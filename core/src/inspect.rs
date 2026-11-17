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

/// MEDIA_INFO 输出白名单（仅此二键）：`source_name` 三态 + 非内容协议字段键存在性。
/// 不落盘原文；不输出标题/URL/封面及其存在标志；不输出 MEDIA 内 status/volume/mDeviceState 数值
/// （设备 volume/state/mirror 来自专用 GET_*，在 InspectReport 顶层保留）。
pub const MEDIA_JSON_KEYS: &[&str] = &["source_name", "keys_present"];

/// 允许出现在 `keys_present` 中的非内容协议键（不含标题/封面/URL 类键名）。
pub const MEDIA_PROTOCOL_KEY_ALLOWLIST: &[&str] = &[
    "mSourceName",
    "status",
    "volume",
    "mDuration",
    "id",
    "mDeviceState",
];

#[derive(Clone, Debug, Default)]
pub struct MediaInfoSummary {
    /// mSourceName：absent | empty | nonempty（不回传字符串本身）
    pub source_name: &'static str,
    pub keys_present: Vec<String>,
}

impl MediaInfoSummary {
    pub fn from_payload(p: &[u8]) -> Self {
        // 仅在栈上扫描；函数返回后不保留原文。
        if p.is_empty() {
            return Self {
                source_name: "absent",
                keys_present: Vec::new(),
            };
        }
        let text = String::from_utf8_lossy(p);
        let trimmed = text.trim().trim_end_matches('\0');
        if !(trimmed.starts_with('{') && trimmed.ends_with('}')) {
            return Self {
                source_name: "absent",
                keys_present: Vec::new(),
            };
        }
        let mut keys_present = Vec::new();
        for k in MEDIA_PROTOCOL_KEY_ALLOWLIST {
            if trimmed.contains(&format!("\"{k}\"")) {
                keys_present.push((*k).to_string());
            }
        }
        let source_name = match json_string_value(trimmed, "mSourceName") {
            None => "absent",
            Some(s) if s.is_empty() => "empty",
            Some(_) => "nonempty",
        };
        Self {
            source_name,
            keys_present,
        }
    }

    /// 固定两字段；测试锁定防止新增泄漏键。
    pub fn to_json(&self) -> String {
        format!(
            "{{\"source_name\":{:?},\"keys_present\":{:?}}}",
            self.source_name, self.keys_present
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

#[derive(Clone, Debug, Default)]
pub struct InspectReport {
    pub ip: String,
    pub auth_route: String,
    pub mirror_mode: Option<u32>,
    pub state: Option<u32>,
    pub volume: Option<u8>,
    pub channel_tlv: Option<String>,
    pub get_channel_payload_hex: Option<String>,
    pub device_info_public: Vec<(String, String)>,
    pub group_id_present: bool,
    pub group_id: Option<String>,
    pub device_type: Option<String>,
    pub media: MediaInfoSummary,
    /// 本连接实际发出的命令码（按发送顺序）。
    pub tx_commands: Vec<u16>,
    pub ended_with: &'static str,
    pub error: Option<String>,
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
            "{{\"ip\":{:?},\"auth_route\":{:?},\"mirror_mode\":{},\"state\":{},\"volume\":{},\"channel_tlv\":{},\"get_channel_payload_hex\":{},\"group_id_present\":{},\"group_id\":{},\"device_type\":{},\"device_info_public\":{{{}}},\"media\":{},\"tx_commands\":[{}],\"ended_with\":{:?},\"error\":{}}}",
            self.ip,
            self.auth_route,
            opt_num(self.mirror_mode),
            opt_num(self.state),
            opt_num(self.volume.map(|v| v as u32)),
            self.channel_tlv.as_ref().map(|s| format!("{s:?}")).unwrap_or_else(|| "null".into()),
            self.get_channel_payload_hex.as_ref().map(|s| format!("{s:?}")).unwrap_or_else(|| "null".into()),
            self.group_id_present,
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
}

fn opt_num(v: Option<u32>) -> String {
    v.map(|x| x.to_string()).unwrap_or_else(|| "null".into())
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
        ..Default::default()
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
        // 与官方引导前段一致：版本+挑战应答后立刻 GET_DEVICE_INFO，再收 NATIVE_VERSION_ACK。
        // 不发送 SET_LOCAL_DEVICE_INFO（避免登记 sourceName / isSameAccount）。
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
        rep.device_info_public = device_info_public(&kv);
        rep.channel_tlv = kv.iter().find(|(k, _)| k == "channel").map(|(_, v)| v.clone());
        rep.group_id = kv.iter().find(|(k, _)| k == "groupId").map(|(_, v)| v.clone());
        rep.group_id_present = rep.group_id.as_ref().map(|s| !s.is_empty()).unwrap_or(false);
        rep.device_type = kv.iter().find(|(k, _)| k == "deviceType").map(|(_, v)| v.clone());

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
        let mut need_state = true;
        let mut need_ch = true;
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
            // 不主动 HEARTBEAT，也不回 HEARTBEAT_ACK（避免扩大发送集合）。
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
                    rep.get_channel_payload_hex = Some(f.payload.iter().map(|b| format!("{b:02x}")).collect());
                }
                (cmd, seq) => {
                    // 未知应答：记录错误但不发送任何额外命令。
                    return proto(format!("unexpected inspect rx {cmd:#06x}/{seq}"));
                }
            }
        }
        if need_mirror || need_vol || need_state {
            return proto(format!(
                "inspect incomplete mirror={} vol={} media={} state={} channel={}",
                !need_mirror, !need_vol, !need_media, !need_state, !need_ch
            ));
        }
        // media/channel 可缺：部分音箱可能不回；保留已得字段。
        Ok(())
    })();

    rep.tx_commands = tx.cmds;
    if let Err(e) = outcome {
        rep.error = Some(e.to_string());
    }
    // 无论成败：只 TCP shutdown，不发 CLOSE。
    ctl.tcp_shutdown_only();
    rep.ended_with = "tcp_shutdown";
    rep.assert_tx_policy()?;
    if let Some(err) = &rep.error {
        return Err(crate::Error::Protocol(err.clone()));
    }
    Ok(rep)
}

/// 供测试：计划发送集合必须与运行时 allowlist 一致。
pub fn inspect_planned_tx_commands() -> &'static [u16] {
    INSPECT_ALLOWED_TX
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inspect_tx_allowlist_excludes_close_open_and_sets() {
        for cmd in INSPECT_FORBIDDEN_TX {
            assert!(
                !INSPECT_ALLOWED_TX.contains(cmd),
                "{cmd:#06x} must not be in allowlist"
            );
        }
        assert!(INSPECT_ALLOWED_TX.contains(&c::GET_DEVICE_INFO));
        assert!(INSPECT_ALLOWED_TX.contains(&c::GET_MIRROR_MODE));
        assert!(INSPECT_ALLOWED_TX.contains(&c::GET_STATE));
        assert!(INSPECT_ALLOWED_TX.contains(&c::GET_CHANNEL));
        assert!(INSPECT_ALLOWED_TX.contains(&c::GET_MEDIA_INFO));
        assert!(!INSPECT_ALLOWED_TX.contains(&c::CLOSE));
        assert!(!INSPECT_ALLOWED_TX.contains(&c::OPEN));
        assert!(!INSPECT_ALLOWED_TX.contains(&c::SET_PLAY_SOURCE));
        assert!(!INSPECT_ALLOWED_TX.contains(&c::SET_LOCAL_DEVICE_INFO));
        assert_eq!(inspect_planned_tx_commands(), INSPECT_ALLOWED_TX);
    }

    /// media JSON 顶层键必须恰好为白名单；禁止标题/封面标志与 MEDIA 内数值对象键。
    /// `keys_present` 数组内可含协议键名字符串（含 mDeviceState），但不得作为 media 对象键输出数值。
    fn assert_media_json_whitelist(j: &str) {
        assert!(j.starts_with('{') && j.ends_with('}'), "{j}");
        assert_eq!(
            MEDIA_JSON_KEYS,
            &["source_name", "keys_present"],
            "MEDIA_JSON_KEYS drift"
        );
        for key in MEDIA_JSON_KEYS {
            assert!(j.contains(&format!("\"{key}\"")), "missing {key} in {j}");
        }
        // 对象键形态（带冒号）禁止；数组内的同名字符串仍允许
        for banned_obj_key in [
            "\"mDeviceState\":",
            "\"status\":",
            "\"volume\":",
            "\"mDuration\":",
            "\"id\":",
            "\"ack_seen\":",
            "\"payload_kind\":",
            "\"has_title\":",
            "\"has_cover\":",
            "\"has_cover_or_url\":",
            "\"mTitle\":",
            "\"mArtist\":",
            "\"mAlbum\":",
            "\"mCoverUrl\":",
            "\"mArt\":",
        ] {
            assert!(
                !j.contains(banned_obj_key),
                "media json must not contain object key {banned_obj_key}: {j}"
            );
        }
        for banned in [
            "has_title",
            "has_cover_or_url",
            "SecretPhone",
            "Private Song",
            "https://",
            "example/",
        ] {
            assert!(!j.contains(banned), "media json must not contain {banned:?}: {j}");
        }
        // keys_present 不得列入内容键名
        if let Some(i) = j.find("\"keys_present\"") {
            let arr = &j[i..];
            for bad in ["mTitle", "mArtist", "mAlbum", "mCoverUrl", "mArt"] {
                assert!(
                    !arr.contains(&format!("\"{bad}\"")),
                    "keys_present must not list content key {bad}: {j}"
                );
            }
        }
    }

    #[test]
    fn media_json_whitelist_strips_content_and_numeric_fields() {
        let raw = br#"{"mSourceName":"SecretPhone","mTitle":"Private Song","mArtist":"X","mCoverUrl":"https://example/x.jpg","mArt":"y","mDeviceState":2,"status":0,"volume":0,"mDuration":1,"id":""}"#;
        let s = MediaInfoSummary::from_payload(raw);
        let j = s.to_json();
        assert_eq!(s.source_name, "nonempty");
        assert_eq!(j, r#"{"source_name":"nonempty","keys_present":["mSourceName", "status", "volume", "mDuration", "id", "mDeviceState"]}"#);
        assert_media_json_whitelist(&j);
        // keys_present 可含协议键名，但不得含标题/封面键名
        for bad in ["mTitle", "mArtist", "mAlbum", "mCoverUrl", "mArt"] {
            assert!(!s.keys_present.iter().any(|k| k == bad), "{:?}", s.keys_present);
        }
    }

    #[test]
    fn media_json_source_name_empty_and_absent() {
        let empty = MediaInfoSummary::from_payload(br#"{"mSourceName":"","status":1}"#);
        assert_eq!(empty.source_name, "empty");
        assert_media_json_whitelist(&empty.to_json());
        let absent = MediaInfoSummary::from_payload(br#"{"status":1}"#);
        assert_eq!(absent.source_name, "absent");
        assert_media_json_whitelist(&absent.to_json());
        let none = MediaInfoSummary::from_payload(&[]);
        assert_eq!(none.source_name, "absent");
        assert!(none.keys_present.is_empty());
        assert_eq!(none.to_json(), r#"{"source_name":"absent","keys_present":[]}"#);
        let opaque = MediaInfoSummary::from_payload(b"not-json");
        assert_eq!(opaque.source_name, "absent");
        assert_media_json_whitelist(&opaque.to_json());
    }

    #[test]
    fn inspect_report_media_embed_keeps_top_level_device_volume() {
        // 顶层 volume/state/mirror 来自专用 GET_*，与 media 白名单分离。
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
            ..Default::default()
        };
        r.tx_commands = INSPECT_ALLOWED_TX.to_vec();
        let j = r.to_json();
        assert!(j.contains("\"mirror_mode\":1"), "{j}");
        assert!(j.contains("\"state\":2"), "{j}");
        assert!(j.contains("\"volume\":0"), "{j}"); // 顶层专用查询
        let media_start = j.find("\"media\":").expect(j.as_str());
        let media_slice = &j[media_start..];
        let media_json = media_slice
            .strip_prefix("\"media\":")
            .and_then(|s| {
                let start = s.find('{')?;
                let end = s.find("},\"tx_commands\"")?;
                Some(&s[start..end + 1])
            })
            .expect(j.as_str());
        assert_media_json_whitelist(media_json);
        assert!(!j.contains("SecretPhone"), "{j}");
        assert!(!j.contains("Private Song"), "{j}");
        assert!(!j.contains("https://"), "{j}");
        assert!(!j.contains("has_title"), "{j}");
        assert!(!j.contains("has_cover"), "{j}");
    }

    #[test]
    fn report_tx_policy_rejects_close() {
        let mut r = InspectReport::default();
        r.tx_commands = vec![c::NATIVE_VERSION, c::CLOSE];
        assert!(r.assert_tx_policy().is_err());
    }
}
