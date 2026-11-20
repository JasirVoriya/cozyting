//! 8899 控制通道认证路由（两条链路不可混用）：
//! - `legacy`：`0x0028` 数字挑战 → `0x0029` HMAC-SHA1 应答，之后走明文 `$` 帧。
//! - `safety`：`0x1400` SafetyInfo 能力协商 → `0x1402`/`0x1403` SafetyAuth 双向 HMAC 互验 → SafetyData(AES-128-CBC) 加密业务帧。
//!   实测在 LX06/L17A 上：互验后走加密控制帧引导与 Open/RTSP，RTP 音频通道保持明文；本端 `0x1403` 的 `result` 必须为 `"0"`。

pub mod legacy;
pub mod safety;

/// 由能力探测/抓包决定走哪条链路。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AuthRoute {
    LegacyClear,
    Safety,
}
