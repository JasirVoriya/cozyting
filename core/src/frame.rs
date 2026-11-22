//! 8899 控制帧编解码：`'$'(0x24) | command:u16be | sequence:u16be | length:u32be | payload`。
//!
//! 现代 Safety 链路的加密载荷以 `00 07 01 e0` 开头，由 `auth::safety` 处理。

use crate::{proto, Result};
use std::io::{Read, Write};

pub const MAGIC: u8 = 0x24;
pub const HEADER_LEN: usize = 9;
pub const MAX_PAYLOAD: usize = 4 * 1024 * 1024;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Frame {
    pub command: u16,
    pub sequence: u16,
    pub payload: Vec<u8>,
}

impl Frame {
    pub fn new(command: u16, sequence: u16, payload: impl Into<Vec<u8>>) -> Self {
        Self { command, sequence, payload: payload.into() }
    }

    pub fn empty(command: u16, sequence: u16) -> Self {
        Self::new(command, sequence, Vec::new())
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(HEADER_LEN + self.payload.len());
        out.push(MAGIC);
        out.extend_from_slice(&self.command.to_be_bytes());
        out.extend_from_slice(&self.sequence.to_be_bytes());
        out.extend_from_slice(&(self.payload.len() as u32).to_be_bytes());
        out.extend_from_slice(&self.payload);
        out
    }

    pub fn read_from(r: &mut impl Read) -> Result<Self> {
        let mut h = [0u8; HEADER_LEN];
        r.read_exact(&mut h)?;
        if h[0] != MAGIC {
            return proto(format!("bad magic {:02x}", h[0]));
        }
        let command = u16::from_be_bytes([h[1], h[2]]);
        let sequence = u16::from_be_bytes([h[3], h[4]]);
        let len = u32::from_be_bytes([h[5], h[6], h[7], h[8]]) as usize;
        if len > MAX_PAYLOAD {
            return proto(format!("payload too large: {len}"));
        }
        let mut payload = vec![0u8; len];
        r.read_exact(&mut payload)?;
        Ok(Self { command, sequence, payload })
    }

    pub fn write_to(&self, w: &mut impl Write) -> Result<()> {
        w.write_all(&self.encode())?;
        Ok(())
    }
}

/// 5 字节标量：`tag(0) + u32be`。用于 `GetVolume_Ack` / `GetState_Ack` / `GetMirrorMode_Ack`。
/// `SetVolume_Ack` 可能返回 4 字节 u32be 或 5 字节标量，两种格式均支持。
pub fn decode_volume_ack(p: &[u8]) -> Option<u8> {
    match p.len() {
        4 => Some(u32::from_be_bytes([p[0], p[1], p[2], p[3]]).min(255) as u8),
        5 => decode_scalar5(p).map(|v| v.min(255) as u8),
        _ => None,
    }
}

pub fn decode_scalar5(p: &[u8]) -> Option<u32> {
    (p.len() == 5 && p[0] == 0).then(|| u32::from_be_bytes([p[1], p[2], p[3], p[4]]))
}

/// `SetVolume` 载荷为裸 4 字节 `u32be`（`0..=100`），与 `GetVolume_Ack` 的 5 字节标量不同。
pub fn encode_volume(v: u8) -> [u8; 4] {
    (v.min(100) as u32).to_be_bytes()
}

/// `NOTIFY` 载荷：`len | label | type(0x03) | u8 value`，如 `state=2`、`first-audiopcm=1`。
pub fn decode_notify(p: &[u8]) -> Option<(String, Option<u8>)> {
    let n = *p.first()? as usize;
    if n == 0 || p.len() < n + 1 {
        return None;
    }
    let label = String::from_utf8_lossy(&p[1..1 + n]).into_owned();
    let value = match p.get(1 + n) {
        Some(0x03) => p.get(2 + n).copied(),
        _ => None,
    };
    Some((label, value))
}

/// 键值 TLV（GetDeviceInfo 应答 0x001f，06:20 UTC+8 实测字节）：
/// `00 | totalLen:u16be | { keyLen:u8 | key | type:u8 | value }*`；type 0x0c = `len:u16be | UTF-8`，type 0x03 = 1 字节整数。
/// 例：`07 'channel' 0c 00 06 'center'`。遇到未知类型即停止。
pub fn decode_kv(p: &[u8]) -> Vec<(String, String)> {
    let mut out = vec![];
    let mut i = if p.len() >= 3 && p[0] == 0 && u16::from_be_bytes([p[1], p[2]]) as usize + 3 == p.len() { 3 } else { 0 };
    while i < p.len() {
        let kl = p[i] as usize;
        if kl == 0 || i + 1 + kl + 1 > p.len() { break; }
        let key = String::from_utf8_lossy(&p[i + 1..i + 1 + kl]).into_owned();
        let t = p[i + 1 + kl];
        let mut j = i + 2 + kl;
        let val = match t {
            0x03 => { let v = match p.get(j) { Some(v) => *v, None => break }; j += 1; v.to_string() }
            0x0c => {
                if j + 2 > p.len() { break; }
                let n = u16::from_be_bytes([p[j], p[j + 1]]) as usize;
                j += 2;
                if j + n > p.len() { break; }
                let v = String::from_utf8_lossy(&p[j..j + n]).into_owned();
                j += n;
                v
            }
            _ => break,
        };
        out.push((key, val));
        i = j;
    }
    out
}

/// 从 GetDeviceInfo 应答里取声道（"center"/"left"/"right"…）。
pub fn device_info_channel(p: &[u8]) -> Option<String> {
    decode_kv(p).into_iter().find(|(k, _)| k == "channel").map(|(_, v)| v)
}

/// 从 GetDeviceInfo(0x001f) 应答里提取的跨端展示元数据。
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct DeviceInfoMeta {
    pub mi_name: Option<String>,
    pub room_name: Option<String>,
}

/// 从 GetDeviceInfo 应答里同时提取用户自定义设备名（`miName`）与米家房间名（`roomName`）；
/// 缺失或空白字段归一化为 `None`。
pub fn device_info_meta(p: &[u8]) -> DeviceInfoMeta {
    let mut meta = DeviceInfoMeta::default();
    for (k, v) in decode_kv(p) {
        let trimmed = v.trim();
        if trimmed.is_empty() {
            continue;
        }
        match k.as_str() {
            "miName" if meta.mi_name.is_none() => meta.mi_name = Some(trimmed.to_string()),
            "roomName" if meta.room_name.is_none() => meta.room_name = Some(trimmed.to_string()),
            _ => {}
        }
    }
    meta
}

/// 从 GetDeviceInfo 应答里取用户自定义设备名（`miName`）；缺失或空白返回 `None`。
pub fn device_info_mi_name(p: &[u8]) -> Option<String> {
    device_info_meta(p).mi_name
}

/// 从 GetDeviceInfo 应答里取米家房间名（`roomName`）；缺失或空白返回 `None`。
pub fn device_info_room_name(p: &[u8]) -> Option<String> {
    device_info_meta(p).room_name
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn device_info_kv() {
        let mut p = vec![0u8, 0, 0];
        p.extend_from_slice(b"\x07channel\x0c\x00\x06center\x0adeviceType\x0c\x00\x014");
        let n = (p.len() - 3) as u16;
        p[1..3].copy_from_slice(&n.to_be_bytes());
        assert_eq!(device_info_channel(&p).as_deref(), Some("center"));
        assert_eq!(decode_kv(&p)[1], ("deviceType".to_string(), "4".to_string()));
        assert_eq!(device_info_mi_name(&p), None);
    }

    #[test]
    fn device_info_mi_name_extracts_utf8_and_ignores_blank() {
        let name = "客厅音箱-0001";
        let nb = name.as_bytes();
        let mut p = vec![0u8, 0, 0];
        p.push(6);
        p.extend_from_slice(b"miName");
        p.push(0x0c);
        p.extend_from_slice(&(nb.len() as u16).to_be_bytes());
        p.extend_from_slice(nb);
        let total = (p.len() - 3) as u16;
        p[1..3].copy_from_slice(&total.to_be_bytes());
        assert_eq!(device_info_mi_name(&p).as_deref(), Some(name));

        let mut blank = vec![0u8, 0, 0];
        blank.extend_from_slice(b"\x06miName\x0c\x00\x03   ");
        let total_b = (blank.len() - 3) as u16;
        blank[1..3].copy_from_slice(&total_b.to_be_bytes());
        assert_eq!(device_info_mi_name(&blank), None);
        assert_eq!(device_info_room_name(&blank), None);
    }

    #[test]
    fn device_info_meta_extracts_mi_name_and_room_name_and_hides_blank_room() {
        let name = "小爱音箱Pro-0001";
        let room = "客厅";
        let nb = name.as_bytes();
        let rb = room.as_bytes();
        let mut p = vec![0u8, 0, 0];
        p.push(6);
        p.extend_from_slice(b"miName");
        p.push(0x0c);
        p.extend_from_slice(&(nb.len() as u16).to_be_bytes());
        p.extend_from_slice(nb);
        p.push(8);
        p.extend_from_slice(b"roomName");
        p.push(0x0c);
        p.extend_from_slice(&(rb.len() as u16).to_be_bytes());
        p.extend_from_slice(rb);
        let total = (p.len() - 3) as u16;
        p[1..3].copy_from_slice(&total.to_be_bytes());
        assert_eq!(
            device_info_meta(&p),
            DeviceInfoMeta {
                mi_name: Some(name.into()),
                room_name: Some(room.into()),
            }
        );

        let mut empty_room = vec![0u8, 0, 0];
        empty_room.extend_from_slice(b"\x06miName\x0c\x00\x09Sound Pro\x08roomName\x0c\x00\x00");
        let total_e = (empty_room.len() - 3) as u16;
        empty_room[1..3].copy_from_slice(&total_e.to_be_bytes());
        assert_eq!(
            device_info_meta(&empty_room),
            DeviceInfoMeta {
                mi_name: Some("Sound Pro".into()),
                room_name: None,
            }
        );
    }

    #[test]
    fn roundtrip() {
        let f = Frame::new(0x0058, 2, br#"{"sourceName":"x"}"#.to_vec());
        let bytes = f.encode();
        assert_eq!(&bytes[..5], &[0x24, 0x00, 0x58, 0x00, 0x02]);
        let g = Frame::read_from(&mut &bytes[..]).unwrap();
        assert_eq!(f, g);
    }
    #[test]
    fn notify() {
        let p = [5, b's', b't', b'a', b't', b'e', 3, 2];
        assert_eq!(decode_notify(&p), Some(("state".into(), Some(2))));
    }
}
