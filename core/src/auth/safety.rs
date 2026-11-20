//! 现代 SafetyAuth / SafetyData 协议层。
//!
//! 协议规范：
//! - `authKey` = `lowercase_hex(MD5(map(recvIP + recvPort + sendIP + sendPort)))`，其中 `map` 将数字 `'0'..'9'` 映射为 `'a'..'j'`（对端音箱 `:8899` 在前，本端地址在后）。
//! - AES-128-CBC 密钥 = `authKey` 前 16 个 ASCII 字节；初始 IV = 同一 16 字节；收发两个方向各自独立维护 CBC 链（下一帧 IV = 上一帧密文末 16 字节）。
//! - SafetyData 信封格式：`00 07 01 e0 | pad:u8 | integrity:u32be | ciphertext`（零填充，`pad ∈ 1..=16`）。
//! - `integrity` 校验：基于多项式 `0x04c11db7`、初值 `0xffffffff`、表项字节序翻转的 32 位校验值。
//! - OPack 内层包装：`tagLen:u8 | tag("cmd"/"ack") | valueType:u8(=30) | len:u32be | json`。
//! - SafetyAuth（`0x1402`/`0x1403`）：双方各自发送 `{"authMsg": <32hex随机>}`，对方回 `authMsgAck = hex(HMAC(authKey_ascii32, authMsg))`，哈希算法由 `0x1401` 的 `authAlgorithmType` 指定（1=MD5, 2=SHA1, 4=SHA256）。
//! - 本端发送 `0x1403` 时 `result` 字段须为 `"0"`；认证完成后所有 8899 控制帧均使用 SafetyData 信封加密，RTSP/RTP 媒体流保持独立明文连接。

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
    let endpoint_concat = format!("{}{}{}{}", receiver.ip(), receiver.port(), sender.ip(), sender.port());
    let mut mapped = Vec::with_capacity(endpoint_concat.len());
    for b in endpoint_concat.bytes() {
        mapped.push(if b.is_ascii_digit() { b - b'0' + b'a' } else { b });
    }
    hex::encode(Md5::digest(&mapped))
}

const fn build_integrity_table() -> [u32; 256] {
    let mut table = [0u32; 256];
    let mut i = 0usize;
    while i < 256 {
        let mut crc = (i as u32) << 24;
        let mut bit = 0;
        while bit < 8 {
            crc = if (crc & 0x8000_0000) != 0 {
                (crc << 1) ^ 0x04c1_1db7
            } else {
                crc << 1
            };
            bit += 1;
        }
        table[i] = crc.swap_bytes();
        i += 1;
    }
    table
}

const INTEGRITY_TABLE: [u32; 256] = build_integrity_table();

pub fn integrity(bytes: &[u8]) -> u32 {
    bytes.iter().fold(u32::MAX, |acc, &b| {
        let idx = ((acc as u8) ^ b) as usize;
        INTEGRITY_TABLE[idx] ^ (acc >> 8)
    })
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

/// OPack 安全命令内层编码：`tagLen:u8 | tag("cmd"/"ack") | valueType:u8(=30) | len:u32be | json`。
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

/// 本端挑战值：以当前微秒时间戳十进制串的 MD5 小写十六进制生成 32 字符随机挑战。
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

/// 现代媒体密钥（SetMirrorKey `0x006c` JSON：`authKey`/`streamKey`/`streamIV`，各 16 个 ASCII 字符）。
/// 媒体前缀加密：对每个 PES 的 ADTS 载荷前 `min(len, 256)` 向下取整到 16 字节的部分执行 AES-128-CBC（无填充）。
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

    /// 原地加密 ADTS 帧前缀。
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

    #[test]
    fn auth_key_derivation_maps_digits_and_hashes_md5() {
        let k1 = derive_auth_key("192.0.2.10:8899".parse().unwrap(), "192.0.2.20:40000".parse().unwrap());
        let k2 = derive_auth_key("192.0.2.10:8899".parse().unwrap(), "192.0.2.20:40000".parse().unwrap());
        let k3 = derive_auth_key("192.0.2.11:8899".parse().unwrap(), "192.0.2.20:40000".parse().unwrap());
        assert_eq!(k1.len(), 32);
        assert_eq!(k1, k2);
        assert_ne!(k1, k3);
    }

    #[test]
    fn seal_open_roundtrip_with_chaining() {
        let k = derive_auth_key("192.0.2.10:8899".parse().unwrap(), "192.0.2.20:40000".parse().unwrap());
        let (mut tx, mut rx) = (DirectionalCipher::from_auth_key(&k).unwrap(), DirectionalCipher::from_auth_key(&k).unwrap());
        for msg in [&b""[..], b"hello", &[7u8; 16][..], &[1u8; 33][..]] {
            let sealed = tx.seal(msg);
            assert_eq!(rx.open(&sealed).unwrap(), msg);
        }
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
