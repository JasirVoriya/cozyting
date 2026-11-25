//! 8899 控制会话（每台音箱一条 TCP，发送端为客户端）。
//!
//! 明文引导与建连帧序：
//!
//! ```text
//! R→S 0x0028(seq=r) 数字挑战
//! S→R 0x0036(seq0) "1.0.1123012\0" + 0x0029(seq=r) HMAC   （同一次 write）
//! S→R 0x001e(seq1)
//! R→S 0x0037(seq0)
//! S→R 0x0058(seq2) {"sourceName":...}
//! R→S 0x001f(seq1) + 0x0059(seq2) 任意顺序
//! S→R 0x0058(seq3) {"isSameAccount":0} ; 0x0034(seq4)
//! S→R 0x000e / 0x0014 / 0x001c（顺序不固定）
//! ... preparePlayback：0x0058 / 0x001e / 0x0058 / 0x0034 / 0x001a / 0x0040 SetPlaySource
//! S→R 0x0000 Open "wfd://<手机IP>:<反向端口>?mirrorMode=1\0"  （不等 0x0001/0x0041）
//! 音箱反向连入 → RTSP（见 rtsp.rs）
//! S→R 0x0012 SetMediaInfo（在 RTSP PLAY 应答后）
//! 运行期：每 5s 0x001a 心跳；15s 无心跳活动判定断线；SetVolume 等精确 ACK 匹配
//! ```
//! 注：序号是**每条连接**独立的；引导+准备阶段使用 0..15，运行期序号从 16 开始递增。

use crate::commands as c;
use crate::frame::{decode_notify, decode_scalar5, device_info_meta, encode_volume, DeviceInfoMeta, Frame};
use crate::auth::legacy;
use crate::{proto, Result};
use std::io::{BufReader, BufWriter, Write};
use std::net::{Ipv4Addr, Shutdown, SocketAddr, SocketAddrV4, TcpStream};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub const NATIVE_VERSION_STRING: &str = "1.0.1123012\0";

#[derive(Clone, Debug)]
pub struct SourceIdentity {
    /// 显示在接收端上的来源名（Android 端运行期可传入实际设备名）。
    pub source_name: String,
    /// SetPlaySource 的 `ref_function`：`single_room` / `multi_room` / `stereo`。
    pub ref_function: String,
}

impl Default for SourceIdentity {
    fn default() -> Self {
        Self { source_name: "Android Sender".into(), ref_function: "single_room".into() }
    }
}

impl SourceIdentity {
    /// SetPlaySource(0x0040) 文本载荷。
    pub fn set_play_source_json(&self) -> String {
        format!(
            r#"{{"ref_channel":"controlcenter","ref_function":"{}","ref_content":"music_wangyiyun"}}"#,
            self.ref_function
        )
    }
}

/// Open(0x0000) 文本载荷（含结尾 `\0`）。
pub fn open_wfd_url(local: Ipv4Addr, reverse_port: u16) -> String {
    format!("wfd://{local}:{reverse_port}?mirrorMode=1\0")
}

#[derive(Clone, Debug, Default)]
pub struct ReceiverStatus {
    pub volume: Option<u8>,
    pub state: Option<u32>,
    pub mirror_mode: Option<u32>,
    pub first_audio_pcm: bool,
    /// 引导时 GetDeviceInfo(0x001f) 的原始载荷（键值 TLV，含 channel/deviceType/groupId 等）。
    pub device_info: Option<Vec<u8>>,
}

/// Safety 握手结果（不含任何密钥材料）。
#[derive(Clone, Debug, Default)]
pub struct SafetyReport {
    pub device_version: Option<String>,
    pub selection_json: Option<String>,
    pub selection: Option<crate::auth::safety::SafetySelection>,
    pub device_challenge_decrypted: bool,
    pub device_ack_result: Option<String>,
    pub device_ack_hmac_ok: bool,
    pub mutual_auth: bool,
    pub frames: Vec<String>,
    pub post_auth: Vec<String>,
    pub error: Option<String>,
}

impl SafetyReport {
    pub fn to_json(&self) -> String {
        let q = |o: &Option<String>| o.as_ref().map(|s| format!("{:?}", s)).unwrap_or("null".into());
        format!(
            r#"{{"device_version":{},"selection":{},"device_challenge_decrypted":{},"device_ack_result":{},"device_ack_hmac_ok":{},"mutual_auth":{},"frames":{:?},"post_auth":{:?},"error":{}}}"#,
            q(&self.device_version), q(&self.selection_json), self.device_challenge_decrypted, q(&self.device_ack_result),
            self.device_ack_hmac_ok, self.mutual_auth, self.frames, self.post_auth, q(&self.error)
        )
    }
}

pub const SAFETY_NATIVE_VERSION: &str = "3.1.6030516";
/// 本端 0x1403 里的 result。06:24 UTC+8 实测：发 "1" 音箱收到后立即断开；发 "0"（与音箱自己的 0x1401/0x1403 一致）会话保持。
/// 可用环境变量 MIPLAY_SAFETY_ACK_RESULT 覆盖。
pub const SAFETY_ACK_RESULT: &str = "0";

/// 写端：Safety 互验完成后装上 tx 密码器，此后每帧载荷（含空载荷）都先 SafetyData 加密再套 `$` 头。
pub struct SecureWriter {
    /// 调试“网络黑洞”：now_us() 小于该值时所有运行期写入被静默丢弃（不报错、不关连接）。
    pub mute_until: Arc<std::sync::atomic::AtomicI64>,
    w: BufWriter<TcpStream>,
    tx: Option<crate::auth::safety::DirectionalCipher>,
}

impl SecureWriter {
    fn put(&mut self, f: &Frame) -> Result<()> {
        match self.tx.as_mut() {
            Some(tx) => Frame::new(f.command, f.sequence, tx.seal(&f.payload)).write_to(&mut self.w),
            None => f.write_to(&mut self.w),
        }
    }
}

pub struct ControlSession {
    peer: SocketAddrV4,
    reader: BufReader<TcpStream>,
    rx: Option<crate::auth::safety::DirectionalCipher>,
    pub secure: bool,
    writer: Arc<Mutex<SecureWriter>>,
    pub status: ReceiverStatus,
    next_runtime_seq: u16,
}

impl ControlSession {
    pub fn connect(ip: Ipv4Addr) -> Result<Self> {
        Self::connect_with_timeout(ip, Duration::from_secs(5), Duration::from_secs(10))
    }

    pub fn connect_with_timeout(
        ip: Ipv4Addr,
        connect_timeout: Duration,
        io_timeout: Duration,
    ) -> Result<Self> {
        Self::connect_peer_with_timeout(
            SocketAddrV4::new(ip, crate::discovery::CONTROL_PORT),
            connect_timeout,
            io_timeout,
        )
    }

    fn connect_peer_with_timeout(
        peer: SocketAddrV4,
        connect_timeout: Duration,
        io_timeout: Duration,
    ) -> Result<Self> {
        let s = TcpStream::connect_timeout(&SocketAddr::V4(peer), connect_timeout)?;
        s.set_nodelay(true)?;
        s.set_read_timeout(Some(io_timeout))?;
        s.set_write_timeout(Some(io_timeout))?;
        Ok(Self {
            peer,
            reader: BufReader::new(s.try_clone()?),
            rx: None,
            secure: false,
            writer: Arc::new(Mutex::new(SecureWriter { mute_until: Default::default(), w: BufWriter::new(s), tx: None })),
            status: ReceiverStatus::default(),
            next_runtime_seq: 16,
        })
    }

    /// 本端在该连接上的 IPv4（用于 Open URL 与反向监听绑定，必须与音箱同网段的接口）。
    pub fn local_addr(&self) -> Result<SocketAddrV4> {
        match self.reader.get_ref().local_addr()? {
            SocketAddr::V4(a) => Ok(a),
            _ => proto("IPv6 not supported"),
        }
    }

    pub fn peer(&self) -> SocketAddrV4 {
        self.peer
    }

    fn send(&self, frames: &[Frame]) -> Result<()> {
        let mut w = self.writer.lock().unwrap();
        for f in frames {
            w.put(f)?;
        }
        w.w.flush()?;
        Ok(())
    }

    /// 供 `inspect`：发送已由调用方校验过的帧（调用方负责命令 allowlist）。
    pub(crate) fn send_tracked(&self, frames: &[Frame]) -> Result<()> {
        self.send(frames)
    }

    pub(crate) fn read_frame_public(&mut self) -> Result<Frame> {
        self.read_frame()
    }

    pub(crate) fn read_skipping_notify_public(&mut self) -> Result<Frame> {
        self.read_skipping_notify()
    }

    pub(crate) fn absorb_notify_public(&mut self, f: &Frame) {
        self.absorb_notify(f)
    }

    pub(crate) fn expect_cmd(&mut self, cmd: u16, seq: u16) -> Result<Frame> {
        self.expect(cmd, seq)
    }

    /// 仅关闭本 TCP 套接字（shutdown Both + flush）。**不**发送协议 `CLOSE(0x0002)`。
    /// `ControlSession` 无 Drop→CLOSE；正式播放路径的 `RuntimeWriter::close()` 语义不变。
    pub fn tcp_shutdown_only(self) {
        if let Ok(mut w) = self.writer.lock() {
            let _ = w.w.flush();
            let _ = w.w.get_ref().shutdown(Shutdown::Both);
        }
        let _ = self.reader.get_ref().shutdown(Shutdown::Both);
        // self drop → BufWriter/TcpStream drop；不再写 CLOSE。
    }

    fn text(cmd: u16, seq: u16, s: &str) -> Frame {
        Frame::new(cmd, seq, s.as_bytes().to_vec())
    }

    /// 读一帧；Safety 会话下解密 SafetyData 载荷。
    fn read_frame(&mut self) -> Result<Frame> {
        let mut f = Frame::read_from(&mut self.reader)?;
        if let Some(rx) = self.rx.as_mut() {
            if f.payload.len() >= 25 && f.payload[..4] == crate::auth::safety::ENVELOPE_PREFIX {
                f.payload = rx.open(&f.payload)?;
            }
        }
        Ok(f)
    }

    /// 读一帧；NOTIFY 被吸收进状态后继续读。
    fn read_skipping_notify(&mut self) -> Result<Frame> {
        loop {
            let f = self.read_frame()?;
            if f.command == c::NOTIFY {
                self.absorb_notify(&f);
                continue;
            }
            return Ok(f);
        }
    }

    fn absorb_notify(&mut self, f: &Frame) {
        if let Some((label, v)) = decode_notify(&f.payload) {
            match (label.as_str(), v) {
                ("state", Some(v)) => self.status.state = Some(v as u32),
                ("first-audiopcm", Some(1)) => self.status.first_audio_pcm = true,
                _ => log::debug!("notify {label}={v:?}"),
            }
        }
    }

    fn expect(&mut self, cmd: u16, seq: u16) -> Result<Frame> {
        let f = self.read_skipping_notify()?;
        if f.command != cmd || f.sequence != seq {
            return proto(format!("expected {cmd:#06x}/{seq}, got {:#06x}/{}", f.command, f.sequence));
        }
        Ok(f)
    }

    fn expect_pair(&mut self, a: (u16, u16), b: (u16, u16)) -> Result<()> {
        let (mut ga, mut gb) = (false, false);
        while !(ga && gb) {
            let f = self.read_skipping_notify()?;
            let k = (f.command, f.sequence);
            if f.command == c::GET_DEVICE_INFO_ACK { self.status.device_info = Some(f.payload.clone()); }
            if !ga && k == a { ga = true } else if !gb && k == b { gb = true } else {
                return proto(format!("unexpected {:#06x}/{}", k.0, k.1));
            }
        }
        Ok(())
    }

    /// 旧式明文引导。
    pub fn bootstrap_legacy(&mut self, id: &SourceIdentity) -> Result<()> {
        let ch = self.read_skipping_notify()?;
        if ch.command != c::LEGACY_CHALLENGE || !legacy::is_valid_challenge(&ch.payload) {
            return proto(format!("expected 0x0028 numeric challenge, got {:#06x}", ch.command));
        }
        self.send(&[
            Self::text(c::NATIVE_VERSION, 0, NATIVE_VERSION_STRING),
            Frame::new(c::LEGACY_CHALLENGE_ACK, ch.sequence, legacy::challenge_response(&ch.payload)),
        ])?;
        self.send(&[Frame::empty(c::GET_DEVICE_INFO, 1)])?;
        self.expect(c::NATIVE_VERSION_ACK, 0)?;
        self.send(&[self.source_name_frame(2, id)])?;
        self.expect_pair((c::GET_DEVICE_INFO_ACK, 1), (c::SET_LOCAL_DEVICE_INFO_ACK, 2))?;
        self.send(&[Self::text(c::SET_LOCAL_DEVICE_INFO, 3, r#"{"isSameAccount":0}"#), Frame::empty(c::GET_MIRROR_MODE, 4)])?;
        let mut need = [false; 4]; // acct, mirror, volume, state
        let mut queried = false;
        while need.iter().any(|x| !x) {
            let f = self.read_skipping_notify()?;
            match (f.command, f.sequence) {
                (c::SET_LOCAL_DEVICE_INFO_ACK, 3) => {
                    need[0] = true;
                    if !queried {
                        queried = true;
                        self.send(&[Frame::empty(c::GET_VOLUME, 5), Frame::empty(c::GET_MEDIA_INFO, 6), Frame::empty(c::GET_STATE, 7)])?;
                    }
                }
                (c::GET_MIRROR_MODE_ACK, 4) => { need[1] = true; self.status.mirror_mode = decode_scalar5(&f.payload) }
                (c::GET_VOLUME_ACK, 5) => { need[2] = true; self.status.volume = decode_scalar5(&f.payload).map(|v| v.min(100) as u8) }
                (c::GET_STATE_ACK, 7) => { need[3] = true; self.status.state = decode_scalar5(&f.payload) }
                (cmd, seq) => return proto(format!("unexpected {cmd:#06x}/{seq} during bootstrap")),
            }
        }
        Ok(())
    }

    /// 现代 SafetyAuth 握手：
    /// 1. 音箱先发 `0x0028` 数字挑战；本端发 `0x0036`(seq 1) 协议版本 `3.1.6030516\0` 和同序号 `0x0029` HMAC-SHA1 应答；
    /// 2. 明文 `0x1400`(seq 2)：OPack `cmd` + offer JSON；音箱回明文 `0x1401`：OPack `ack` + 选择 JSON；
    /// 3. `authKey` = `MD5(映射(音箱IP+8899+本端IP+本端端口))` 的 hex；AES key = IV = `authKey` 前 16 字符，两个方向各自 CBC 链式；
    /// 4. 本端发加密 `0x1402`(seq 3)：SafetyData(OPack cmd `{"authMsg": MD5hex(微秒)}`)；
    ///    音箱发加密 `0x1402`：`{"authMsg"}`，本端回加密 `0x1403`(同序号)：OPack ack `{"result":"0","authMsgAck": HMAC(authKey, 对方authMsg)}`；
    ///    音箱回加密 `0x1403`：`{"result","authMsgAck"}`，校验 = `HMAC(authKey, 本端authMsg)` → 双向互验完成。
    /// 互验完成后，本连接后续所有控制帧均走 SafetyData 加密。
    pub fn safety_handshake(&mut self, version: &str, post_auth: &[u32]) -> Result<SafetyReport> {
        use crate::auth::safety::*;
        let mut rep = SafetyReport::default();
        let ch = self.read_skipping_notify()?;
        if ch.command != c::LEGACY_CHALLENGE || !legacy::is_valid_challenge(&ch.payload) {
            return proto(format!("expected 0x0028 numeric challenge, got {:#06x}", ch.command));
        }
        let mut ver = version.to_string();
        if !ver.ends_with('\0') { ver.push('\0'); }
        self.send(&[
            Self::text(c::NATIVE_VERSION, 1, &ver),
            Frame::new(c::LEGACY_CHALLENGE_ACK, ch.sequence, legacy::challenge_response(&ch.payload)),
            Frame::new(c::SAFETY_INFO, 2, opack_wrap("cmd", SAFETY_INFO_OFFER_JSON.as_bytes())?),
        ])?;
        let local = self.local_addr()?;
        let auth_key = derive_auth_key(self.peer, local);
        let mut tx = DirectionalCipher::from_auth_key(&auth_key)?;
        let mut rx = DirectionalCipher::from_auth_key(&auth_key)?;
        let my_msg = new_auth_msg();
        let mut alg = 0u32;
        let (mut sent_challenge, mut answered, mut verified) = (false, false, false);
        while !(answered && verified) {
            let f = match self.read_skipping_notify() {
                Ok(f) => f,
                Err(e) => { rep.error = Some(format!("{e}")); return Ok(rep); }
            };
            rep.frames.push(format!("{:#06x}/{} len={}", f.command, f.sequence, f.payload.len()));
            match f.command {
                c::NATIVE_VERSION_ACK => rep.device_version = Some(String::from_utf8_lossy(&f.payload).trim_end_matches('\0').to_string()),
                c::SAFETY_INFO_ACK => {
                    let (_, _, json) = opack_unwrap(&f.payload)?;
                    rep.selection_json = Some(String::from_utf8_lossy(&json).into_owned());
                    let sel = SafetySelection::parse(&json).ok_or_else(|| crate::Error::Protocol("bad 0x1401 json".into()))?;
                    alg = sel.auth_algorithm_type;
                    rep.selection = Some(sel);
                    if !sent_challenge {
                        sent_challenge = true;
                        let body = tx.seal(&opack_wrap("cmd", format!(r#"{{"authMsg":"{my_msg}"}}"#).as_bytes())?);
                        self.send(&[Frame::new(c::SAFETY_AUTH, 3, body)])?;
                    }
                }
                c::SAFETY_AUTH => {
                    let plain = rx.open(&f.payload)?;
                    let (tag, _, json) = opack_unwrap(&plain)?;
                    let peer_msg = json_str(&json, "authMsg").ok_or_else(|| crate::Error::Protocol(format!("0x1402 tag={tag} no authMsg")))?;
                    rep.device_challenge_decrypted = true;
                    let ack = auth_msg_ack_alg(if alg == 0 { 4 } else { alg }, &auth_key, &peer_msg)?;
                    let body = tx.seal(&opack_wrap("ack", format!(r#"{{"result":"{}","authMsgAck":"{ack}"}}"#, std::env::var("MIPLAY_SAFETY_ACK_RESULT").unwrap_or_else(|_| SAFETY_ACK_RESULT.to_string())).as_bytes())?);
                    self.send(&[Frame::new(c::SAFETY_AUTH_ACK, f.sequence, body)])?;
                    answered = true;
                }
                c::SAFETY_AUTH_ACK => {
                    let plain = rx.open(&f.payload)?;
                    let (_, _, json) = opack_unwrap(&plain)?;
                    rep.device_ack_result = json_str(&json, "result");
                    let got = json_str(&json, "authMsgAck").unwrap_or_default();
                    verified = got == auth_msg_ack_alg(if alg == 0 { 4 } else { alg }, &auth_key, &my_msg)?;
                    rep.device_ack_hmac_ok = verified;
                    if !verified { rep.error = Some("device authMsgAck HMAC mismatch".into()); return Ok(rep); }
                }
                _ => {}
            }
        }
        rep.mutual_auth = true;
        // 互验完成：此后本连接所有帧都走 SafetyData（两个方向各自延续 CBC 链）。
        self.writer.lock().unwrap().tx = Some(tx);
        self.rx = Some(rx);
        self.secure = true;
        // 可选：认证后发只读的空载荷命令观察回帧。编码：低 16 位 = 命令；0x1_0000 位 = 明文发送；0 = 只观察。
        if !post_auth.is_empty() {
            let mut seq = 4u16;
            for &code in post_auth {
                let cmd = (code & 0xffff) as u16;
                if cmd == 0 { continue; }
                if code & 0x1_0000 != 0 {
                    let mut w = self.writer.lock().unwrap();
                    Frame::empty(cmd, seq).write_to(&mut w.w)?;
                    w.w.flush()?;
                    rep.post_auth.push(format!("sent {cmd:#06x}/{seq} (plain empty)"));
                } else {
                    self.send(&[Frame::empty(cmd, seq)])?;
                    rep.post_auth.push(format!("sent {cmd:#06x}/{seq} (encrypted empty)"));
                }
                seq += 1;
            }
            self.reader.get_ref().set_read_timeout(Some(std::time::Duration::from_secs(12)))?;
            let t0 = std::time::Instant::now();
            let deadline = t0 + std::time::Duration::from_secs(12);
            while std::time::Instant::now() < deadline {
                match self.read_frame() {
                    Ok(f) => {
                        let txt: String = String::from_utf8_lossy(&f.payload).chars().filter(|c| !c.is_control()).take(120).collect();
                        rep.post_auth.push(format!("+{}ms recv {:#06x}/{} {} bytes (decrypted): {}", t0.elapsed().as_millis(), f.command, f.sequence, f.payload.len(), txt));
                    }
                    Err(e) => { rep.post_auth.push(format!("+{}ms read end: {e}", t0.elapsed().as_millis())); break; }
                }
            }
            self.reader.get_ref().set_read_timeout(Some(Duration::from_secs(10)))?;
        }
        Ok(rep)
    }

    /// Safety 引导：互验后把 legacy 引导里 0x0029 之后的那段查询/设置原样走一遍（全部加密）。
    /// 06:30 UTC+8 实测：LX06/L17A 认证后接受加密 0x001e 并回加密 0x001f。
    pub fn bootstrap_safety(&mut self, id: &SourceIdentity) -> Result<SafetyReport> {
        let rep = self.safety_handshake(SAFETY_NATIVE_VERSION, &[])?;
        if !rep.mutual_auth {
            return proto(format!("safety auth failed: {}", rep.to_json()));
        }
        self.send(&[Frame::empty(c::GET_DEVICE_INFO, 4), self.source_name_frame(5, id)])?;
        self.expect_pair((c::GET_DEVICE_INFO_ACK, 4), (c::SET_LOCAL_DEVICE_INFO_ACK, 5))?;
        self.send(&[Self::text(c::SET_LOCAL_DEVICE_INFO, 6, r#"{"isSameAccount":0}"#), Frame::empty(c::GET_MIRROR_MODE, 7)])?;
        let mut need = [false; 4];
        let mut queried = false;
        while need.iter().any(|x| !x) {
            let f = self.read_skipping_notify()?;
            match f.command {
                c::SET_LOCAL_DEVICE_INFO_ACK => {
                    need[0] = true;
                    if !queried {
                        queried = true;
                        self.send(&[Frame::empty(c::GET_VOLUME, 5), Frame::empty(c::GET_MEDIA_INFO, 6), Frame::empty(c::GET_STATE, 7)])?;
                    }
                }
                c::GET_MIRROR_MODE_ACK => { need[1] = true; self.status.mirror_mode = decode_scalar5(&f.payload) }
                c::GET_VOLUME_ACK => { need[2] = true; self.status.volume = decode_scalar5(&f.payload).map(|v| v.min(100) as u8) }
                c::GET_STATE_ACK => { need[3] = true; self.status.state = decode_scalar5(&f.payload) }
                cmd => log::info!("safety bootstrap: other {cmd:#06x}/{}", f.sequence),
            }
        }
        Ok(rep)
    }

    pub fn prepare_playback(&mut self, id: &SourceIdentity) -> Result<()> {
        self.send(&[self.source_name_frame(8, id), Frame::empty(c::GET_DEVICE_INFO, 9)])?;
        self.expect_pair((c::SET_LOCAL_DEVICE_INFO_ACK, 8), (c::GET_DEVICE_INFO_ACK, 9))?;
        self.send(&[Self::text(c::SET_LOCAL_DEVICE_INFO, 10, r#"{"isSameAccount":0}"#)])?;
        self.expect(c::SET_LOCAL_DEVICE_INFO_ACK, 10)?;
        self.send(&[Frame::empty(c::GET_MIRROR_MODE, 11)])?;
        let mirror_ack = self.expect(c::GET_MIRROR_MODE_ACK, 11)?;
        // prepare 二次 GET_MIRROR_MODE 必须写回 status；历史成功 INITIAL 的 mirror_mode
        // 来自引导/prepare 查询值，丢弃 ACK 会把失败摘要钉在过期 bootstrap 快照上。
        self.status.mirror_mode = decode_scalar5(&mirror_ack.payload);
        self.send(&[Frame::empty(c::HEARTBEAT, 12)])?;
        self.expect(c::HEARTBEAT_ACK, 12)?;
        let body = id.set_play_source_json();
        log::info!(
            "ctrl send SET_PLAY_SOURCE cmd={:#06x} seq=13 ref_function={} bytes={} secure={} prepare_mirror={:?}",
            c::SET_PLAY_SOURCE,
            id.ref_function,
            body.len(),
            self.secure,
            self.status.mirror_mode
        );
        self.send(&[Self::text(c::SET_PLAY_SOURCE, 13, &body)])
    }

    pub fn send_open(&self, local: Ipv4Addr, reverse_port: u16) -> Result<()> {
        let url = open_wfd_url(local, reverse_port);
        log::info!(
            "ctrl send OPEN cmd={:#06x} seq=14 url_bytes={} secure={} (no payload body log)",
            c::OPEN,
            url.len(),
            self.secure
        );
        self.send(&[Self::text(c::OPEN, 14, &url)])
    }

    /// Open 之后短暂排空控制通道上已到达的 ACK（0x0041 / 0x0001 等），只用于可观测性。
    /// 不延长反向 RTSP 等待；读超时立即结束；不记录载荷正文。
    pub fn poll_incoming(&mut self, budget: Duration) -> Result<Vec<(u16, u16, usize)>> {
        self.reader.get_ref().set_read_timeout(Some(Duration::from_millis(20)))?;
        let deadline = std::time::Instant::now() + budget;
        let mut out = Vec::new();
        while std::time::Instant::now() < deadline {
            match self.read_frame() {
                Ok(f) => {
                    if f.command == c::NOTIFY {
                        self.absorb_notify(&f);
                        continue;
                    }
                    out.push((f.command, f.sequence, f.payload.len()));
                }
                Err(_) => break,
            }
        }
        self.reader.get_ref().set_read_timeout(Some(Duration::from_secs(10)))?;
        Ok(out)
    }

    pub fn send_media_info(&self, title: &str, artist: &str, source_name: &str, volume: u8) -> Result<()> {
        let json = format!(
            r#"{{"mArtist":"{artist}","mAlbum":"","mTitle":"{title}","mDuration":2147483647,"id":"","mCoverUrl":"","status":0,"volume":{volume},"mArt":"","mSourceName":"{source_name}","mDeviceState":2}}"#
        );
        self.send(&[Self::text(c::SET_MEDIA_INFO, 15, &json)])
    }

    /// 运行期写入句柄（心跳线程、音量命令使用）。读循环由调用方在独立线程中跑 `runtime_read_loop`。
    pub fn runtime_writer(&self) -> RuntimeWriter {
        RuntimeWriter { writer: self.writer.clone(), seq: Arc::new(Mutex::new(self.next_runtime_seq)) }
    }

    /// 旧式引导之后、不 Open 的情况下读取/设置音量：用运行期序号 16 发 SetVolume 并等精确 ACK，然后 CLOSE。
    /// 用于“推流前先静音”。
    pub fn set_volume_then_close(mut self, v: u8) -> Result<Option<u8>> {
        let w = self.runtime_writer();
        let payload = encode_volume(v).to_vec();
        let seq = w.write(c::SET_VOLUME, payload.clone())?;
        let deadline = std::time::Instant::now() + Duration::from_secs(3);
        let mut result = None;
        while std::time::Instant::now() < deadline {
            let f = match self.read_frame() { Ok(f) => f, Err(e) => { log::warn!("pre-mute read ended: {e}"); break } };
            match f.command {
                c::NOTIFY => self.absorb_notify(&f),
                c::HEARTBEAT => w.write_with_seq(Frame::empty(c::HEARTBEAT_ACK, f.sequence))?,
                c::SET_VOLUME_ACK if f.sequence == seq => {
                    log::info!("SetVolume ACK seq {} payload {:02x?}", f.sequence, f.payload);
                    result = crate::frame::decode_volume_ack(&f.payload);
                    break;
                }
                _ => log::debug!("pre-mute: ignoring {:#06x}/{}", f.command, f.sequence),
            }
        }
        let _ = w.close();
        Ok(result)
    }

    /// 旧式引导之后、不 Open：发一条命令并等待指定 ACK（同序号），然后发协议 **CLOSE**。
    /// 名称含 close：不是「仅 TCP 断开」的只查询；状态调查请用 `crate::inspect`。
    pub fn command_then_close(mut self, cmd: u16, payload: Vec<u8>, ack: u16) -> Result<Option<Frame>> {
        let w = self.runtime_writer();
        let seq = w.write(cmd, payload)?;
        let deadline = std::time::Instant::now() + Duration::from_secs(3);
        let mut result = None;
        while std::time::Instant::now() < deadline {
            let f = match self.read_frame() {
                Ok(f) => f,
                Err(crate::Error::Io(e)) if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => continue,
                Err(e) => { log::warn!("query read ended: {e}"); break }
            };
            match f.command {
                c::NOTIFY => self.absorb_notify(&f),
                c::HEARTBEAT => w.write_with_seq(Frame::empty(c::HEARTBEAT_ACK, f.sequence))?,
                x if x == ack => {
                    log::info!("{cmd:#06x} -> ack {x:#06x} seq {} (sent {seq}) payload {:02x?}", f.sequence, f.payload);
                    result = Some(f);
                    break;
                }
                other => log::info!("query: other frame {other:#06x}/{} {:02x?}", f.sequence, f.payload),
            }
        }
        let _ = w.close();
        Ok(result)
    }

    pub fn runtime_read_loop(self, w: RuntimeWriter, on_frame: impl FnMut(&Frame, &ReceiverStatus)) -> Result<()> {
        self.runtime_read_loop_until(w, Arc::new(std::sync::atomic::AtomicBool::new(true)), on_frame)
    }

    /// 运行期控制读循环，支持外部 `alive` 退出标志；当 `alive` 置 `false` 或底层 8899 连接被
    /// `RuntimeWriter::shutdown_socket()` 关闭时立即退出，避免残留僵尸连接继续回复心跳。
    pub fn runtime_read_loop_until(
        mut self,
        w: RuntimeWriter,
        alive: Arc<std::sync::atomic::AtomicBool>,
        mut on_frame: impl FnMut(&Frame, &ReceiverStatus),
    ) -> Result<()> {
        self.reader.get_ref().set_read_timeout(Some(Duration::from_secs(15)))?;
        while alive.load(std::sync::atomic::Ordering::Relaxed) {
            let f = self.read_frame()?; // 15s 超时 == 心跳看门狗
            if !alive.load(std::sync::atomic::Ordering::Relaxed) {
                break;
            }
            match f.command {
                c::HEARTBEAT => w.write_with_seq(Frame::empty(c::HEARTBEAT_ACK, f.sequence))?,
                c::NOTIFY => self.absorb_notify(&f),
                _ => {}
            }
            on_frame(&f, &self.status);
        }
        Ok(())
    }
}

impl ControlSession {
    fn source_name_frame(&self, seq: u16, id: &SourceIdentity) -> Frame {
        Self::text(c::SET_LOCAL_DEVICE_INFO, seq, &format!(r#"{{"sourceName":"{}"}}"#, id.source_name.replace('/', "\\/")))
    }
}

/// 极简只读查询设备在 TCP 8899 `GET_DEVICE_INFO(0x001e)` 中返回的展示元数据（`miName` + `roomName`）。
///
/// 约束：
/// - 优先明文 legacy 挑战应答后仅发 `GET_DEVICE_INFO(0x001e)`；若未收到合法 `0x001f` 且时间预算有余，回退 Safety 互验后再查 `GET_DEVICE_INFO`。
/// - 全程绝不发送 `OPEN` / `CLOSE` / `SET_*`，结束后仅 `tcp_shutdown_only()`。
/// - 超时、拒连或协议错误时返回 `None`，由调用方回退缓存名/房间名或 mDNS 老名字；
///   收到合法 `0x001f` 时返回 `Some(DeviceInfoMeta)`（字段缺失或空白时对应项为 `None`）。
pub fn fetch_device_info_meta(ip: Ipv4Addr, timeout: Duration) -> Option<DeviceInfoMeta> {
    fetch_device_info_meta_at(SocketAddrV4::new(ip, crate::discovery::CONTROL_PORT), timeout)
}

/// 极简只读查询设备在 TCP 8899 `GET_DEVICE_INFO(0x001e)` 中返回的 `miName`（用户自定义名）。
pub fn fetch_device_mi_name(ip: Ipv4Addr, timeout: Duration) -> Option<String> {
    fetch_device_info_meta(ip, timeout).and_then(|m| m.mi_name)
}

fn fetch_device_info_meta_at(peer: SocketAddrV4, timeout: Duration) -> Option<DeviceInfoMeta> {
    let deadline = Instant::now() + timeout;
    let rem = deadline.saturating_duration_since(Instant::now());
    if rem.is_zero() {
        return None;
    }
    match fetch_device_info_meta_legacy(peer, rem) {
        Ok(meta) => return Some(meta),
        Err(e) => {
            log::debug!("fetch_device_info_meta legacy {peer} failed: {e}");
        }
    }
    let rem = deadline.saturating_duration_since(Instant::now());
    if rem < Duration::from_millis(120) {
        return None;
    }
    match fetch_device_info_meta_safety(peer, rem) {
        Ok(meta) => Some(meta),
        Err(e) => {
            log::debug!("fetch_device_info_meta safety {peer} failed: {e}");
            None
        }
    }
}

fn fetch_device_info_meta_legacy(peer: SocketAddrV4, timeout: Duration) -> Result<DeviceInfoMeta> {
    let mut ctl = ControlSession::connect_peer_with_timeout(peer, timeout, timeout)?;
    let res = (|| -> Result<DeviceInfoMeta> {
        let ch = ctl.read_skipping_notify()?;
        if ch.command != c::LEGACY_CHALLENGE || !legacy::is_valid_challenge(&ch.payload) {
            return proto(format!("expected 0x0028 challenge, got {:#06x}", ch.command));
        }
        ctl.send(&[
            ControlSession::text(c::NATIVE_VERSION, 0, NATIVE_VERSION_STRING),
            Frame::new(c::LEGACY_CHALLENGE_ACK, ch.sequence, legacy::challenge_response(&ch.payload)),
            Frame::empty(c::GET_DEVICE_INFO, 1),
        ])?;
        for _ in 0..8 {
            let f = ctl.read_skipping_notify()?;
            match (f.command, f.sequence) {
                (c::GET_DEVICE_INFO_ACK, 1) => return Ok(device_info_meta(&f.payload)),
                (c::NATIVE_VERSION_ACK, _) | (c::HEARTBEAT, _) => continue,
                (cmd, seq) => return proto(format!("unexpected {cmd:#06x}/{seq} waiting for GET_DEVICE_INFO_ACK")),
            }
        }
        proto("too many frames before GET_DEVICE_INFO_ACK")
    })();
    ctl.tcp_shutdown_only();
    res
}

fn fetch_device_info_meta_safety(peer: SocketAddrV4, timeout: Duration) -> Result<DeviceInfoMeta> {
    let mut ctl = ControlSession::connect_peer_with_timeout(peer, timeout, timeout)?;
    let res = (|| -> Result<DeviceInfoMeta> {
        let rep = ctl.safety_handshake(SAFETY_NATIVE_VERSION, &[])?;
        if !rep.mutual_auth {
            return proto("safety mutual_auth failed");
        }
        ctl.send(&[Frame::empty(c::GET_DEVICE_INFO, 4)])?;
        for _ in 0..8 {
            let f = ctl.read_skipping_notify()?;
            match (f.command, f.sequence) {
                (c::GET_DEVICE_INFO_ACK, 4) => return Ok(device_info_meta(&f.payload)),
                (c::NATIVE_VERSION_ACK, _) | (c::HEARTBEAT, _) => continue,
                (cmd, seq) => return proto(format!("unexpected {cmd:#06x}/{seq} waiting for encrypted GET_DEVICE_INFO_ACK")),
            }
        }
        proto("too many frames before encrypted GET_DEVICE_INFO_ACK")
    })();
    ctl.tcp_shutdown_only();
    res
}

#[derive(Clone)]
pub struct RuntimeWriter {
    writer: Arc<Mutex<SecureWriter>>,
    seq: Arc<Mutex<u16>>,
}

impl RuntimeWriter {
    pub fn write(&self, cmd: u16, payload: Vec<u8>) -> Result<u16> {
        let mut s = self.seq.lock().unwrap();
        let seq = *s;
        *s = s.wrapping_add(1);
        self.write_with_seq(Frame::new(cmd, seq, payload))?;
        Ok(seq)
    }
    pub fn write_with_seq(&self, f: Frame) -> Result<()> {
        let mut w = self.writer.lock().unwrap();
        if crate::clock::now_us() < w.mute_until.load(std::sync::atomic::Ordering::Relaxed) { return Ok(()) }
        w.put(&f)?;
        w.w.flush()?;
        Ok(())
    }
    pub fn heartbeat(&self) -> Result<u16> { self.write(c::HEARTBEAT, vec![]) }
    /// 发送 SetVolume(0x000c)，ACK(0x000d) 匹配由读循环回调完成。
    pub fn set_volume(&self, v: u8) -> Result<u16> { self.write(c::SET_VOLUME, encode_volume(v).to_vec()) }
    pub fn pause(&self) -> Result<u16> { self.write(c::PAUSE, vec![]) }
    /// 恢复播放：连续发送两次 Resume(0x0006)，间隔 29ms。
    pub fn resume(&self) -> Result<()> {
        self.write(c::RESUME, vec![])?;
        std::thread::sleep(Duration::from_millis(29));
        self.write(c::RESUME, vec![])?;
        Ok(())
    }
    pub fn close(&self) -> Result<u16> { self.write(c::CLOSE, vec![]) }
    /// 强制关闭 8899 控制通道底层 TCP，立即唤醒阻塞在 `runtime_read_loop_until` 中的读线程。
    pub fn shutdown_socket(&self) {
        if let Ok(mut w) = self.writer.lock() {
            let _ = w.w.flush();
            let _ = w.w.get_ref().shutdown(Shutdown::Both);
        }
    }
    pub fn mute_handle(&self) -> Arc<std::sync::atomic::AtomicI64> { self.writer.lock().unwrap().mute_until.clone() }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn set_play_source_json_embeds_ref_function() {
        let id = SourceIdentity { source_name: "Phone".into(), ref_function: "multi_room".into() };
        assert_eq!(
            id.set_play_source_json(),
            r#"{"ref_channel":"controlcenter","ref_function":"multi_room","ref_content":"music_wangyiyun"}"#
        );
    }

    #[test]
    fn open_wfd_url_matches_field_advertisement() {
        // Open wfd://192.0.2.182:40159
        let u = open_wfd_url(Ipv4Addr::new(192, 0, 2, 182), 40159);
        assert!(u.starts_with("wfd://192.0.2.182:40159?mirrorMode=1"));
        assert!(u.ends_with('\0'));
    }

    #[test]
    fn prepare_mirror_ack_scalar_decodes_history_and_v7_values() {
        // GET_MIRROR_MODE_ACK 5 字节标量；历史成功常见 2，v7 LX06 失败摘要常见 1。
        assert_eq!(decode_scalar5(&[0, 0, 0, 0, 2]), Some(2));
        assert_eq!(decode_scalar5(&[0, 0, 0, 0, 1]), Some(1));
        assert_eq!(decode_scalar5(&[0, 0, 0, 0, 0]), Some(0));
    }

    #[test]
    fn fetch_device_mi_name_reads_tlv_without_sending_close() {
        use std::net::TcpListener;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = match listener.local_addr().unwrap() {
            SocketAddr::V4(v4) => v4,
            _ => unreachable!(),
        };
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            Frame::new(c::LEGACY_CHALLENGE, 7, b"1234567890123".to_vec())
                .write_to(&mut stream)
                .unwrap();
            let mut seen_cmds = Vec::new();
            for _ in 0..3 {
                let f = Frame::read_from(&mut stream).unwrap();
                seen_cmds.push(f.command);
            }
            Frame::new(c::NATIVE_VERSION_ACK, 0, vec![]).write_to(&mut stream).unwrap();
            let name = "客厅音箱Pro";
            let room = "客厅";
            let nb = name.as_bytes();
            let rb = room.as_bytes();
            let mut tlv = vec![0u8, 0, 0];
            tlv.push(6);
            tlv.extend_from_slice(b"miName");
            tlv.push(0x0c);
            tlv.extend_from_slice(&(nb.len() as u16).to_be_bytes());
            tlv.extend_from_slice(nb);
            tlv.push(8);
            tlv.extend_from_slice(b"roomName");
            tlv.push(0x0c);
            tlv.extend_from_slice(&(rb.len() as u16).to_be_bytes());
            tlv.extend_from_slice(rb);
            let total = (tlv.len() - 3) as u16;
            tlv[1..3].copy_from_slice(&total.to_be_bytes());
            Frame::new(c::GET_DEVICE_INFO_ACK, 1, tlv).write_to(&mut stream).unwrap();
            // 确认客户端仅做 TCP shutdown，不发送 CLOSE(0x0002) 等任何后续帧
            let trailing = Frame::read_from(&mut stream);
            assert!(trailing.is_err(), "expected EOF after tcp_shutdown_only, got {trailing:?}");
            seen_cmds
        });

        let got = fetch_device_info_meta_at(addr, Duration::from_secs(2));
        let seen_cmds = server.join().unwrap();
        assert_eq!(
            got,
            Some(DeviceInfoMeta {
                mi_name: Some("客厅音箱Pro".into()),
                room_name: Some("客厅".into()),
            })
        );
        assert_eq!(seen_cmds, vec![c::NATIVE_VERSION, c::LEGACY_CHALLENGE_ACK, c::GET_DEVICE_INFO]);
        assert!(!seen_cmds.contains(&c::CLOSE));
        assert!(!seen_cmds.contains(&c::OPEN));
    }

    #[test]
    fn runtime_writer_shutdown_socket_unblocks_read_loop_immediately() {
        use std::net::TcpListener;
        use std::sync::atomic::{AtomicBool, Ordering};
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = match listener.local_addr().unwrap() {
            SocketAddr::V4(v4) => v4,
            _ => unreachable!(),
        };
        let server = std::thread::spawn(move || {
            let (_stream, _) = listener.accept().unwrap();
            // 保持服务端不主动断开，验证客户端 shutdown_socket 会立刻唤醒阻塞在 15s 读超时的循环
            std::thread::sleep(Duration::from_millis(800));
        });

        let ctl = ControlSession::connect_peer_with_timeout(
            addr,
            Duration::from_secs(2),
            Duration::from_secs(5),
        )
        .unwrap();
        let rw = ctl.runtime_writer();
        let alive = Arc::new(AtomicBool::new(true));
        let alive_bg = alive.clone();
        let rw_bg = rw.clone();
        let started = Instant::now();
        let loop_handle = std::thread::spawn(move || {
            ctl.runtime_read_loop_until(rw_bg, alive_bg, |_, _| {})
        });
        std::thread::sleep(Duration::from_millis(40));
        alive.store(false, Ordering::Relaxed);
        rw.shutdown_socket();
        let _ = loop_handle.join().unwrap();
        assert!(
            started.elapsed() < Duration::from_secs(2),
            "read loop should exit promptly after shutdown_socket, took {:?}",
            started.elapsed()
        );
        server.join().unwrap();
    }
}
