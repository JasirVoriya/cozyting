//! 认证。两条链路**不可混用**（MiPlayForWindows docs/miplay-windows-audio-source-transmitter.md:91-104）：
//! - `legacy`：0x0028 数字挑战 → 0x0029 HMAC-SHA1，之后明文 `$` 帧。官方手机 com.milink.service 12.4.8.13
//!   对 LX06/S12 实测走的就是这条（同文档 :7-19），且已被 Windows/Android 发送端真机放音验证。
//! - `safety`：0x1400 SafetyInfo / 0x1402 SafetyAuth 互验 / SafetyData(AES-128-CBC) 加密业务帧，
//!   以及 0x006c SetMirrorKey + 媒体 PES 前缀加密。
//!   06:10–06:12 UTC+8 本项目在 LX06/L17A 上跑通：互验 → 加密的 legacy 同款引导 → Open/RTSP → 明文 RTP 音频，
//!   两台同组 state=2、first-audiopcm、心跳 ACK 正常（evidence 15–18）。关键：本端 0x1403 的 result 必须是 "0"，发 "1" 音箱立即断开。
//!   未做：SetMirrorKey(0x006c) + 媒体加密（不发也能播放）。

pub mod legacy;
pub mod safety;

/// 由能力探测/抓包决定走哪条链路。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AuthRoute {
    LegacyClear,
    Safety,
}
