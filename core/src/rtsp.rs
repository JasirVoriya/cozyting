//! WFD RTSP（发送端为 RTSP **服务端**，音箱收到 Open 后反向连入）。
//!
//! 时序（已确认：MiPlayForWindows android `MiPlayRtsp.kt:47-122`；接收端视角 FusionPlay `media.rs:917-1230`）：
//! ```text
//! S→R  OPTIONS * (CSeq1, Require: org.wfa.wfd1.0, wfd_timer_server_port:<u32 IPv4>:<udp port>)
//! R→S  200(CSeq1)  并且 R→S OPTIONS *(CSeq1)  → S 回 200
//! S→R  GET_PARAMETER(CSeq2) wfd_audio_codecs / wfd_client_rtp_ports / ...
//! R→S  200 + 能力（旧音箱含 "wfd_audio_codecs: AAC 00000001 00"；新链路为 wfd_audio_codecs_v2 + wfd_content_SP_protection）
//! S→R  SET_PARAMETER(CSeq3) wfd_audio_codecs: AAC 00000001 00 / wfd_client_rtp_ports: RTP/AVP/TCP;interleaved mode=play / wfd_presentation_URL
//! S→R  SET_PARAMETER(CSeq4) wfd_trigger_method: SETUP
//! R→S  SETUP(CSeq2, Transport: RTP/AVP/TCP;interleaved=0-1)  → S 200 + Session
//! R→S  PLAY(CSeq3) → S 200(Range: npt=now-)；随后控制通道发 SetMediaInfo
//! S→R  TIME_OFFSET(CSeq5, TimeOffset:<µs，发送端时钟>)  ← 组同步关键
//! 运行期：R 定期 VIDEO_LATENCY 遥测（源端不回复，docs/miplay-real-legacy-source-fresh-session.md:196-198），
//!         OPTIONS/GET_PARAMETER 保活需回 200。
//! ```
//! 现代链路额外：OPTIONS 头 authMsg / authMsgAck(HMAC-SHA256(MirrorKeys.auth_key)) / authKeyType:2 / authAlgorithmVal:4
//! （FusionPlay media.rs:1053-1093）——发送端具体写法需抓包。

use crate::{proto, Result};
use std::collections::BTreeMap;
use std::io::{BufRead, Read, Write};
use std::net::Ipv4Addr;

#[derive(Debug, Clone)]
pub struct RtspMessage {
    pub start_line: String,
    pub headers: BTreeMap<String, String>, // key 小写
    pub body: Vec<u8>,
}

impl RtspMessage {
    pub fn cseq(&self) -> Option<u32> {
        self.headers.get("cseq").and_then(|v| v.trim().parse().ok())
    }
    pub fn is_response(&self) -> bool {
        self.start_line.starts_with("RTSP/")
    }
    pub fn method(&self) -> &str {
        self.start_line.split_whitespace().next().unwrap_or("")
    }
}

pub fn read_message(r: &mut impl BufRead) -> Result<RtspMessage> {
    let mut start_line = String::new();
    while start_line.trim().is_empty() {
        start_line.clear();
        if r.read_line(&mut start_line)? == 0 {
            return proto("RTSP closed");
        }
    }
    let mut headers = BTreeMap::new();
    loop {
        let mut line = String::new();
        r.read_line(&mut line)?;
        let line = line.trim_end_matches(['\r', '\n']);
        if line.is_empty() {
            break;
        }
        if let Some((k, v)) = line.split_once(':') {
            headers.insert(k.trim().to_ascii_lowercase(), v.trim().to_string());
        }
    }
    let len: usize = headers.get("content-length").and_then(|v| v.parse().ok()).unwrap_or(0);
    if len > 1 << 20 {
        return proto("RTSP body too large");
    }
    let mut body = vec![0; len];
    r.read_exact(&mut body)?;
    Ok(RtspMessage { start_line: start_line.trim_end().to_string(), headers, body })
}

/// 头部构造；`no_space` 中的头不在冒号后加空格（抓包形态：wfd_timer_server_port / TimeOffset）。
fn encode(start: &str, headers: &[(&str, String)], body: &[u8], no_space: &[&str]) -> Vec<u8> {
    let mut s = format!("{start}\r\n");
    for (k, v) in headers {
        if no_space.contains(k) { s += &format!("{k}:{v}\r\n") } else { s += &format!("{k}: {v}\r\n") }
    }
    s += "\r\n";
    let mut out = s.into_bytes();
    out.extend_from_slice(body);
    out
}

pub struct SourceRtsp {
    pub source_ip: Ipv4Addr,
    pub timer_port: u16,
    pub session_id: String,
}

impl SourceRtsp {
    pub fn new(source_ip: Ipv4Addr, timer_port: u16) -> Self {
        use rand::Rng;
        Self { source_ip, timer_port, session_id: rand::thread_rng().gen_range(100_000_000u32..1_000_000_000).to_string() }
    }

    fn date() -> String {
        // 形如 "Tue, 17 Nov 2026 05:20:00 +0000"（与 MiPlayForWindows rtspDate 同格式）
        let secs = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
        let days = secs.div_euclid(86_400);
        let rem = secs.rem_euclid(86_400);
        let (h, m, sec) = (rem / 3600, (rem % 3600) / 60, rem % 60);
        let wd = ["Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed"][days.rem_euclid(7) as usize];
        // civil_from_days (Howard Hinnant)
        let z = days + 719_468;
        let era = z.div_euclid(146_097);
        let doe = z - era * 146_097;
        let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        let mp = (5 * doy + 2) / 153;
        let d = doy - (153 * mp + 2) / 5 + 1;
        let mo = if mp < 10 { mp + 3 } else { mp - 9 };
        let y = yoe + era * 400 + if mo <= 2 { 1 } else { 0 };
        let mn = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"][(mo - 1) as usize];
        format!("{wd}, {d:02} {mn} {y} {h:02}:{m:02}:{sec:02} +0000")
    }

    pub fn m1_options(&self) -> Vec<u8> {
        let ip_u32 = u32::from(self.source_ip);
        encode(
            "OPTIONS * RTSP/1.0",
            &[("Date", Self::date()), ("Server", String::new()), ("CSeq", "1".into()), ("Require", "org.wfa.wfd1.0".into()),
              ("wfd_timer_server_port", format!("{ip_u32}:{}", self.timer_port))],
            &[],
            &["wfd_timer_server_port"],
        )
    }

    pub fn options_response(&self, cseq: u32, with_session: bool) -> Vec<u8> {
        let mut h = vec![("Date", Self::date()), ("Server", String::new()), ("CSeq", cseq.to_string())];
        if with_session { h.push(("Session", format!("{};timeout=60", self.session_id))) }
        h.push(("Public", "org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER".into()));
        encode("RTSP/1.0 200 OK", &h, &[], &[])
    }

    fn param(&self, method: &str, cseq: u32, body: &str) -> Vec<u8> {
        encode(
            &format!("{method} rtsp://localhost/wfd1.0 RTSP/1.0"),
            &[("Date", Self::date()), ("Server", String::new()), ("CSeq", cseq.to_string()),
              ("Content-Type", "text/parameters".into()), ("Content-Length", body.len().to_string())],
            body.as_bytes(),
            &[],
        )
    }

    pub fn m3_get_capabilities(&self) -> Vec<u8> {
        self.param("GET_PARAMETER", 2, "wfd_video_formats\r\nwfd_audio_codecs\r\nwfd_client_rtp_ports\r\nwfd_tcp_enable\r\nwfd_tcp_multi_session_enable\r\nwfd_image_enable\r\nwfd_dynamic_video_enable\r\nwfd_standby_resume_capability\r\nwfd_video_bitrate\r\nwfd_current_video_info\r\n")
    }

    pub fn m4_set_params(&self) -> Vec<u8> {
        self.param("SET_PARAMETER", 3, &format!(
            "wfd_audio_codecs: AAC 00000001 00\r\nwfd_client_rtp_ports: RTP/AVP/TCP;interleaved mode=play\r\nwfd_presentation_URL: rtsp://{}/wfd1.0/streamid=0 none\r\n",
            self.source_ip))
    }

    pub fn m5_trigger_setup(&self) -> Vec<u8> {
        self.param("SET_PARAMETER", 4, "wfd_trigger_method: SETUP\r\n")
    }

    pub fn setup_response(&self, transport: &str) -> Vec<u8> {
        let t = if transport.ends_with(';') { transport.to_string() } else { format!("{transport};") };
        encode("RTSP/1.0 200 OK", &[("Date", Self::date()), ("Server", String::new()), ("CSeq", "2".into()),
            ("Session", format!("{};timeout=60", self.session_id)), ("Transport", t)], &[], &[])
    }

    pub fn play_response(&self) -> Vec<u8> {
        encode("RTSP/1.0 200 OK", &[("Date", Self::date()), ("Server", String::new()), ("CSeq", "3".into()),
            ("Session", format!("{};timeout=60", self.session_id)), ("Range", "npt=now-".into())], &[], &[])
    }

    /// TIME_OFFSET：值为发送端时钟（clock::now_us 同源）上的“媒体 PTS=0 的播放时刻”。
    pub fn time_offset(&self, cseq: u32, offset_us: i64) -> Vec<u8> {
        encode("TIME_OFFSET rtsp://localhost/wfd1.0 RTSP/1.0", &[("Date", Self::date()), ("Server", String::new()),
            ("CSeq", cseq.to_string()), ("Content-Type", "text/parameters".into()),
            ("TimeOffset", offset_us.to_string()), ("Content-Length", "0".into())], &[], &["TimeOffset"])
    }

    pub fn generic_ok(&self, cseq: u32) -> Vec<u8> {
        encode("RTSP/1.0 200 OK", &[("Date", Self::date()), ("Server", String::new()), ("CSeq", cseq.to_string()),
            ("Session", format!("{};timeout=60", self.session_id)), ("Content-Length", "0".into())], &[], &[])
    }

    /// 跑完握手直到 PLAY；`time_offset` 回调在收到 PLAY 后被调用（组内共享屏障在这里阻塞）。
    pub fn handshake<S: Read + Write>(
        &self,
        stream: &mut std::io::BufReader<S>,
        mut on_play: impl FnMut(),
        time_offset: impl FnOnce() -> Result<i64>,
    ) -> Result<i64> {
        let send = |s: &mut std::io::BufReader<S>, b: Vec<u8>| -> Result<()> { s.get_mut().write_all(&b)?; Ok(()) };
        send(stream, self.m1_options())?;
        let (mut got_ok, mut got_req) = (false, false);
        while !(got_ok && got_req) {
            let m = read_message(stream)?;
            if m.is_response() && m.cseq() == Some(1) { got_ok = true }
            else if m.method() == "OPTIONS" { got_req = true; send(stream, self.options_response(m.cseq().unwrap_or(1), false))? }
            else { return proto(format!("unexpected in M1/M2: {}", m.start_line)) }
        }
        send(stream, self.m3_get_capabilities())?;
        let caps = read_message(stream)?;
        let caps_s = String::from_utf8_lossy(&caps.body).to_string();
        log::info!("RTSP caps from receiver: {}", caps_s.replace("\r\n", " | "));
        if !caps_s.contains("wfd_audio_codecs: AAC 00000001 00") {
            return proto(format!("receiver did not advertise AAC 00000001 00: {caps_s}"));
        }
        send(stream, self.m4_set_params())?;
        let r = read_message(stream)?;
        if !(r.start_line.contains(" 200 ") && r.cseq() == Some(3)) { return proto(format!("M4 failed: {}", r.start_line)) }
        send(stream, self.m5_trigger_setup())?;
        let r = read_message(stream)?;
        if !(r.start_line.contains(" 200 ") && r.cseq() == Some(4)) { return proto(format!("M5 failed: {}", r.start_line)) }
        let setup = read_message(stream)?;
        if setup.method() != "SETUP" { return proto(format!("expected SETUP, got {}", setup.start_line)) }
        let transport = setup.headers.get("transport").cloned().unwrap_or_default();
        send(stream, self.setup_response(&transport))?;
        let play = read_message(stream)?;
        if play.method() != "PLAY" { return proto(format!("expected PLAY, got {}", play.start_line)) }
        log::info!("RTSP SETUP transport={transport}; got PLAY");
        let offset = time_offset()?;
        send(stream, self.play_response())?;
        on_play();
        send(stream, self.time_offset(5, offset))?;
        // 期间可能夹带 VIDEO_LATENCY 等请求；找 CSeq 5 的应答
        loop {
            let m = read_message(stream)?;
            if m.is_response() && m.cseq() == Some(5) {
                if !m.start_line.contains(" 200 ") { return proto(format!("TIME_OFFSET rejected: {}", m.start_line)) }
                break;
            }
            log::debug!("RTSP during TIME_OFFSET: {}", m.start_line);
        }
        Ok(offset)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn timer_port_header_is_u32_ip() {
        let r = SourceRtsp::new(Ipv4Addr::new(192, 168, 1, 2), 40000);
        let s = String::from_utf8(r.m1_options()).unwrap();
        assert!(s.contains("wfd_timer_server_port:3232235778:40000\r\n"));
    }
    #[test]
    fn parse() {
        let raw = b"SETUP rtsp://x RTSP/1.0\r\nCSeq: 2\r\nTransport: RTP/AVP/TCP;interleaved=0-1\r\n\r\n";
        let m = read_message(&mut &raw[..]).unwrap();
        assert_eq!(m.method(), "SETUP");
        assert_eq!(m.cseq(), Some(2));
    }
}
