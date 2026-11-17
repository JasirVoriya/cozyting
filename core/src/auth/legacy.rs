//! 旧式明文挑战。
//!
//! 来源（已确认，两份独立实现一致）：
//! - MiPlayForWindows android `MiPlayControl.kt:509-517`
//! - MiPlayForWindows `src/DLNACast.Core/MiPlay/MiPlayLegacySafetyChallengeCodec.cs:14-21`
//! 算法：key = lowercase_hex(MD5("0.0.0.0"))（32 个 ASCII 字符作为 HMAC key），
//!       resp = lowercase_hex(HMAC-SHA1(key, challenge_ascii_digits))，作为 0x0029 载荷，序号与 0x0028 相同。

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
    fn shape() {
        let r = challenge_response(b"643009625691733");
        assert_eq!(r.len(), 40);
        assert!(r.iter().all(|c| c.is_ascii_hexdigit()));
    }

    /// 真机记录（MiPlayForWindows docs/miplay-research.md:108, 116-117）：三组向量全部复现。
    /// 注意：同文档 :126 的 `643009625691733 → 35044ee1…` 无法复现（疑似挑战数字记录有误），故未采用。
    #[test]
    fn matches_captured_vectors() {
        for (c, r) in [
            ("legacy-challenge", "1bfbbecf1244c16add4362959aa0ccc7b6e8a0c4"),
            ("6399403323514405", "1bf4ec8cd0686ec6f9f57729e453ef75907fc68e"),
            ("15056381675087147", "c32613f30b7ac82d45f59af376acf6c879dc2d30"),
        ] {
            assert_eq!(challenge_response(c.as_bytes()), r.as_bytes().to_vec());
        }
    }
}
