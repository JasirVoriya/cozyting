//! 可运行的投送会话：N 台音箱、一条共享 AAC 时间线、一个共享 TIME_OFFSET。
//!
//! 流程与已在真机验证的 MiPlayForWindows Android 实现对齐（事实参考，代码独立编写）：
//! - 每台音箱：8899 旧式引导 → preparePlayback → 绑定 UDP 计时 + 反向 TCP 监听 → Open → 接受 RTSP/辅助/音频三条回连
//!   → RTSP 握手到 PLAY → TIME_OFFSET（组共享）→ 运行期控制（心跳/音量/通知）→ 媒体写线程；
//! - 组：最后一台到达 PLAY 时预读 14 个 AU，然后取 offset = now + delay（MiPlayCastSession.kt:137-176, 491-512）；
//! - 媒体：单线程按官方起播节奏 + 21.333ms 推进共享 PTS，每台音箱独立打包器/队列。

use crate::clock::now_us;
use crate::clock_sync::TimerServer;
use crate::commands as c;
use crate::control::{ControlSession, RuntimeWriter, SourceIdentity};
use crate::frame::{decode_notify, encode_volume, Frame};
use crate::media::{Packetizer, PacketizerState};
use crate::rtsp::{read_message, SourceRtsp};
use crate::timeline::{OutputTimestamp, SharedTimeline};
use crate::{Error, Result};
use std::collections::VecDeque;
use std::io::{BufReader, Write};
use std::net::{Ipv4Addr, Shutdown, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU32, AtomicU64, Ordering};
use std::sync::mpsc::{sync_channel, Receiver as ChanRx, SyncSender, TrySendError};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

pub const STARTUP_RUNWAY_AUS: usize = 14;
pub const MAX_RECEIVERS: usize = 63;

/// 官方抓包的起播节奏（毫秒），之后按 1024/48000 s 推进（MiPlayMediaPacketizer.kt:86-102 记录的时间点）。
const STARTUP_DUE_MS: [f64; 19] = [
    0.000, 0.000, 0.812, 0.812, 3.463, 4.230, 20.360, 22.199, 23.864, 40.903, 42.905, 62.727, 84.784,
    111.306, 128.829, 148.735, 170.391, 202.805, 211.654,
];
const AU_MS: f64 = 1024.0 / 48_000.0 * 1000.0;

fn due_after_ms(sent: u64) -> f64 {
    if (sent as usize) < STARTUP_DUE_MS.len() {
        STARTUP_DUE_MS[sent as usize]
    } else {
        STARTUP_DUE_MS[STARTUP_DUE_MS.len() - 1] + (sent - (STARTUP_DUE_MS.len() as u64 - 1)) as f64 * AU_MS
    }
}

/// AAC-LC 48k 立体声原始 AU 来源（不含 ADTS）。`None` = 结束。
pub trait AuSource: Send {
    fn next_au(&mut self) -> Option<Vec<u8>>;
    /// 恢复播放前丢掉暂停前积压的数据（官方 Resume 前也丢弃暂停前的 PCM）。
    fn flush(&mut self) {}
}

/// 全零 PCM 编出的 AAC-LC 立体声静音帧（ffmpeg aac 编码器输出的稳定帧，6 字节）。
pub const SILENT_AAC_LC_STEREO: [u8; 6] = [0x21, 0x10, 0x04, 0x60, 0x8c, 0x1c];

pub struct SilenceSource {
    pub remaining: Option<u64>,
}
impl AuSource for SilenceSource {
    fn next_au(&mut self) -> Option<Vec<u8>> {
        if let Some(r) = self.remaining.as_mut() {
            if *r == 0 { return None }
            *r -= 1;
        }
        Some(SILENT_AAC_LC_STEREO.to_vec())
    }
}

/// 由外部（JNI/MediaCodec）推入的 AU。
pub struct ChannelSource(pub ChanRx<Vec<u8>>);
impl AuSource for ChannelSource {
    fn next_au(&mut self) -> Option<Vec<u8>> {
        self.0.recv().ok()
    }
    fn flush(&mut self) {
        while self.0.try_recv().is_ok() {}
    }
}

#[derive(Clone, Debug)]
pub struct CastConfig {
    pub identity: SourceIdentity,
    pub playback_delay_us: i64,
    /// 推流前先用独立 8899 会话把音量设到该值（调试静音用 Some(0)）。
    pub premute: Option<u8>,
    /// RTSP 就绪（PLAY + TIME_OFFSET）后对该音箱设一次音量（None 不设）。中途加入/重连的音箱同样适用。
    pub volume_after_play: Option<u8>,
    /// 控制通道超过该时长没有收到任何帧（心跳 ACK 每 5 s 一次）就判定断线。
    pub heartbeat_timeout: Duration,
    /// 断线后自动重连（复用当前组 TIME_OFFSET，等同中途加入）。
    pub auto_reconnect: bool,
    /// 8899 认证链路：Legacy（明文，官方 milink 对这两台的做法）/ Safety（SafetyAuth 互验后全加密）/
    /// Auto（先明文，明文引导失败再换一条连接走 Safety）。
    pub auth: AuthMode,
}

impl Default for CastConfig {
    fn default() -> Self {
        Self {
            identity: SourceIdentity::default(),
            playback_delay_us: crate::netinfo::DELAY_DEFAULT_US,
            premute: None,
            volume_after_play: None,
            heartbeat_timeout: Duration::from_secs(15),
            auto_reconnect: true,
            auth: AuthMode::Auto,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AuthMode { Legacy, Safety, Auto }

impl AuthMode {
    pub fn parse(s: &str) -> Self {
        match s { "safety" => Self::Safety, "legacy" => Self::Legacy, _ => Self::Auto }
    }
}

/// 按模式建立已引导的控制会话；返回 (会话, 实际使用的链路名)。
pub fn connect_bootstrapped(ip: Ipv4Addr, id: &SourceIdentity, mode: AuthMode) -> Result<(ControlSession, &'static str)> {
    let safety = |ip| -> Result<(ControlSession, &'static str)> {
        let mut ctl = ControlSession::connect(ip)?;
        ctl.bootstrap_safety(id)?;
        Ok((ctl, "safety"))
    };
    match mode {
        AuthMode::Safety => safety(ip),
        AuthMode::Legacy | AuthMode::Auto => {
            let mut ctl = ControlSession::connect(ip)?;
            match ctl.bootstrap_legacy(id) {
                Ok(()) => Ok((ctl, "legacy")),
                Err(e) if mode == AuthMode::Auto => {
                    drop(ctl);
                    log::warn!("[{ip}] legacy bootstrap failed ({e}); falling back to Safety");
                    safety(ip).map_err(|e2| Error::Protocol(format!("legacy failed: {e}; safety fallback failed: {e2}")))
                }
                Err(e) => Err(e),
            }
        }
    }
}

#[derive(Clone, Debug, Default)]
pub struct ReceiverStats {
    pub auth: String,
    /// GetDeviceInfo 里的 channel 字段（center/left/right）。
    pub device_channel: Option<String>,
    pub ip: String,
    pub phase: String,
    pub volume_original: Option<u8>,
    pub volume_premute_ack: Option<u8>,
    pub volume_now: Option<u8>,
    pub mirror_mode: Option<u32>,
    pub state: Option<u32>,
    pub rtsp_caps: String,
    pub setup_transport: String,
    pub time_offset_us: Option<i64>,
    pub timer_requests: u64,
    pub video_latency_msgs: u64,
    pub rtsp_runtime_requests: u64,
    pub heartbeat_acks: u64,
    pub notifies: Vec<String>,
    pub aus_written: u64,
    pub bytes_written: u64,
    pub runtime_time_offsets_sent: u64,
    pub runtime_time_offsets_acked: u64,
    pub pauses_acked: u64,
    pub resumes_acked: u64,
    pub volume_after_play_ack: Option<u8>,
    pub reconnects: u64,
    pub joined_late: bool,
    pub aus_blackholed: u64,
    /// GetChannel(0x18) 的 ACK 载荷（十六进制）；None = 未查询或未应答。
    pub channel_ack: Option<String>,
    pub error: Option<String>,
}

fn json_str(s: &str) -> String {
    let mut o = String::from("\"");
    for ch in s.chars() {
        match ch {
            '"' => o += "\\\"",
            '\\' => o += "\\\\",
            '\n' => o += "\\n",
            '\r' => o += "\\r",
            c if (c as u32) < 0x20 => o += &format!("\\u{:04x}", c as u32),
            c => o.push(c),
        }
    }
    o + "\""
}
fn opt<T: std::fmt::Display>(v: &Option<T>) -> String {
    v.as_ref().map(|x| x.to_string()).unwrap_or_else(|| "null".into())
}

impl ReceiverStats {
    pub fn to_json(&self) -> String {
        let n: Vec<String> = self.notifies.iter().rev().take(20).rev().map(|s| json_str(s)).collect();
        format!(
            "{{\"ip\":{},\"auth\":{:?},\"device_channel\":{},\"phase\":{},\"volume_original\":{},\"volume_premute_ack\":{},\"volume_now\":{},\"mirror_mode\":{},\"state\":{},\"setup_transport\":{},\"time_offset_us\":{},\"timer_requests\":{},\"video_latency_msgs\":{},\"rtsp_runtime_requests\":{},\"heartbeat_acks\":{},\"aus_written\":{},\"bytes_written\":{},\"runtime_time_offsets_sent\":{},\"runtime_time_offsets_acked\":{},\"pauses_acked\":{},\"resumes_acked\":{},\"volume_after_play_ack\":{},\"reconnects\":{},\"joined_late\":{},\"aus_blackholed\":{},\"channel_ack\":{},\"notifies\":[{}],\"error\":{}}}",
            json_str(&self.ip), self.auth, self.device_channel.as_ref().map(|c| json_str(c)).unwrap_or("null".into()), json_str(&self.phase), opt(&self.volume_original), opt(&self.volume_premute_ack), opt(&self.volume_now),
            opt(&self.mirror_mode), opt(&self.state), json_str(&self.setup_transport), opt(&self.time_offset_us), self.timer_requests,
            self.video_latency_msgs, self.rtsp_runtime_requests, self.heartbeat_acks, self.aus_written, self.bytes_written,
            self.runtime_time_offsets_sent, self.runtime_time_offsets_acked, self.pauses_acked, self.resumes_acked,
            opt(&self.volume_after_play_ack), self.reconnects, self.joined_late, self.aus_blackholed,
            self.channel_ack.as_deref().map(json_str).unwrap_or_else(|| "null".into()),
            n.join(","), self.error.as_deref().map(json_str).unwrap_or_else(|| "null".into())
        )
    }
}

struct SharedAu {
    ts: OutputTimestamp,
    aac: Vec<u8>,
}

struct ReceiverHandle {
    ip: Ipv4Addr,
    stats: Arc<Mutex<ReceiverStats>>,
    runtime: RuntimeWriter,
    pending_ack: Arc<Mutex<Option<(u16, SyncSender<Frame>)>>>,
    media_tx: SyncSender<Arc<SharedAu>>,
    sockets: Vec<TcpStream>,
    timer_stop: Arc<AtomicBool>,
    alive: Arc<AtomicBool>,
    /// RTSP 反向连接的写端（握手结束后与运行期循环共用）与下一个源端发起请求的 CSeq（握手用到 5）。
    rtsp_writer: Arc<Mutex<TcpStream>>,
    rtsp: Arc<SourceRtsp>,
    rtsp_cseq: Arc<AtomicU32>,
    /// 最近一次从 8899 收到任何帧的时刻（now_us）。
    last_rx_us: Arc<AtomicI64>,
    /// 调试：置位后读循环不再刷新 last_rx_us，等同“控制通道收不到任何帧”，用于验证 15 s 心跳超时判定。
    rx_stall: Arc<AtomicBool>,
    /// 调试“网络黑洞”截止时刻（now_us）：控制/音频/RTSP/计时器全部不收不发，TCP 不关。
    blackhole_until: Arc<AtomicI64>,
    timer_mute: Arc<AtomicI64>,
    ctl_mute: Arc<AtomicI64>,
}

impl ReceiverHandle {
    fn set_volume(&self, v: u8) -> Result<u8> {
        let (tx, rx) = sync_channel(1);
        {
            let mut p = self.pending_ack.lock().unwrap();
            *p = Some((0xffff, tx));
        }
        let seq = self.runtime.write(c::SET_VOLUME, encode_volume(v).to_vec())?;
        if let Some((s, _)) = self.pending_ack.lock().unwrap().as_mut() { *s = seq; }
        let f = rx.recv_timeout(Duration::from_secs(3)).map_err(|_| Error::Timeout(format!("SetVolume ack from {}", self.ip)))?;
        let got = crate::frame::decode_volume_ack(&f.payload)
            .ok_or_else(|| Error::Protocol(format!("bad SetVolume ack {:02x?}", f.payload)))?;
        self.stats.lock().unwrap().volume_now = Some(got);
        Ok(got)
    }

    /// 运行期 TIME_OFFSET（RTSP，CSeq 从 6 递增）。官方 `MultiMirrorDisplay::resume` 先对每个成员 setTimeOffset，
    /// 再 resume；`WifiDisplaySource::setTimeOffset` 异步投递并由 `sendTimeOffset` 发 RTSP 请求，不等应答。
    fn send_time_offset(&self, offset: i64) -> Result<u32> {
        let cseq = self.rtsp_cseq.fetch_add(1, Ordering::SeqCst);
        let msg = self.rtsp.time_offset(cseq, offset);
        self.rtsp_writer.lock().unwrap().write_all(&msg)?;
        let mut s = self.stats.lock().unwrap();
        s.runtime_time_offsets_sent += 1;
        s.time_offset_us = Some(offset);
        Ok(cseq)
    }

    fn is_healthy(&self, timeout: Duration) -> bool {
        self.alive.load(Ordering::Relaxed)
            && self.stats.lock().unwrap().error.is_none()
            && now_us() - self.last_rx_us.load(Ordering::Relaxed) < timeout.as_micros() as i64
    }

    fn shutdown(&self, send_close: bool) {
        self.alive.store(false, Ordering::Relaxed);
        if send_close { let _ = self.runtime.close(); std::thread::sleep(Duration::from_millis(50)); }
        for s in &self.sockets { let _ = s.shutdown(Shutdown::Both); }
        self.timer_stop.store(true, Ordering::Relaxed);
    }
}

/// 组共享 TIME_OFFSET：初始成员在 PLAY 处汇合；最后一个到达者预读起播 AU 并定值。
struct OffsetBarrier {
    expected: Mutex<usize>,
    arrived: Mutex<usize>,
    value: Mutex<Option<std::result::Result<i64, String>>>,
    cv: Condvar,
}

impl OffsetBarrier {
    fn new(n: usize) -> Self {
        Self { expected: Mutex::new(n), arrived: Mutex::new(0), value: Mutex::new(None), cv: Condvar::new() }
    }
    fn try_fire(&self, media: &Mutex<MediaState>, delay: i64) {
        let ready = *self.arrived.lock().unwrap() >= *self.expected.lock().unwrap();
        if !ready || self.value.lock().unwrap().is_some() { return }
        let v = {
            let mut m = media.lock().unwrap();
            let mut ok = true;
            while m.runway.len() < STARTUP_RUNWAY_AUS {
                match m.source.next_au() { Some(au) => m.runway.push_back(au), None => { ok = false; break } }
            }
            if ok { Ok(now_us() + delay) } else { Err("source ended during prewarm".to_string()) }
        };
        *self.value.lock().unwrap() = Some(v);
        self.cv.notify_all();
    }
    fn arrive(&self, media: &Mutex<MediaState>, delay: i64) -> Result<i64> {
        *self.arrived.lock().unwrap() += 1;
        self.try_fire(media, delay);
        let mut g = self.value.lock().unwrap();
        let deadline = Instant::now() + Duration::from_secs(30);
        while g.is_none() {
            let left = deadline.saturating_duration_since(Instant::now());
            if left.is_zero() { return Err(Error::Timeout("TIME_OFFSET barrier".into())) }
            g = self.cv.wait_timeout(g, left).unwrap().0;
        }
        g.clone().unwrap().map_err(Error::Protocol)
    }
    fn abandon(&self, media: &Mutex<MediaState>, delay: i64) {
        let mut e = self.expected.lock().unwrap();
        *e = e.saturating_sub(1);
        drop(e);
        self.try_fire(media, delay);
    }
    fn current(&self) -> Option<i64> {
        self.value.lock().unwrap().as_ref().and_then(|r| r.as_ref().ok().copied())
    }
}

struct MediaState {
    source: Box<dyn AuSource>,
    runway: VecDeque<Vec<u8>>,
}

/// 媒体时钟：共享时间线 + 已发送 AU 数 + 发送节奏原点。暂停/恢复时由控制线程重新锚定。
struct MediaClock {
    timeline: SharedTimeline,
    sent: u64,
    started: Instant,
}

/// 会话内部共享状态（媒体线程、监督线程、JNI 调用方共用）。
struct Inner {
    cfg: CastConfig,
    receivers: Mutex<Vec<ReceiverHandle>>,
    stop: AtomicBool,
    paused: AtomicBool,
    /// 当前组 TIME_OFFSET（初值来自屏障；恢复播放后更新）。0 = 尚未确定。
    offset: AtomicI64,
    media: Arc<Mutex<MediaState>>,
    clock: Mutex<MediaClock>,
    aus_sent: AtomicU64,
    timeline_corrections: AtomicU64,
    /// 用户主动移除的音箱不重连。
    removed: Mutex<Vec<Ipv4Addr>>,
    control_lock: Mutex<()>,
    /// 断线待重连成员的最后统计（重连计数延续）。
    pending: Mutex<Vec<(Ipv4Addr, ReceiverStats)>>,
}

pub struct CastSession {
    inner: Arc<Inner>,
    media_thread: Mutex<Option<std::thread::JoinHandle<()>>>,
    supervisor: Mutex<Option<std::thread::JoinHandle<()>>>,
    started_at: Instant,
}

fn accept_timeout(l: &TcpListener, t: Duration, what: &str) -> Result<TcpStream> {
    l.set_nonblocking(true)?;
    let deadline = Instant::now() + t;
    loop {
        match l.accept() {
            Ok((s, _)) => { s.set_nonblocking(false)?; s.set_nodelay(true)?; return Ok(s) }
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                if Instant::now() > deadline { return Err(Error::Timeout(format!("reverse {what} connection"))) }
                std::thread::sleep(Duration::from_millis(5));
            }
            Err(e) => return Err(e.into()),
        }
    }
}

/// 只做引导 + 设音量 + CLOSE，不建立播放会话。返回 (原音量, ACK 音量)。
pub fn premute(ip: Ipv4Addr, id: &SourceIdentity, v: u8) -> Result<(Option<u8>, Option<u8>)> {
    premute_with(ip, id, v, AuthMode::Legacy)
}

pub fn premute_with(ip: Ipv4Addr, id: &SourceIdentity, v: u8, mode: AuthMode) -> Result<(Option<u8>, Option<u8>)> {
    let (ctl, _) = connect_bootstrapped(ip, id, mode)?;
    let orig = ctl.status.volume;
    let ack = ctl.set_volume_then_close(v)?;
    Ok((orig, ack))
}

/// 只读：查询音箱当前声道（0=中置/立体声，1=左，2=右）。返回 ACK 原始载荷。
/// 独立 8899 连接上只做 Safety 握手（认证互验），完成后不发任何业务帧，直接关闭。
pub fn safety_probe(ip: Ipv4Addr, version: &str) -> Result<crate::control::SafetyReport> {
    safety_probe_ex(ip, version, &[])
}

/// 同上，互验后再发 `post_auth` 里的只读空载荷命令（加密）并观察 6 s。
pub fn safety_probe_ex(ip: Ipv4Addr, version: &str, post_auth: &[u32]) -> Result<crate::control::SafetyReport> {
    let mut ctl = ControlSession::connect(ip)?;
    ctl.safety_handshake(version, post_auth)
}

pub fn get_channel(ip: Ipv4Addr, id: &SourceIdentity) -> Result<Option<Vec<u8>>> {
    let mut ctl = ControlSession::connect(ip)?;
    ctl.bootstrap_legacy(id)?;
    Ok(ctl.command_then_close(c::GET_CHANNEL, vec![], c::GET_CHANNEL_ACK)?.map(|f| f.payload))
}

/// **会改音箱设置**（立体声对的左右声道）。官方只在用户在立体声组里指定声道时调用；本项目未在真机上执行过。
pub fn set_channel(ip: Ipv4Addr, id: &SourceIdentity, ch: u8) -> Result<Option<Vec<u8>>> {
    if ch > c::CHANNEL_FRONT_RIGHT { return Err(Error::Protocol(format!("bad channel {ch}"))) }
    let mut ctl = ControlSession::connect(ip)?;
    ctl.bootstrap_legacy(id)?;
    Ok(ctl.command_then_close(c::SET_CHANNEL, vec![ch], c::SET_CHANNEL_ACK)?.map(|f| f.payload))
}

/// 只做引导读取状态然后 CLOSE。
pub fn probe(ip: Ipv4Addr, id: &SourceIdentity) -> Result<ReceiverStats> {
    let mut ctl = ControlSession::connect(ip)?;
    ctl.bootstrap_legacy(id)?;
    let st = ReceiverStats {
        ip: ip.to_string(), phase: "probed".into(), volume_original: ctl.status.volume, volume_now: ctl.status.volume,
        mirror_mode: ctl.status.mirror_mode, state: ctl.status.state, ..Default::default()
    };
    let _ = ctl.runtime_writer().close();
    Ok(st)
}

fn connect_receiver(
    ip: Ipv4Addr,
    cfg: &CastConfig,
    stats: Arc<Mutex<ReceiverStats>>,
    offset_provider: Box<dyn FnOnce() -> Result<i64> + Send>,
) -> Result<ReceiverHandle> {
    let phase = |p: &str| { log::info!("[{ip}] {p}"); stats.lock().unwrap().phase = p.to_string(); };
    if let Some(v) = cfg.premute {
        phase("premute: bootstrap + SetVolume");
        let (orig, ack) = premute_with(ip, &cfg.identity, v, cfg.auth)?;
        let mut s = stats.lock().unwrap();
        if s.volume_original.is_none() { s.volume_original = orig; }
        s.volume_premute_ack = ack;
        drop(s);
        log::info!("[{ip}] premute: original volume {orig:?}, ack {ack:?}");
        if ack != Some(v) {
            return Err(Error::Protocol(format!("premute not acknowledged (ack {ack:?}); refusing to stream")));
        }
        std::thread::sleep(Duration::from_millis(300));
    }
    phase("8899 connect + bootstrap");
    let (mut ctl, route) = connect_bootstrapped(ip, &cfg.identity, cfg.auth)?;
    {
        let mut s = stats.lock().unwrap();
        s.auth = route.to_string();
        s.device_channel = ctl.status.device_info.as_deref().and_then(crate::frame::device_info_channel);
    }
    {
        let mut s = stats.lock().unwrap();
        if s.volume_original.is_none() { s.volume_original = ctl.status.volume; }
        s.volume_now = ctl.status.volume;
        s.mirror_mode = ctl.status.mirror_mode;
        s.state = ctl.status.state;
    }
    if let (Some(v), Some(now)) = (cfg.premute, ctl.status.volume) {
        if now != v { return Err(Error::Protocol(format!("receiver volume is {now}, expected premuted {v}; refusing to stream"))) }
    }
    phase("preparePlayback + SetPlaySource");
    ctl.prepare_playback(&cfg.identity)?;
    let local = *ctl.local_addr()?.ip();

    let timer = TimerServer::bind(local)?;
    let timer_port = timer.port();
    let timer_stop = timer.stop_handle();
    let timer_count = timer.requests.clone();
    let timer_mute = timer.mute_until.clone();
    let blackhole_until = Arc::new(AtomicI64::new(0));
    std::thread::Builder::new().name(format!("timer-{ip}")).spawn(move || timer.run(vec![ip]))?;

    let listener = TcpListener::bind((local, 0))?;
    let port = listener.local_addr()?.port();
    phase(&format!("Open wfd://{local}:{port}, timer udp {timer_port}"));
    ctl.send_open(local, port)?;

    let rtsp_sock = accept_timeout(&listener, Duration::from_secs(10), "RTSP")?;
    phase("RTSP connected back; handshake");
    let rtsp = Arc::new(SourceRtsp::new(local, timer_port));
    let rtsp_writer = Arc::new(Mutex::new(rtsp_sock.try_clone()?));
    let rtsp_cseq = Arc::new(AtomicU32::new(6));
    let media_writer = ctl.runtime_writer();
    let source_name = cfg.identity.source_name.clone();
    let (ready_tx, ready_rx) = std::sync::mpsc::channel::<Result<i64>>();
    let rtsp_stream = rtsp_sock.try_clone()?;
    rtsp_stream.set_read_timeout(Some(Duration::from_secs(40)))?;
    let st2 = stats.clone();
    let alive = Arc::new(AtomicBool::new(true));
    let alive2 = alive.clone();
    let bh2 = blackhole_until.clone();
    let (rtsp2, rw2) = (rtsp.clone(), rtsp_writer.clone());
    std::thread::Builder::new().name(format!("rtsp-{ip}")).spawn(move || {
        let mut br = BufReader::new(rtsp_stream);
        let r = rtsp2.handshake(&mut br, || {
            let json = format!(
                r#"{{"mArtist":"Android","mAlbum":"","mTitle":"System Audio","mDuration":2147483647,"id":"","mCoverUrl":"","status":0,"volume":0,"mArt":"","mSourceName":"{}","mDeviceState":2}}"#,
                source_name.replace('/', "\\/")
            );
            let _ = media_writer.write_with_seq(Frame::new(c::SET_MEDIA_INFO, 15, json.into_bytes()));
        }, offset_provider);
        let ok = r.is_ok();
        let _ = ready_tx.send(r);
        if !ok { return }
        let _ = br.get_ref().set_read_timeout(None);
        while alive2.load(Ordering::Relaxed) {
            let m = match read_message(&mut br) { Ok(m) => m, Err(e) => { log::info!("[{ip}] RTSP closed: {e}"); break } };
            if m.is_response() {
                // 源端发起的运行期 TIME_OFFSET 的应答（CSeq ≥ 6）
                if m.cseq().unwrap_or(0) >= 6 {
                    let okr = m.start_line.contains(" 200 ");
                    log::info!("[{ip}] RTSP response CSeq {} {}", m.cseq().unwrap_or(0), m.start_line);
                    let mut s = st2.lock().unwrap();
                    if okr { s.runtime_time_offsets_acked += 1 }
                    s.notifies.push(format!("{}ms rtsp-resp cseq={} ok={okr}", now_us() / 1000, m.cseq().unwrap_or(0)));
                }
                continue;
            }
            let method = m.method().to_string();
            if method == "VIDEO_LATENCY" {
                st2.lock().unwrap().video_latency_msgs += 1;
                continue;
            }
            st2.lock().unwrap().rtsp_runtime_requests += 1;
            log::info!("[{ip}] RTSP runtime {method}");
            let cseq = m.cseq().unwrap_or(0);
            let resp = if method == "OPTIONS" { rtsp2.options_response(cseq, true) } else { rtsp2.generic_ok(cseq) };
            if now_us() < bh2.load(Ordering::Relaxed) { continue }
            if rw2.lock().unwrap().write_all(&resp).is_err() { break }
        }
    })?;

    let aux = accept_timeout(&listener, Duration::from_secs(10), "auxiliary")?;
    let audio = accept_timeout(&listener, Duration::from_secs(10), "audio")?;
    drop(listener);
    phase("3 reverse connections up; waiting PLAY/TIME_OFFSET");
    let offset = ready_rx.recv_timeout(Duration::from_secs(45)).map_err(|_| Error::Timeout("RTSP PLAY".into()))??;
    stats.lock().unwrap().time_offset_us = Some(offset);
    phase(&format!("PLAY ok, TIME_OFFSET={offset}"));

    // 运行期控制
    let runtime = ctl.runtime_writer();
    let pending_ack: Arc<Mutex<Option<(u16, SyncSender<Frame>)>>> = Arc::new(Mutex::new(None));
    let last_rx_us = Arc::new(AtomicI64::new(now_us()));
    let rx_stall = Arc::new(AtomicBool::new(false));
    let (pa, st3, rw, al, lr, stall) = (pending_ack.clone(), stats.clone(), runtime.clone(), alive.clone(), last_rx_us.clone(), rx_stall.clone());
    let bh3 = blackhole_until.clone();
    let ctl_mute = runtime.mute_handle();
    std::thread::Builder::new().name(format!("ctl-{ip}")).spawn(move || {
        let r = ctl.runtime_read_loop(rw, |f, status| {
            if !stall.load(Ordering::Relaxed) && now_us() >= bh3.load(Ordering::Relaxed) { lr.store(now_us(), Ordering::Relaxed); }
            let mut s = st3.lock().unwrap();
            s.state = status.state.or(s.state);
            match f.command {
                c::HEARTBEAT_ACK => s.heartbeat_acks += 1,
                c::PAUSE_ACK => { s.pauses_acked += 1; s.notifies.push(format!("{}ms pause-ack", now_us() / 1000)); }
                c::RESUME_ACK => { s.resumes_acked += 1; s.notifies.push(format!("{}ms resume-ack", now_us() / 1000)); }
                c::NOTIFY => {
                    let txt = decode_notify(&f.payload).map(|(l, v)| format!("{l}={}", opt(&v))).unwrap_or_else(|| format!("raw:{:02x?}", f.payload));
                    log::info!("[{ip}] notify {txt}");
                    s.notifies.push(format!("{}ms {txt}", now_us() / 1000));
                }
                c::SET_VOLUME_ACK => {
                    if let Some((seq, tx)) = pa.lock().unwrap().take() {
                        if seq == f.sequence || seq == 0xffff { let _ = tx.try_send(f.clone()); }
                    }
                }
                c::GET_CHANNEL_ACK | c::SET_CHANNEL_ACK => {
                    let hex: String = f.payload.iter().map(|b| format!("{b:02x}")).collect();
                    log::info!("[{ip}] channel ack {:#06x}/{} payload {hex}", f.command, f.sequence);
                    s.channel_ack = Some(hex.clone());
                    s.notifies.push(format!("{}ms channel-ack {:#06x} {hex}", now_us() / 1000, f.command));
                }
                c::HEARTBEAT => {}
                other => log::info!("[{ip}] control {other:#06x}/{} len {}", f.sequence, f.payload.len()),
            }
        });
        if al.load(Ordering::Relaxed) {
            log::warn!("[{ip}] control loop ended: {r:?}");
            st3.lock().unwrap().error.get_or_insert_with(|| format!("control loop ended: {r:?}"));
        }
    })?;
    let hb = runtime.clone();
    let al = alive.clone();
    std::thread::Builder::new().name(format!("hb-{ip}")).spawn(move || {
        while al.load(Ordering::Relaxed) {
            for _ in 0..50 { if !al.load(Ordering::Relaxed) { return } std::thread::sleep(Duration::from_millis(100)); }
            if hb.heartbeat().is_err() { break }
        }
    })?;

    // 媒体写线程
    let (media_tx, media_rx) = sync_channel::<Arc<SharedAu>>(256);
    let mut audio_w = audio.try_clone()?;
    let st4 = stats.clone();
    let al = alive.clone();
    let bh4 = blackhole_until.clone();
    std::thread::Builder::new().name(format!("media-{ip}")).spawn(move || {
        let mut pk = Packetizer::new(PacketizerState::default());
        while let Ok(au) = media_rx.recv() {
            let frames = pk.packetize(&au.aac, &au.ts);
            if now_us() < bh4.load(Ordering::Relaxed) { st4.lock().unwrap().aus_blackholed += 1; continue }
            let mut n = 0;
            for f in &frames {
                if let Err(e) = audio_w.write_all(f) {
                    if al.load(Ordering::Relaxed) { st4.lock().unwrap().error.get_or_insert_with(|| format!("audio write: {e}")); }
                    return;
                }
                n += f.len();
            }
            let mut s = st4.lock().unwrap();
            s.aus_written += 1;
            s.bytes_written += n as u64;
            s.timer_requests = timer_count.load(Ordering::Relaxed);
        }
    })?;

    let h = ReceiverHandle {
        ip, stats: stats.clone(), runtime, pending_ack, media_tx,
        sockets: vec![rtsp_sock, aux, audio], timer_stop, alive,
        rtsp_writer, rtsp, rtsp_cseq, last_rx_us, rx_stall, blackhole_until, timer_mute, ctl_mute,
    };
    if let Some(v) = cfg.volume_after_play {
        match h.set_volume(v) {
            Ok(got) => { stats.lock().unwrap().volume_after_play_ack = Some(got); log::info!("[{ip}] volume_after_play {v} -> ack {got}"); }
            Err(e) => {
                // 静音保护：设不上就不推这台
                h.shutdown(true);
                return Err(Error::Protocol(format!("volume_after_play({v}) failed: {e}")));
            }
        }
    }
    phase("streaming");
    Ok(h)
}

impl Inner {
    /// 中途加入/重连：复用当前组 TIME_OFFSET，从实时 AU 开始（官方 "it's already playing" → mMirrotServer.addMirror）。
    fn add_receiver(&self, ip: Ipv4Addr, prior: Option<ReceiverStats>) -> Result<ReceiverStats> {
        if self.paused.load(Ordering::SeqCst) { return Err(Error::Protocol("group is paused; resume first".into())) }
        if self.receivers.lock().unwrap().iter().any(|r| r.ip == ip && r.alive.load(Ordering::Relaxed)) {
            return Err(Error::Protocol("already in group".into()));
        }
        let off = self.offset.load(Ordering::SeqCst);
        if off == 0 { return Err(Error::Protocol("no TIME_OFFSET yet".into())) }
        let mut init = ReceiverStats { ip: ip.to_string(), joined_late: true, ..Default::default() };
        if let Some(p) = prior { init.reconnects = p.reconnects + 1; init.volume_original = p.volume_original; }
        let stats = Arc::new(Mutex::new(init));
        self.removed.lock().unwrap().retain(|x| *x != ip);
        // offset 可能在连接期间因恢复播放而更新：provider 在 PLAY 时刻读取最新值
        let off_now = self.offset.load(Ordering::SeqCst);
        let h = connect_receiver(ip, &self.cfg, stats.clone(), Box::new(move || Ok(off_now)))?;
        let latest = self.offset.load(Ordering::SeqCst);
        if latest != off_now { let _ = h.send_time_offset(latest); }
        let mut rs = self.receivers.lock().unwrap();
        rs.retain(|r| r.ip != ip);
        rs.push(h);
        let s = stats.lock().unwrap().clone();
        Ok(s)
    }
}

impl CastSession {
    /// 并行连接所有音箱，成功 ≥1 台即开始推流。
    pub fn start(ips: &[Ipv4Addr], source: Box<dyn AuSource>, cfg: CastConfig) -> Result<(Self, Vec<ReceiverStats>)> {
        if ips.is_empty() || ips.len() > MAX_RECEIVERS { return Err(Error::Protocol("bad receiver count".into())) }
        let media = Arc::new(Mutex::new(MediaState { source, runway: VecDeque::new() }));
        let barrier = Arc::new(OffsetBarrier::new(ips.len()));
        let mut joins = vec![];
        for &ip in ips {
            let (cfg2, media2, barrier2) = (cfg.clone(), media.clone(), barrier.clone());
            let stats = Arc::new(Mutex::new(ReceiverStats { ip: ip.to_string(), ..Default::default() }));
            joins.push((stats.clone(), std::thread::spawn(move || {
                let arrived = Arc::new(AtomicBool::new(false));
                let arrived2 = arrived.clone();
                let (m3, b3, d) = (media2.clone(), barrier2.clone(), cfg2.playback_delay_us);
                let provider: Box<dyn FnOnce() -> Result<i64> + Send> = Box::new(move || {
                    arrived2.store(true, Ordering::SeqCst);
                    b3.arrive(&m3, d)
                });
                let r = connect_receiver(ip, &cfg2, stats.clone(), provider);
                if let Err(e) = &r {
                    log::error!("[{ip}] failed: {e}");
                    stats.lock().unwrap().error = Some(e.to_string());
                    if !arrived.load(Ordering::SeqCst) { barrier2.abandon(&media2, cfg2.playback_delay_us) }
                }
                r
            })));
        }
        let mut receivers = vec![];
        let mut all_stats = vec![];
        for (stats, j) in joins {
            if let Ok(Ok(h)) = j.join() { receivers.push(h) }
            all_stats.push(stats.lock().unwrap().clone());
        }
        if receivers.is_empty() {
            return Err(Error::Protocol(format!("no receiver connected: {}", all_stats.iter().map(|s| format!("{}: {}", s.ip, s.error.clone().unwrap_or_default())).collect::<Vec<_>>().join("; "))));
        }
        let inner = Arc::new(Inner {
            cfg,
            receivers: Mutex::new(receivers),
            stop: AtomicBool::new(false),
            paused: AtomicBool::new(false),
            offset: AtomicI64::new(barrier.current().unwrap_or(0)),
            media,
            clock: Mutex::new(MediaClock { timeline: SharedTimeline::default(), sent: 0, started: Instant::now() }),
            aus_sent: AtomicU64::new(0),
            timeline_corrections: AtomicU64::new(0),
            removed: Mutex::new(vec![]),
            control_lock: Mutex::new(()),
            pending: Mutex::new(vec![]),
        });
        let i2 = inner.clone();
        let media_thread = std::thread::Builder::new().name("media-clock".into()).spawn(move || {
            i2.clock.lock().unwrap().started = Instant::now();
            while !i2.stop.load(Ordering::Relaxed) {
                if i2.paused.load(Ordering::SeqCst) { std::thread::sleep(Duration::from_millis(5)); continue }
                let au = {
                    let mut m = i2.media.lock().unwrap();
                    match m.runway.pop_front() { Some(a) => Some(a), None => m.source.next_au() }
                };
                let Some(aac) = au else { log::info!("source ended"); break };
                // 取 AU 期间可能被暂停（ChannelSource 阻塞）：暂停中取到的 AU 属于暂停前，丢弃
                if i2.paused.load(Ordering::SeqCst) { continue }
                let due = {
                    let mut ck = i2.clock.lock().unwrap();
                    let idx = ck.sent;
                    let ts = ck.timeline.stamp(idx, now_us());
                    i2.timeline_corrections.store(ck.timeline.corrections, Ordering::Relaxed);
                    let shared = Arc::new(SharedAu { ts, aac });
                    for r in i2.receivers.lock().unwrap().iter() {
                        if !r.alive.load(Ordering::Relaxed) { continue }
                        match r.media_tx.try_send(shared.clone()) {
                            Ok(()) => {}
                            Err(TrySendError::Full(_)) => {
                                log::warn!("[{}] media queue full, dropping receiver", r.ip);
                                r.stats.lock().unwrap().error.get_or_insert_with(|| "media queue full".into());
                                r.alive.store(false, Ordering::Relaxed);
                            }
                            Err(TrySendError::Disconnected(_)) => r.alive.store(false, Ordering::Relaxed),
                        }
                    }
                    ck.sent += 1;
                    i2.aus_sent.store(ck.sent, Ordering::Relaxed);
                    ck.started + Duration::from_micros((due_after_ms(ck.sent) * 1000.0) as u64)
                };
                let now = Instant::now();
                if due > now { std::thread::sleep(due - now) }
            }
        })?;
        let i3 = inner.clone();
        let supervisor = std::thread::Builder::new().name("supervisor".into()).spawn(move || supervise(i3))?;
        Ok((Self {
            inner,
            media_thread: Mutex::new(Some(media_thread)),
            supervisor: Mutex::new(Some(supervisor)),
            started_at: Instant::now(),
        }, all_stats))
    }

    pub fn add_receiver(&self, ip: Ipv4Addr) -> Result<ReceiverStats> {
        self.inner.add_receiver(ip, None)
    }

    pub fn remove_receiver(&self, ip: Ipv4Addr) {
        self.inner.removed.lock().unwrap().push(ip);
        let mut rs = self.inner.receivers.lock().unwrap();
        if let Some(i) = rs.iter().position(|r| r.ip == ip) {
            let r = rs.remove(i);
            r.shutdown(true);
        }
    }

    /// 调试用：不发 CLOSE、直接断开某台的全部连接，模拟网络掉线（用于验证自动重连）。
    pub fn debug_drop_connection(&self, ip: Ipv4Addr) -> bool {
        let rs = self.inner.receivers.lock().unwrap();
        match rs.iter().find(|r| r.ip == ip) {
            Some(r) => { for s in &r.sockets { let _ = s.shutdown(Shutdown::Both); } true }
            None => false,
        }
    }

    /// 调试用：让某台的“最近收帧时间”停止刷新，模拟控制通道静默；监督线程应在 heartbeat_timeout（15 s）后判定掉线并重连。
    pub fn debug_stall_rx(&self, ip: Ipv4Addr) -> bool {
        let rs = self.inner.receivers.lock().unwrap();
        match rs.iter().find(|r| r.ip == ip) {
            Some(r) => { r.rx_stall.store(true, Ordering::Relaxed); true }
            None => false,
        }
    }

    /// 调试用“网络黑洞”：secs 秒内对该音箱的控制帧（含心跳/心跳 ACK）、音频、RTSP 应答、计时器应答全部静默丢弃，
    /// 收到的帧也不算“活着”；TCP 不发 FIN/RST。等同于音箱短暂不可达（丢包）。监督线程应按 15 s 超时判定并重连。
    pub fn debug_blackhole(&self, ip: Ipv4Addr, secs: u64) -> bool {
        let rs = self.inner.receivers.lock().unwrap();
        match rs.iter().find(|r| r.ip == ip) {
            Some(r) => {
                let until = now_us() + secs as i64 * 1_000_000;
                for a in [&r.blackhole_until, &r.timer_mute, &r.ctl_mute] { a.store(until, Ordering::Relaxed); }
                r.stats.lock().unwrap().notifies.push(format!("{}ms blackhole {secs}s", now_us() / 1000));
                true
            }
            None => false,
        }
    }

    pub fn set_volume(&self, ip: Option<Ipv4Addr>, v: u8) -> Vec<(Ipv4Addr, Result<u8>)> {
        let rs = self.inner.receivers.lock().unwrap();
        rs.iter().filter(|r| ip.map_or(true, |x| x == r.ip) && r.alive.load(Ordering::Relaxed))
            .map(|r| (r.ip, r.set_volume(v))).collect()
    }

    /// 暂停：先停发媒体，再对每台发 8899 Pause（官方 CaptureService.PauseMirror → pauseDevice）。
    pub fn pause(&self) -> Vec<(Ipv4Addr, Result<u16>)> {
        let _g = self.inner.control_lock.lock().unwrap();
        self.inner.paused.store(true, Ordering::SeqCst);
        let rs = self.inner.receivers.lock().unwrap();
        rs.iter().filter(|r| r.alive.load(Ordering::Relaxed)).map(|r| (r.ip, r.runtime.pause())).collect()
    }

    /// 恢复：与官方 `MultiMirrorDisplay::resume(op, sendPos)` 相同的算术——
    /// newOffset = serverNow − sendPos + playDelay，先对**全部成员**下发同一个运行期 TIME_OFFSET，
    /// 再发 Resume（8899 连发两次），最后重新锚定时间线并恢复发送。PTS 保持连续。
    pub fn resume(&self) -> Result<i64> {
        let _g = self.inner.control_lock.lock().unwrap();
        if !self.inner.paused.load(Ordering::SeqCst) { return Ok(self.inner.offset.load(Ordering::SeqCst)) }
        let new_offset = self.rebase_and_broadcast("resume");
        {
            let rs = self.inner.receivers.lock().unwrap();
            for r in rs.iter().filter(|r| r.alive.load(Ordering::Relaxed)) {
                if let Err(e) = r.runtime.resume() { log::warn!("[{}] resume failed: {e}", r.ip) }
            }
        }
        self.inner.paused.store(false, Ordering::SeqCst);
        Ok(new_offset)
    }

    /// 切歌/换音源（官方 MultiMirrorDisplay::switchSource(pts) 0x1ee598）：不暂停，丢掉旧音源已缓冲的数据，
    /// 时间线在下一个 AU 处重新锚定，并向所有成员重发 RTSP TIME_OFFSET。音箱侧无 Pause/Resume。
    pub fn switch_source(&self) -> Result<i64> {
        let _g = self.inner.control_lock.lock().unwrap();
        if self.inner.paused.load(Ordering::SeqCst) {
            // 暂停中切歌：只清缓冲，恢复时自然重锚
            let mut m = self.inner.media.lock().unwrap();
            m.runway.clear();
            m.source.flush();
            return Ok(self.inner.offset.load(Ordering::SeqCst));
        }
        Ok(self.rebase_and_broadcast("switch_source"))
    }

    /// 清缓冲 → newOffset = now − sendPos + playDelay → 时间线重锚、发送节奏重置 → 对所有存活成员发 RTSP TIME_OFFSET。
    fn rebase_and_broadcast(&self, why: &str) -> i64 {
        {
            let mut m = self.inner.media.lock().unwrap();
            m.runway.clear();
            m.source.flush();
        }
        let mut ck = self.inner.clock.lock().unwrap();
        let now = now_us();
        let send_pos = ck.timeline.next_pts_us(ck.sent);
        let new_offset = now - send_pos + self.inner.cfg.playback_delay_us;
        let idx = ck.sent;
        ck.timeline.reanchor(idx, now);
        // 发送节奏：下一个 AU 立即发，之后按 21.333 ms
        let due_next = Duration::from_micros((due_after_ms(ck.sent) * 1000.0) as u64);
        ck.started = Instant::now().checked_sub(due_next).unwrap_or_else(Instant::now);
        self.inner.offset.store(new_offset, Ordering::SeqCst);
        let rs = self.inner.receivers.lock().unwrap();
        for r in rs.iter().filter(|r| r.alive.load(Ordering::Relaxed)) {
            match r.send_time_offset(new_offset) {
                Ok(cseq) => log::info!("[{}] {why}: runtime TIME_OFFSET CSeq {cseq} = {new_offset} (sendPos {send_pos})", r.ip),
                Err(e) => log::warn!("[{}] {why}: runtime TIME_OFFSET failed: {e}", r.ip),
            }
        }
        drop(rs);
        drop(ck);
        new_offset
    }

    /// 只读：对运行中的每台发 GetChannel(0x18)，最多等 2 s 收 0x19。返回 (ip, ACK 载荷十六进制)。
    pub fn query_channel(&self) -> Vec<(Ipv4Addr, Option<String>)> {
        let targets: Vec<(Ipv4Addr, Arc<Mutex<ReceiverStats>>)> = {
            let rs = self.inner.receivers.lock().unwrap();
            rs.iter().filter(|r| r.alive.load(Ordering::Relaxed)).map(|r| {
                r.stats.lock().unwrap().channel_ack = None;
                let _ = r.runtime.write(c::GET_CHANNEL, vec![]);
                (r.ip, r.stats.clone())
            }).collect()
        };
        let deadline = Instant::now() + Duration::from_secs(2);
        while Instant::now() < deadline && targets.iter().any(|(_, s)| s.lock().unwrap().channel_ack.is_none()) {
            std::thread::sleep(Duration::from_millis(20));
        }
        targets.into_iter().map(|(ip, s)| (ip, s.lock().unwrap().channel_ack.clone())).collect()
    }

    pub fn is_paused(&self) -> bool { self.inner.paused.load(Ordering::SeqCst) }

    pub fn stats(&self) -> Vec<ReceiverStats> {
        self.inner.receivers.lock().unwrap().iter().map(|r| {
            let mut s = r.stats.lock().unwrap().clone();
            if !r.alive.load(Ordering::Relaxed) && s.error.is_none() { s.error = Some("not alive".into()) }
            s
        }).collect()
    }

    pub fn stats_json(&self) -> String {
        let rs: Vec<String> = self.stats().iter().map(|s| s.to_json()).collect();
        let off = self.inner.offset.load(Ordering::SeqCst);
        format!(
            "{{\"uptime_ms\":{},\"aus_sent\":{},\"timeline_corrections\":{},\"time_offset_us\":{},\"playback_delay_us\":{},\"paused\":{},\"receivers\":[{}]}}",
            self.started_at.elapsed().as_millis(), self.inner.aus_sent.load(Ordering::Relaxed),
            self.inner.timeline_corrections.load(Ordering::Relaxed), off, self.inner.cfg.playback_delay_us,
            self.is_paused(), rs.join(",")
        )
    }

    pub fn is_running(&self) -> bool {
        !self.inner.stop.load(Ordering::Relaxed) && self.media_thread.lock().unwrap().as_ref().map_or(false, |t| !t.is_finished())
    }

    pub fn stop(&self) {
        self.inner.stop.store(true, Ordering::Relaxed);
        if let Some(t) = self.media_thread.lock().unwrap().take() { let _ = t.join(); }
        if let Some(t) = self.supervisor.lock().unwrap().take() { let _ = t.join(); }
        for r in self.inner.receivers.lock().unwrap().iter() { r.shutdown(true); }
    }
}

/// 监督线程：每秒检查成员健康；15 s 没收到控制帧或连接出错 → 断开并按中途加入的方式重连（指数退避，最多 30 s）。
fn supervise(inner: Arc<Inner>) {
    let mut backoff: std::collections::HashMap<Ipv4Addr, (Instant, u32)> = Default::default();
    while !inner.stop.load(Ordering::Relaxed) {
        for _ in 0..10 { if inner.stop.load(Ordering::Relaxed) { return } std::thread::sleep(Duration::from_millis(100)); }
        let mut dead: Vec<(Ipv4Addr, ReceiverStats)> = vec![];
        {
            let mut rs = inner.receivers.lock().unwrap();
            let mut i = 0;
            while i < rs.len() {
                if !rs[i].is_healthy(inner.cfg.heartbeat_timeout) {
                    let r = rs.remove(i);
                    let st = r.stats.lock().unwrap().clone();
                    log::warn!("[{}] unhealthy (error={:?}); disconnecting", r.ip, st.error);
                    r.shutdown(false);
                    dead.push((r.ip, st));
                } else { i += 1 }
            }
        }
        for (ip, st) in dead { backoff.entry(ip).or_insert((Instant::now(), 0)); pending_insert(&inner, ip, st); }
        if !inner.cfg.auto_reconnect || inner.paused.load(Ordering::SeqCst) { continue }
        let due: Vec<Ipv4Addr> = backoff.iter().filter(|(_, (t, _))| *t <= Instant::now()).map(|(ip, _)| *ip).collect();
        for ip in due {
            if inner.removed.lock().unwrap().contains(&ip) { backoff.remove(&ip); continue }
            let prior = inner.pending.lock().unwrap().iter().find(|(i, _)| *i == ip).map(|(_, s)| s.clone());
            log::info!("[{ip}] reconnecting");
            match inner.add_receiver(ip, prior) {
                Ok(s) => { log::info!("[{ip}] reconnected: {}", s.to_json()); backoff.remove(&ip); inner.pending.lock().unwrap().retain(|(i, _)| *i != ip); }
                Err(e) => {
                    let (_, n) = backoff[&ip];
                    let wait = Duration::from_secs((2u64 << n.min(4)).min(30));
                    log::warn!("[{ip}] reconnect failed: {e}; retry in {wait:?}");
                    backoff.insert(ip, (Instant::now() + wait, n + 1));
                }
            }
        }
    }
}

fn pending_insert(inner: &Inner, ip: Ipv4Addr, st: ReceiverStats) {
    let mut p = inner.pending.lock().unwrap();
    p.retain(|(i, _)| *i != ip);
    p.push((ip, st));
}

impl Drop for CastSession {
    fn drop(&mut self) {
        if !self.inner.stop.load(Ordering::Relaxed) { self.stop() }
    }
}
