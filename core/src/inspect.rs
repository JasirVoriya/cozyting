//! 显式「只查询」会话：认证后仅发已知 GET_*，结束只做 TCP shutdown。
//!
//! **不是** 旧 `probe`/`devinfo`/`channel`：那些路径在源码里会 `RuntimeWriter::close()` → 协议 `CLOSE(0x0002)`。
//! 无证据断言 `CLOSE` 仅影响本连接；本模块因此**禁止**发送 `CLOSE`。
//! 亦不发送 Open / SetPlaySource / SetChannel / SetVolume / SetMediaInfo / SetLocalDeviceInfo。

use crate::auth::legacy;
use crate::commands as c;
use crate::control::{ControlSession, NATIVE_VERSION_STRING};
use crate::frame::{decode_kv, decode_scalar5, Frame};
use crate::{proto, Result};
use std::net::Ipv4Addr;
use std::time::{Duration, Instant};

/// `inspect` 允许主动发出的命令全集（测试锁定）。不含 CLOSE/Open/播放控制/SET_*。
pub const INSPECT_ALLOWED_TX: &[u16] = &[
    c::NATIVE_VERSION,
    c::LEGACY_CHALLENGE_ACK,
    c::GET_DEVICE_INFO,
    c::GET_MIRROR_MODE,
    c::GET_VOLUME,
    c::GET_MEDIA_INFO,
    c::GET_STATE,
    c::GET_CHANNEL,
];

/// 明确禁止出现在 inspect 发送集合中的命令（抽样锁定，非穷尽音箱全部命令空间）。
pub const INSPECT_FORBIDDEN_TX: &[u16] = &[
    c::CLOSE,
    c::OPEN,
    c::SET_PLAY_SOURCE,
    c::SET_CHANNEL,
    c::SET_VOLUME,
    c::SET_MEDIA_INFO,
    c::SET_LOCAL_DEVICE_INFO,
    c::PAUSE,
    c::RESUME,
    c::SET_MIRROR_KEY,
    c::HEARTBEAT,
];

fn is_allowed_tx(cmd: u16) -> bool {
    INSPECT_ALLOWED_TX.contains(&cmd)
}

/// GetDeviceInfo 可展示字段（排除账号/硬件标识等）。
fn device_info_public(kv: &[(String, String)]) -> Vec<(String, String)> {
    kv.iter()
        .filter(|(k, _)| {
            !matches!(
                k.as_str(),
                "accountId"
                    | "sn"
                    | "house_Id"
                    | "room_Id"
                    | "bluetoothMac"
                    | "deviceId"
                    | "mac"
                    | "wifiMac"
                    | "ip"
                    | "did"
                    | "miotDid"
            )
        })
        .cloned()
        .collect()
}

/// MEDIA_INFO 输出键白名单。
/// `status`: not_received | empty_payload | parse_error | ok
/// `source_name`: 仅 status=ok 时为 "absent"|"empty"|"nonempty"；否则 JSON null（绝不输出 ""）
pub const MEDIA_JSON_KEYS: &[&str] = &["status", "source_name", "keys_present"];

/// 允许出现在 `keys_present` 中的非内容协议键（不含标题/封面/URL 类键名）。
pub const MEDIA_PROTOCOL_KEY_ALLOWLIST: &[&str] = &[
    "mSourceName",
    "status",
    "volume",
    "mDuration",
    "id",
    "mDeviceState",
];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MediaResponseStatus {
    /// 未收到有效 GET_MEDIA_INFO_ACK（含超时未回）。
    NotReceived,
    /// 收到 ACK 但载荷为空。
    EmptyPayload,
    /// 收到 ACK 但无法解析为 JSON 对象。
    ParseError,
    /// 收到 ACK 且解析为 JSON 对象。
    Ok,
}

impl MediaResponseStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::NotReceived => "not_received",
            Self::EmptyPayload => "empty_payload",
            Self::ParseError => "parse_error",
            Self::Ok => "ok",
        }
    }
}

#[derive(Clone, Debug)]
pub struct MediaInfoSummary {
    pub status: MediaResponseStatus,
    /// 仅 `status == Ok` 时为 Some("absent"|"empty"|"nonempty")；否则 None → JSON null。
    pub source_name: Option<&'static str>,
    pub keys_present: Vec<String>,
}

impl Default for MediaInfoSummary {
    fn default() -> Self {
        Self::not_received()
    }
}

impl MediaInfoSummary {
    pub fn not_received() -> Self {
        Self {
            status: MediaResponseStatus::NotReceived,
            source_name: None,
            keys_present: Vec::new(),
        }
    }

    /// 由已收到的 ACK 载荷解析。空载荷 / 非 JSON 不得记成 source_name=absent。
    pub fn from_payload(p: &[u8]) -> Self {
        if p.is_empty() {
            return Self {
                status: MediaResponseStatus::EmptyPayload,
                source_name: None,
                keys_present: Vec::new(),
            };
        }
        let text = String::from_utf8_lossy(p);
        let trimmed = text.trim().trim_end_matches('\0');
        if !(trimmed.starts_with('{') && trimmed.ends_with('}')) {
            return Self {
                status: MediaResponseStatus::ParseError,
                source_name: None,
                keys_present: Vec::new(),
            };
        }
        let mut keys_present = Vec::new();
        for k in MEDIA_PROTOCOL_KEY_ALLOWLIST {
            if trimmed.contains(&format!("\"{k}\"")) {
                keys_present.push((*k).to_string());
            }
        }
        let source_name = Some(match json_string_value(trimmed, "mSourceName") {
            None => "absent",
            Some(s) if s.is_empty() => "empty",
            Some(_) => "nonempty",
        });
        Self {
            status: MediaResponseStatus::Ok,
            source_name,
            keys_present,
        }
    }

    pub fn to_json(&self) -> String {
        let sn = match self.source_name {
            Some(s) => format!("{s:?}"),
            None => "null".into(),
        };
        // 锁定：source_name 绝不为 JSON 空字符串 ""
        debug_assert!(sn != "\"\"");
        format!(
            "{{\"status\":{:?},\"source_name\":{},\"keys_present\":{:?}}}",
            self.status.as_str(),
            sn,
            self.keys_present
        )
    }
}

fn json_string_value<'a>(json: &'a str, key: &str) -> Option<&'a str> {
    let pat = format!("\"{key}\"");
    let i = json.find(&pat)?;
    let after = &json[i + pat.len()..];
    let after = after.trim_start().trim_start_matches(':').trim_start();
    if !after.starts_with('"') {
        return None;
    }
    let rest = &after[1..];
    let mut out_end = 0;
    let bytes = rest.as_bytes();
    let mut esc = false;
    for (idx, &b) in bytes.iter().enumerate() {
        if esc {
            esc = false;
            continue;
        }
        if b == b'\\' {
            esc = true;
            continue;
        }
        if b == b'"' {
            out_end = idx;
            break;
        }
    }
    Some(&rest[..out_end])
}

#[derive(Clone, Debug)]
pub struct InspectReport {
    pub ip: String,
    pub auth_route: String,
    pub mirror_mode: Option<u32>,
    pub state: Option<u32>,
    pub volume: Option<u8>,
    pub channel_tlv: Option<String>,
    /// 是否收到 GET_CHANNEL_ACK（与载荷是否为空分开；未收到不得伪造）。
    pub get_channel_received: bool,
    pub get_channel_payload_hex: Option<String>,
    pub device_info_public: Vec<(String, String)>,
    /// 键缺失 → None；键在且空 → Some(false)；键在非空 → Some(true)。不得用 false 冒充「未见到键」。
    pub group_id_present: Option<bool>,
    pub group_id: Option<String>,
    pub device_type: Option<String>,
    pub media: MediaInfoSummary,
    pub tx_commands: Vec<u16>,
    pub ended_with: &'static str,
    pub error: Option<String>,
}

impl Default for InspectReport {
    fn default() -> Self {
        Self {
            ip: String::new(),
            auth_route: String::new(),
            mirror_mode: None,
            state: None,
            volume: None,
            channel_tlv: None,
            get_channel_received: false,
            get_channel_payload_hex: None,
            device_info_public: Vec::new(),
            group_id_present: None,
            group_id: None,
            device_type: None,
            media: MediaInfoSummary::not_received(),
            tx_commands: Vec::new(),
            ended_with: "unset",
            error: None,
        }
    }
}

impl InspectReport {
    pub fn to_json(&self) -> String {
        let fields: Vec<String> = self
            .device_info_public
            .iter()
            .map(|(k, v)| format!("{:?}:{:?}", k, v))
            .collect();
        let tx: Vec<String> = self.tx_commands.iter().map(|c| format!("{c:#06x}")).collect();
        format!(
            "{{\"ip\":{:?},\"auth_route\":{:?},\"mirror_mode\":{},\"state\":{},\"volume\":{},\"channel_tlv\":{},\"get_channel_received\":{},\"get_channel_payload_hex\":{},\"group_id_present\":{},\"group_id\":{},\"device_type\":{},\"device_info_public\":{{{}}},\"media\":{},\"tx_commands\":[{}],\"ended_with\":{:?},\"error\":{}}}",
            self.ip,
            self.auth_route,
            opt_num(self.mirror_mode),
            opt_num(self.state),
            opt_num(self.volume.map(|v| v as u32)),
            self.channel_tlv.as_ref().map(|s| format!("{s:?}")).unwrap_or_else(|| "null".into()),
            self.get_channel_received,
            self.get_channel_payload_hex.as_ref().map(|s| format!("{s:?}")).unwrap_or_else(|| "null".into()),
            opt_bool(self.group_id_present),
            self.group_id.as_ref().map(|s| format!("{s:?}")).unwrap_or_else(|| "null".into()),
            self.device_type.as_ref().map(|s| format!("{s:?}")).unwrap_or_else(|| "null".into()),
            fields.join(","),
            self.media.to_json(),
            tx.iter().map(|s| format!("{s:?}")).collect::<Vec<_>>().join(","),
            self.ended_with,
            self.error.as_ref().map(|s| format!("{s:?}")).unwrap_or_else(|| "null".into()),
        )
    }

    pub fn assert_tx_policy(&self) -> Result<()> {
        for cmd in &self.tx_commands {
            if INSPECT_FORBIDDEN_TX.contains(cmd) {
                return proto(format!("inspect tx forbidden command {cmd:#06x}"));
            }
            if !is_allowed_tx(*cmd) {
                return proto(format!("inspect tx unexpected command {cmd:#06x}"));
            }
        }
        Ok(())
    }

    /// 将可选 ACK 应用到报告（供路径级测试；不连网）。
    pub fn apply_optional_acks(&mut self, media_ack: Option<&[u8]>, channel_ack: Option<&[u8]>) {
        match media_ack {
            None => self.media = MediaInfoSummary::not_received(),
            Some(p) => self.media = MediaInfoSummary::from_payload(p),
        }
        match channel_ack {
            None => {
                self.get_channel_received = false;
                self.get_channel_payload_hex = None;
            }
            Some(p) => {
                self.get_channel_received = true;
                self.get_channel_payload_hex = Some(p.iter().map(|b| format!("{b:02x}")).collect());
            }
        }
    }

    pub fn apply_device_info_kv(&mut self, kv: &[(String, String)]) {
        self.device_info_public = device_info_public(kv);
        self.channel_tlv = kv.iter().find(|(k, _)| k == "channel").map(|(_, v)| v.clone());
        self.device_type = kv.iter().find(|(k, _)| k == "deviceType").map(|(_, v)| v.clone());
        match kv.iter().find(|(k, _)| k == "groupId") {
            None => {
                self.group_id = None;
                self.group_id_present = None;
            }
            Some((_, v)) => {
                self.group_id = Some(v.clone());
                self.group_id_present = Some(!v.is_empty());
            }
        }
    }
}

fn opt_num(v: Option<u32>) -> String {
    v.map(|x| x.to_string()).unwrap_or_else(|| "null".into())
}

fn opt_bool(v: Option<bool>) -> String {
    match v {
        Some(true) => "true".into(),
        Some(false) => "false".into(),
        None => "null".into(),
    }
}

struct TxLog {
    cmds: Vec<u16>,
}

impl TxLog {
    fn push(&mut self, cmd: u16) -> Result<()> {
        if INSPECT_FORBIDDEN_TX.contains(&cmd) {
            return proto(format!("refusing to send forbidden inspect cmd {cmd:#06x}"));
        }
        if !is_allowed_tx(cmd) {
            return proto(format!("refusing to send non-allowlisted inspect cmd {cmd:#06x}"));
        }
        self.cmds.push(cmd);
        Ok(())
    }
}

/// Legacy 挑战应答后，仅 GET 查询；结束 TCP shutdown，**不**发协议 CLOSE。
pub fn inspect_legacy(ip: Ipv4Addr) -> Result<InspectReport> {
    let mut rep = InspectReport {
        ip: ip.to_string(),
        auth_route: "legacy".into(),
        ended_with: "tcp_shutdown",
        ..InspectReport::default()
    };
    let mut tx = TxLog { cmds: Vec::new() };
    let mut ctl = ControlSession::connect(ip)?;
    let outcome = (|| -> Result<()> {
        let ch = ctl.read_skipping_notify_public()?;
        if ch.command != c::LEGACY_CHALLENGE || !legacy::is_valid_challenge(&ch.payload) {
            return proto(format!("expected 0x0028 challenge, got {:#06x}", ch.command));
        }
        tx.push(c::NATIVE_VERSION)?;
        tx.push(c::LEGACY_CHALLENGE_ACK)?;
        tx.push(c::GET_DEVICE_INFO)?;
        ctl.send_tracked(&[
            Frame::new(c::NATIVE_VERSION, 0, NATIVE_VERSION_STRING.as_bytes().to_vec()),
            Frame::new(c::LEGACY_CHALLENGE_ACK, ch.sequence, legacy::challenge_response(&ch.payload)),
            Frame::empty(c::GET_DEVICE_INFO, 1),
        ])?;
        let ver_ack = ctl.read_skipping_notify_public()?;
        if ver_ack.command != c::NATIVE_VERSION_ACK {
            return proto(format!("expected NATIVE_VERSION_ACK, got {:#06x}", ver_ack.command));
        }
        let di = ctl.expect_cmd(c::GET_DEVICE_INFO_ACK, 1)?;
        let kv = decode_kv(&di.payload);
        rep.apply_device_info_kv(&kv);

        tx.push(c::GET_MIRROR_MODE)?;
        tx.push(c::GET_VOLUME)?;
        tx.push(c::GET_MEDIA_INFO)?;
        tx.push(c::GET_STATE)?;
        tx.push(c::GET_CHANNEL)?;
        ctl.send_tracked(&[
            Frame::empty(c::GET_MIRROR_MODE, 2),
            Frame::empty(c::GET_VOLUME, 3),
            Frame::empty(c::GET_MEDIA_INFO, 4),
            Frame::empty(c::GET_STATE, 5),
            Frame::empty(c::GET_CHANNEL, 6),
        ])?;

        let mut need_mirror = true;
        let mut need_vol = true;
        let mut need_media = true;
        let mut need_ch = true;
        let mut need_state = true;
        let deadline = Instant::now() + Duration::from_secs(5);
        while Instant::now() < deadline && (need_mirror || need_vol || need_media || need_state || need_ch) {
            let f = match ctl.read_frame_public() {
                Ok(f) => f,
                Err(crate::Error::Io(e))
                    if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) =>
                {
                    continue;
                }
                Err(e) => return Err(e),
            };
            if f.command == c::NOTIFY {
                ctl.absorb_notify_public(&f);
                continue;
            }
            if f.command == c::HEARTBEAT {
                continue;
            }
            match (f.command, f.sequence) {
                (c::GET_MIRROR_MODE_ACK, 2) => {
                    need_mirror = false;
                    rep.mirror_mode = decode_scalar5(&f.payload);
                }
                (c::GET_VOLUME_ACK, 3) => {
                    need_vol = false;
                    rep.volume = decode_scalar5(&f.payload).map(|v| v.min(100) as u8);
                }
                (c::GET_MEDIA_INFO_ACK, 4) => {
                    need_media = false;
                    rep.media = MediaInfoSummary::from_payload(&f.payload);
                }
                (c::GET_STATE_ACK, 5) => {
                    need_state = false;
                    rep.state = decode_scalar5(&f.payload);
                }
                (c::GET_CHANNEL_ACK, 6) => {
                    need_ch = false;
                    rep.get_channel_received = true;
                    rep.get_channel_payload_hex =
                        Some(f.payload.iter().map(|b| format!("{b:02x}")).collect());
                }
                (cmd, seq) => {
                    return proto(format!("unexpected inspect rx {cmd:#06x}/{seq}"));
                }
            }
        }
        if need_media {
            rep.media = MediaInfoSummary::not_received();
        }
        if need_ch {
            rep.get_channel_received = false;
            rep.get_channel_payload_hex = None;
        }
        if need_mirror || need_vol || need_state {
            return proto(format!(
                "inspect incomplete mirror={} vol={} media={} state={} channel={}",
                !need_mirror, !need_vol, !need_media, !need_state, !need_ch
            ));
        }
        Ok(())
    })();

    rep.tx_commands = tx.cmds;
    if let Err(e) = outcome {
        rep.error = Some(e.to_string());
    }
    ctl.tcp_shutdown_only();
    rep.ended_with = "tcp_shutdown";
    rep.assert_tx_policy()?;
    if let Some(err) = &rep.error {
        return Err(crate::Error::Protocol(err.clone()));
    }
    Ok(rep)
}

pub fn inspect_planned_tx_commands() -> &'static [u16] {
    INSPECT_ALLOWED_TX
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inspect_tx_allowlist_excludes_close_open_and_sets() {
        for cmd in INSPECT_FORBIDDEN_TX {
            assert!(!INSPECT_ALLOWED_TX.contains(cmd), "{cmd:#06x}");
        }
        assert!(!INSPECT_ALLOWED_TX.contains(&c::CLOSE));
        assert_eq!(inspect_planned_tx_commands(), INSPECT_ALLOWED_TX);
    }

    fn assert_media_json_whitelist(j: &str) {
        assert!(j.starts_with('{') && j.ends_with('}'), "{j}");
        assert_eq!(MEDIA_JSON_KEYS, &["status", "source_name", "keys_present"]);
        for key in MEDIA_JSON_KEYS {
            assert!(j.contains(&format!("\"{key}\"")), "missing {key} in {j}");
        }
        // 禁止 source_name 为 JSON 空字符串
        assert!(!j.contains("\"source_name\":\"\""), "empty-string source_name forbidden: {j}");
        for banned_obj_key in [
            "\"mDeviceState\":",
            "\"status\":0",
            "\"volume\":0",
            "\"has_title\":",
            "\"has_cover\":",
            "\"mTitle\":",
            "\"mCoverUrl\":",
        ] {
            assert!(!j.contains(banned_obj_key), "banned {banned_obj_key} in {j}");
        }
        for banned in ["has_title", "has_cover_or_url", "SecretPhone", "Private Song", "https://"] {
            assert!(!j.contains(banned), "banned {banned:?} in {j}");
        }
    }

    #[test]
    fn default_media_is_not_received_not_empty_string() {
        let d = MediaInfoSummary::default();
        assert_eq!(d.status, MediaResponseStatus::NotReceived);
        assert!(d.source_name.is_none());
        let j = d.to_json();
        assert_eq!(j, r#"{"status":"not_received","source_name":null,"keys_present":[]}"#);
        assert_media_json_whitelist(&j);
        assert!(!j.contains("\"source_name\":\"\""));
    }

    #[test]
    fn default_inspect_report_serialization_no_forged_media_or_channel() {
        let r = InspectReport::default();
        let j = r.to_json();
        assert!(j.contains("\"media\":{\"status\":\"not_received\",\"source_name\":null,\"keys_present\":[]}"), "{j}");
        assert!(j.contains("\"get_channel_received\":false"), "{j}");
        assert!(j.contains("\"get_channel_payload_hex\":null"), "{j}");
        assert!(j.contains("\"group_id_present\":null"), "{j}");
        assert!(j.contains("\"group_id\":null"), "{j}");
        assert!(j.contains("\"mirror_mode\":null"), "{j}");
        assert!(j.contains("\"ended_with\":\"unset\""), "{j}");
        assert!(!j.contains("\"source_name\":\"\""), "{j}");
    }

    #[test]
    fn media_not_received_vs_empty_payload_vs_parse_error_vs_field_states() {
        let none = MediaInfoSummary::not_received();
        assert_eq!(none.to_json(), r#"{"status":"not_received","source_name":null,"keys_present":[]}"#);

        let empty_ack = MediaInfoSummary::from_payload(&[]);
        assert_eq!(empty_ack.status, MediaResponseStatus::EmptyPayload);
        assert!(empty_ack.source_name.is_none());
        assert_eq!(empty_ack.to_json(), r#"{"status":"empty_payload","source_name":null,"keys_present":[]}"#);

        let bad = MediaInfoSummary::from_payload(b"not-json");
        assert_eq!(bad.status, MediaResponseStatus::ParseError);
        assert!(bad.source_name.is_none());
        assert_eq!(bad.to_json(), r#"{"status":"parse_error","source_name":null,"keys_present":[]}"#);

        let absent = MediaInfoSummary::from_payload(br#"{"status":1}"#);
        assert_eq!(absent.status, MediaResponseStatus::Ok);
        assert_eq!(absent.source_name, Some("absent"));
        assert_media_json_whitelist(&absent.to_json());

        let empty_field = MediaInfoSummary::from_payload(br#"{"mSourceName":""}"#);
        assert_eq!(empty_field.source_name, Some("empty"));
        assert_eq!(
            empty_field.to_json(),
            r#"{"status":"ok","source_name":"empty","keys_present":["mSourceName"]}"#
        );

        let nonempty = MediaInfoSummary::from_payload(
            br#"{"mSourceName":"SecretPhone","mTitle":"Private Song","mCoverUrl":"https://x","mDeviceState":2,"status":0,"volume":0}"#,
        );
        assert_eq!(nonempty.source_name, Some("nonempty"));
        let j = nonempty.to_json();
        assert!(j.starts_with(r#"{"status":"ok","source_name":"nonempty""#), "{j}");
        assert_media_json_whitelist(&j);
        assert!(!j.contains("SecretPhone"));
    }

    #[test]
    fn apply_optional_acks_path_distinguishes_missing_channel_and_media() {
        let mut r = InspectReport {
            ip: "192.0.2.1".into(),
            auth_route: "legacy".into(),
            mirror_mode: Some(1),
            state: Some(2),
            volume: Some(0),
            ended_with: "tcp_shutdown",
            ..InspectReport::default()
        };
        r.tx_commands = INSPECT_ALLOWED_TX.to_vec();
        // 模拟：无 MEDIA ACK、无 CHANNEL ACK（LX06 实测类）
        r.apply_optional_acks(None, None);
        let j = r.to_json();
        assert!(j.contains("\"status\":\"not_received\""), "{j}");
        assert!(j.contains("\"source_name\":null"), "{j}");
        assert!(!j.contains("\"source_name\":\"\""), "{j}");
        assert!(j.contains("\"get_channel_received\":false"), "{j}");
        assert!(j.contains("\"get_channel_payload_hex\":null"), "{j}");

        // 模拟：收到空 MEDIA ACK + 空 CHANNEL 载荷
        r.apply_optional_acks(Some(&[]), Some(&[]));
        let j2 = r.to_json();
        assert!(j2.contains("\"status\":\"empty_payload\""), "{j2}");
        assert!(j2.contains("\"source_name\":null"), "{j2}");
        assert!(j2.contains("\"get_channel_received\":true"), "{j2}");
        assert!(j2.contains("\"get_channel_payload_hex\":\"\""), "{j2}");

        // 模拟：有效 MEDIA + CHANNEL
        r.apply_optional_acks(Some(br#"{"mSourceName":"X"}"#), Some(&[0x01]));
        let j3 = r.to_json();
        assert!(j3.contains("\"status\":\"ok\""), "{j3}");
        assert!(j3.contains("\"source_name\":\"nonempty\""), "{j3}");
        assert!(j3.contains("\"get_channel_received\":true"), "{j3}");
        assert!(j3.contains("\"get_channel_payload_hex\":\"01\""), "{j3}");
        assert!(!j3.contains("\"source_name\":\"\""), "{j3}");
    }

    #[test]
    fn group_id_key_absent_is_null_not_false() {
        let mut r = InspectReport::default();
        r.apply_device_info_kv(&[("channel".into(), "center".into()), ("deviceType".into(), "4".into())]);
        assert_eq!(r.group_id_present, None);
        assert_eq!(r.group_id, None);
        let j = r.to_json();
        assert!(j.contains("\"group_id_present\":null"), "{j}");
        assert!(j.contains("\"group_id\":null"), "{j}");

        r.apply_device_info_kv(&[("groupId".into(), "".into())]);
        assert_eq!(r.group_id_present, Some(false));
        assert_eq!(r.group_id.as_deref(), Some(""));
        let j2 = r.to_json();
        assert!(j2.contains("\"group_id_present\":false"), "{j2}");
        assert!(j2.contains("\"group_id\":\"\""), "{j2}");

        r.apply_device_info_kv(&[("groupId".into(), "abc".into())]);
        assert_eq!(r.group_id_present, Some(true));
    }

    #[test]
    fn inspect_report_media_embed_keeps_top_level_device_volume() {
        let mut r = InspectReport {
            ip: "192.0.2.1".into(),
            auth_route: "legacy".into(),
            mirror_mode: Some(1),
            state: Some(2),
            volume: Some(0),
            media: MediaInfoSummary::from_payload(
                br#"{"mSourceName":"SecretPhone","mTitle":"Private Song","mCoverUrl":"https://x","volume":50}"#,
            ),
            ended_with: "tcp_shutdown",
            ..InspectReport::default()
        };
        r.tx_commands = INSPECT_ALLOWED_TX.to_vec();
        let j = r.to_json();
        assert!(j.contains("\"mirror_mode\":1"), "{j}");
        assert!(j.contains("\"volume\":0"), "{j}");
        assert!(j.contains("\"status\":\"ok\""), "{j}");
        assert!(j.contains("\"source_name\":\"nonempty\""), "{j}");
        assert!(!j.contains("SecretPhone"), "{j}");
        assert!(!j.contains("\"source_name\":\"\""), "{j}");
    }

    #[test]
    fn report_tx_policy_rejects_close() {
        let mut r = InspectReport::default();
        r.tx_commands = vec![c::NATIVE_VERSION, c::CLOSE];
        assert!(r.assert_tx_policy().is_err());
    }
}
