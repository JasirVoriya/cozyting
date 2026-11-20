//! 旧式明文挑战（0x0028 → 0x0029）。
//!
//! 协议规范：
//! - 接收端连上 8899 后下发 12..=17 位十进制 ASCII 数字挑战（`0x0028`）；
//! - 发送端以 `lowercase_hex(MD5("0.0.0.0"))`（32 个 ASCII 字符）作为 HMAC-SHA1 密钥，
//!   对挑战字节计算 `lowercase_hex(HMAC-SHA1(key, challenge))`（40 字节十六进制文本），
//!   作为 `0x0029` 载荷回传，序号与 `0x0028` 保持一致。

use hmac::{Hmac, Mac};
use md5::{Digest, Md5};
use sha1::Sha1;

pub const NATIVE_HMAC_SEED: &str = "0.0.0.0";

pub fn is_valid_challenge(p: &[u8]) -> bool {
    (12..=17).contains(&p.len()) && p.iter().all(u8::is_ascii_digit)
}

pub fn challenge_response(challenge: &[u8]) -> Vec<u8> {
    let key = hex::encode(Md5::digest(NATIVE_HMAC_SEED.as_bytes()));
    let mut mac = Hmac::<Sha1>::new_from_slice(key.as_bytes()).expect("hmac key");
    mac.update(challenge);
    hex::encode(mac.finalize().into_bytes()).into_bytes()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn shape_and_validity() {
        assert!(is_valid_challenge(b"123456789012"));
        assert!(!is_valid_challenge(b"12345"));
        let r = challenge_response(b"1020304050607080");
        assert_eq!(r.len(), 40);
        assert!(r.iter().all(|c| c.is_ascii_hexdigit()));
    }

    #[test]
    fn deterministic_hmac_vectors() {
        let r1 = challenge_response(b"1000000000000001");
        let r2 = challenge_response(b"1000000000000001");
        let r3 = challenge_response(b"9999999999999999");
        assert_eq!(r1, r2);
        assert_ne!(r1, r3);
    }
}
