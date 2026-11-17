//! 现代 SafetyAuth / SafetyData。
//!
//! 已从源码确认（FusionPlay 接收端 + MiPlayForWindows 研究代码交叉一致）：
//! - authKey = lowercase_hex(MD5(map(recvIP + recvPort + sendIP + sendPort)))，map 把数字 '0'..'9' 映射为 'a'..'j'
//!   （FusionPlay protocol.rs:1991-2010，测试向量 :2415-2422；MiPlayForWindows MiPlaySafetyKeyDerivation.cs:16-41；
//!   顺序 “对端(音箱:8899)在前” 见 docs/miplay-research.md:153）。
//! - AES key = authKey 前 16 个 ASCII 字符；初始 IV = 同一 16 字节；CBC，IV 在**每个方向**上链式延续（下一 IV = 上一密文末块）
//!   （FusionPlay protocol.rs:1076-1078, 1870-1932）。
//! - 信封：`00 07 01 e0 | pad:u8 | integrity:u32be | ciphertext`，零填充，pad∈1..=16（同上 :1880-1904）。
//! - integrity：非反射 CRC-32(0x04c11db7) 表项字节翻转、初值 0xffffffff、无终值异或（同上 :1934-1958）。
//! - OPack 包装：`klen | key | u32le(30) | dlen:u8 | data`（同上 :1960-1989）。
//! - SafetyAuth：双方各发 `{"authMsg": <32hex随机>}`，对方回 `authMsgAck = hex(HMAC-SHA256(authKey_ascii32, authMsg))`
//!   （同上 :1270-1349）。
//! - SafetyInfo offer（发送端→音箱）：`{"authKeyTypes":"1","authAlgorithmTypes":"7","integrityTypes":"1","aesKeyTypes":"1","aesIvTypes":"3"}`，
//!   S12 选择 `(1,4,1,1,2)`（MiPlayForWindows docs/miplay-research.md:126-145）。
//!
//! 06:10 UTC+8 实测（LX06 固件 2.1.5091615 / L17A 2.1.5080516）：
//! - 出站方向 IV 也取 authKey 前半段（与入站相同），互验 HMAC 双向通过；
//! - 本端 0x1403 `result` 必须为 "0"：发 "1"（MiPlayForWindows 的做法）音箱在回 0x1403 后立即断开，这就是 Windows 实验“认证后被断”的原因；
//! - 认证后所有 8899 帧（含空载荷）都 SafetyData 加密，legacy 同款引导、心跳、音量、暂停/恢复照常工作；RTSP/RTP 走独立连接、保持明文。
//! 未做：SetMirrorKey 与媒体 PES 加密（不发也能出 first-audiopcm 和 state=2）。

use aes::cipher::{block_padding::NoPadding, BlockDecryptMut, BlockEncryptMut, KeyIvInit};
use hmac::{Hmac, Mac};
use md5::{Digest, Md5};
use sha2::Sha256;
use std::net::SocketAddrV4;

use crate::{Error, Result};

type Enc = cbc::Encryptor<aes::Aes128>;
type Dec = cbc::Decryptor<aes::Aes128>;

pub const ENVELOPE_PREFIX: [u8; 4] = [0x00, 0x07, 0x01, 0xe0];
pub const OPACK_VALUE_TYPE: u32 = 30;

pub fn derive_auth_key(receiver: SocketAddrV4, sender: SocketAddrV4) -> String {
    let s = format!("{}{}{}{}", receiver.ip(), receiver.port(), sender.ip(), sender.port());
    let mapped: String = s
        .chars()
        .map(|c| if c.is_ascii_digit() { (c as u8 - b'0' + b'a') as char } else { c })
        .collect();
    hex::encode(Md5::digest(mapped.as_bytes()))
}

pub fn integrity(bytes: &[u8]) -> u32 {
    fn entry(i: u8) -> u32 {
        let mut v = (i as u32) << 24;
        for _ in 0..8 {
            v = if v & 0x8000_0000 != 0 { (v << 1) ^ 0x04c1_1db7 } else { v << 1 };
        }
        v.swap_bytes()
    }
    let mut v = u32::MAX;
    for b in bytes {
        v = entry((v as u8) ^ b) ^ (v >> 8);
    }
    v
}

/// 单方向 CBC 状态。发送端需要两个实例：outbound / inbound。
pub struct DirectionalCipher {
    key: [u8; 16],
    iv: [u8; 16],
}

impl DirectionalCipher {
    pub fn from_auth_key(auth_key_hex32: &str) -> Result<Self> {
        let b = auth_key_hex32.as_bytes();
        if b.len() < 16 {
            return Err(Error::Crypto("authKey too short".into()));
        }
        let mut key = [0u8; 16];
        key.copy_from_slice(&b[..16]);
        Ok(Self { key, iv: key })
    }

    pub fn seal(&mut self, plain: &[u8]) -> Vec<u8> {
        let pad = 16 - plain.len() % 16;
        let mut buf = plain.to_vec();
        buf.resize(plain.len() + pad, 0);
        let n = buf.len();
        Enc::new(&self.key.into(), &self.iv.into())
            .encrypt_padded_mut::<NoPadding>(&mut buf, n)
            .expect("block aligned");
        self.iv.copy_from_slice(&buf[n - 16..]);
        let mut out = Vec::with_capacity(9 + n);
        out.extend_from_slice(&ENVELOPE_PREFIX);
        out.push(pad as u8);
        out.extend_from_slice(&integrity(&buf).to_be_bytes());
        out.extend_from_slice(&buf);
        out
    }

    pub fn open(&mut self, body: &[u8]) -> Result<Vec<u8>> {
        if body.len() < 25 || (body.len() - 9) % 16 != 0 || body[..4] != ENVELOPE_PREFIX {
            return Err(Error::Crypto(format!("bad envelope len {}", body.len())));
        }
        let pad = body[4] as usize;
        let mut ct = body[9..].to_vec();
        let crc = u32::from_be_bytes([body[5], body[6], body[7], body[8]]);
        if crc != integrity(&ct) {
            return Err(Error::Crypto("integrity mismatch".into()));
        }
        let next_iv: [u8; 16] = ct[ct.len() - 16..].try_into().unwrap();
        Dec::new(&self.key.into(), &self.iv.into())
            .decrypt_padded_mut::<NoPadding>(&mut ct)
            .map_err(|_| Error::Crypto("decrypt".into()))?;
        self.iv = next_iv;
        if pad == 0 || pad > 16 || pad > ct.len() {
            return Err(Error::Crypto(format!("bad pad {pad}")));
        }
        ct.truncate(ct.len() - pad);
        Ok(ct)
    }
}

/// OPack 安全命令内层（MiPlayForWindows MiPlaySafetyEnvelopeCodec，真机已验证）：
/// `tagLen:u8 | tag("cmd"/"ack") | valueType:u8(=30) | len:u32be | json`。
/// 注：FusionPlay 的写法 `u32le(30) | len:u8` 在 len<256 时字节完全相同。
pub fn opack_wrap(key: &str, data: &[u8]) -> Result<Vec<u8>> {
    if key.len() > 255 {
        return Err(Error::Protocol("OPack tag > 255 bytes".into()));
    }
    let mut v = vec![key.len() as u8];
    v.extend_from_slice(key.as_bytes());
    v.push(OPACK_VALUE_TYPE as u8);
    v.extend_from_slice(&(data.len() as u32).to_be_bytes());
    v.extend_from_slice(data);
    Ok(v)
}

pub fn opack_unwrap(body: &[u8]) -> Result<(String, u32, Vec<u8>)> {
    let kl = *body.first().ok_or_else(|| Error::Protocol("empty wrapper".into()))? as usize;
    if body.len() < kl + 6 {
        return Err(Error::Protocol("truncated wrapper".into()));
    }
    let key = String::from_utf8_lossy(&body[1..1 + kl]).into_owned();
    let t = body[1 + kl] as u32;
    let dl = u32::from_be_bytes(body[2 + kl..6 + kl].try_into().unwrap()) as usize;
    let data = body
        .get(6 + kl..6 + kl + dl)
        .ok_or_else(|| Error::Protocol("truncated data".into()))?
        .to_vec();
    Ok((key, t, data))
}

/// 扁平 JSON 里取字符串字段（安全命令的 JSON 都是一层 string→string）。
pub fn json_str(json: &[u8], field: &str) -> Option<String> {
    let s = std::str::from_utf8(json).ok()?;
    let pat = format!("\"{field}\"");
    let i = s.find(&pat)? + pat.len();
    let rest = s[i..].trim_start().strip_prefix(':')?.trim_start().strip_prefix('"')?;
    Some(rest[..rest.find('"')?].to_string())
}

/// 本端挑战：与原生 sendSafetyAuth 相同，MD5(十进制微秒时间戳) 的小写 hex。
pub fn new_auth_msg() -> String {
    let us = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_micros()).unwrap_or(0);
    hex::encode(Md5::digest(us.to_string().as_bytes()))
}

/// HMAC，按 0x1401 选中的 authAlgorithmType（1=MD5, 2=SHA1, 4=SHA256）。
pub fn auth_msg_ack_alg(alg: u32, auth_key_hex32: &str, auth_msg: &str) -> Result<String> {
    Ok(match alg {
        1 => { let mut m = Hmac::<Md5>::new_from_slice(auth_key_hex32.as_bytes()).unwrap(); m.update(auth_msg.as_bytes()); hex::encode(m.finalize().into_bytes()) }
        2 => { let mut m = Hmac::<sha1::Sha1>::new_from_slice(auth_key_hex32.as_bytes()).unwrap(); m.update(auth_msg.as_bytes()); hex::encode(m.finalize().into_bytes()) }
        4 => auth_msg_ack(auth_key_hex32, auth_msg),
        a => return Err(Error::Crypto(format!("unsupported authAlgorithmType {a}"))),
    })
}

/// 0x1401 的选择结果。
#[derive(Clone, Debug, Default)]
pub struct SafetySelection {
    pub result: String,
    pub auth_key_type: u32,
    pub auth_algorithm_type: u32,
    pub integrity_type: u32,
    pub aes_key_type: u32,
    pub aes_iv_type: u32,
}

impl SafetySelection {
    pub fn parse(json: &[u8]) -> Option<Self> {
        let n = |k: &str| json_str(json, k).and_then(|v| v.parse().ok());
        Some(Self {
            result: json_str(json, "result")?,
            auth_key_type: n("authKeyType")?,
            auth_algorithm_type: n("authAlgorithmType")?,
            integrity_type: n("integrityType")?,
            aes_key_type: n("aesKeyType")?,
            aes_iv_type: n("aesIvType")?,
        })
    }
}

pub fn auth_msg_ack(auth_key_hex32: &str, auth_msg: &str) -> String {
    let mut mac = Hmac::<Sha256>::new_from_slice(auth_key_hex32.as_bytes()).expect("hmac");
    mac.update(auth_msg.as_bytes());
    hex::encode(mac.finalize().into_bytes())
}

pub const SAFETY_INFO_OFFER_JSON: &str =
    r#"{"authKeyTypes":"1","authAlgorithmTypes":"7","integrityTypes":"1","aesKeyTypes":"1","aesIvTypes":"3"}"#;

/// 现代媒体密钥（SetMirrorKey 0x006c JSON：authKey/streamKey/streamIV，各 16 个 ASCII 字符）。
/// 媒体加密：每个 PES 的 ADTS 载荷前 min(len,256) 向下取整到 16 的字节做 AES-128-CBC（无填充），
/// IV 可由 PES private data 携带（FusionPlay media.rs:1507-1556, 1575-1600）。发送端实现为接收端的逆运算。
#[derive(Clone)]
pub struct MirrorKeys {
    pub auth_key: [u8; 16],
    pub stream_key: [u8; 16],
    pub stream_iv: [u8; 16],
}

impl MirrorKeys {
    pub fn random() -> Self {
        use rand::Rng;
        let mut r = rand::thread_rng();
        let mut gen = || -> [u8; 16] {
            let mut a = [0u8; 16];
            for b in a.iter_mut() {
                *b = b"0123456789abcdef"[r.gen_range(0..16)];
            }
            a
        };
        Self { auth_key: gen(), stream_key: gen(), stream_iv: gen() }
    }

    pub fn to_json(&self) -> String {
        format!(
            r#"{{"authKey":"{}","streamKey":"{}","streamIV":"{}"}}"#,
            String::from_utf8_lossy(&self.auth_key),
            String::from_utf8_lossy(&self.stream_key),
            String::from_utf8_lossy(&self.stream_iv)
        )
    }

    /// 原地加密 ADTS 帧前缀（与 FusionPlay 解密逻辑互逆）。PES private-data IV 的写法需抓包确认，暂用固定 stream_iv。
    pub fn encrypt_adts_prefix(&self, adts: &mut [u8]) {
        let n = adts.len().min(256) / 16 * 16;
        if n == 0 {
            return;
        }
        Enc::new(&self.stream_key.into(), &self.stream_iv.into())
            .encrypt_padded_mut::<NoPadding>(&mut adts[..n], n)
            .expect("aligned");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// FusionPlay 抓包测试向量：protocol.rs:2415-2422（local=接收端 192.168.31.128:8899, remote=手机 192.168.31.207:49668）。
    #[test]
    fn auth_key_vector() {
        let k = derive_auth_key("192.168.31.128:8899".parse().unwrap(), "192.168.31.207:49668".parse().unwrap());
        assert_eq!(k, "b0175093fe7e51f8ac3cdeb42cd9f513");
    }

    #[test]
    fn seal_open_roundtrip_with_chaining() {
        let k = "b0175093fe7e51f8ac3cdeb42cd9f513";
        let (mut tx, mut rx) = (DirectionalCipher::from_auth_key(k).unwrap(), DirectionalCipher::from_auth_key(k).unwrap());
        for msg in [&b""[..], b"hello", &[7u8; 16][..], &[1u8; 33][..]] {
            let sealed = tx.seal(msg);
            assert_eq!(rx.open(&sealed).unwrap(), msg);
        }
    }

    /// MiPlayForWindows docs/miplay-research.md 记录的 S12 真机 0x1402 原始载荷：
    /// local 192.168.10.9:9970, peer 192.168.10.4:8899；key = IV = authKey 前半段，应解出 OPack cmd/authMsg。
    /// 06:10 UTC+8 实测：完整性校验通过，但用文档里记的端点派生出的 key 解出来是乱码 → 文档端点（:9970）大概率记错，
    /// 改为用真机握手验证（`miplay-cli safety IP`），这里保留样本备查。
    #[test]
    #[ignore]
    fn decrypt_captured_s12_safety_auth() {
        let k = derive_auth_key("192.168.10.4:8899".parse().unwrap(), "192.168.10.9:9970".parse().unwrap());
        let body = hex::decode("000701E0021F4C4CB50810D7D40C897EE73090514B08615C41BDAD39751CEEB7CCCD0A6BAC5B6DD2AE5EAC7A82635D5FD69B654537560166E1385F1BD610314059C6A3AF30C1DAE517").unwrap();
        let mut rx = DirectionalCipher::from_auth_key(&k).unwrap();
        let plain = rx.open(&body).unwrap();
        let (tag, t, json) = opack_unwrap(&plain).unwrap();
        assert_eq!((tag.as_str(), t), ("cmd", 30));
        assert_eq!(json_str(&json, "authMsg").unwrap().len(), 32);
    }

    #[test]
    fn selection_parse() {
        let s = SafetySelection::parse(br#"{"aesIvType":"2","aesKeyType":"1","authAlgorithmType":"4","authKeyType":"1","integrityType":"1","result":"0"}"#).unwrap();
        assert_eq!((s.result.as_str(), s.auth_algorithm_type, s.aes_iv_type), ("0", 4, 2));
    }

    #[test]
    fn opack() {
        let w = opack_wrap("cmd", br#"{"authMsg":"x"}"#).unwrap();
        let (k, t, d) = opack_unwrap(&w).unwrap();
        assert_eq!((k.as_str(), t, d.as_slice()), ("cmd", 30, &br#"{"authMsg":"x"}"#[..]));
    }
}
