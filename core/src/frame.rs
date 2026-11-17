//! 8899 控制帧编解码：`'$'(0x24) | command:u16be | sequence:u16be | length:u32be | payload`。
//!
//! 来源：MiPlayForWindows `MiPlayControl.kt:96-125`；FusionPlay `protocol.rs:1783-1868`
//! （FusionPlay 把 command 拆成 outer_type + command 两个字节，语义相同）。
//! 现代链路的加密载荷以 `00 07 01 e0` 开头（FusionPlay `protocol.rs:1807`），由 `auth::safety` 处理。

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

/// 5 字节标量：tag(0) + u32be。用于 GetVolume_Ack / GetState_Ack / GetMirrorMode_Ack。
/// 来源：MiPlayControl.kt:530-533；FusionPlay protocol.rs:1445-1451。
/// SetVolume ACK：MiPlayForWindows 记录为回显 4 字节；实测 L17A/LX06 也可能回 5 字节标量。两种都接受。
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

/// SetVolume 载荷是**裸 4 字节** u32be（0..=100），与 GetVolume_Ack 的 5 字节不同。
/// 来源：MiPlayControl.kt:540-546；docs/miplay-windows-audio-source-transmitter.md:745-757。
pub fn encode_volume(v: u8) -> [u8; 4] {
    (v.min(100) as u32).to_be_bytes()
}

/// NOTIFY 载荷：`len | label | type(0x03) | u8 value`，如 `state=2`、`first-audiopcm=1`。
/// 来源：MiPlayControl.kt:519-528；FusionPlay protocol.rs:1226-1233。
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
