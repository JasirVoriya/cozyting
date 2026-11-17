//! 8899 控制通道命令号。
//!
//! 已从源码确认（两端交叉一致）：
//! - 发送端视角：MiPlayForWindows `android/.../miplay/MiPlayControl.kt:64-94`
//! - 接收端视角：FusionPlay `src/FusionPlay.MiPlaySdk/src/protocol.rs:23-88`
//! 帧头第 2 字节（命令高字节）为“命名空间”：0x00 旧式/业务，0x14 Safety 包装，0x04 AloneMediaPlayer。

pub const OPEN: u16 = 0x0000;
pub const OPEN_ACK: u16 = 0x0001;
pub const CLOSE: u16 = 0x0002;
pub const PAUSE: u16 = 0x0004;
pub const PAUSE_ACK: u16 = 0x0005;
pub const RESUME: u16 = 0x0006;
pub const RESUME_ACK: u16 = 0x0007;
pub const SET_VOLUME: u16 = 0x000c;
pub const SET_VOLUME_ACK: u16 = 0x000d;
pub const GET_VOLUME: u16 = 0x000e;
pub const GET_VOLUME_ACK: u16 = 0x000f;
pub const SET_MEDIA_INFO: u16 = 0x0012;
pub const SET_MEDIA_INFO_ACK: u16 = 0x0013;
pub const GET_MEDIA_INFO: u16 = 0x0014;
pub const HEARTBEAT: u16 = 0x001a;
pub const HEARTBEAT_ACK: u16 = 0x001b;
pub const GET_STATE: u16 = 0x001c;
pub const GET_STATE_ACK: u16 = 0x001d;
pub const GET_DEVICE_INFO: u16 = 0x001e;
pub const GET_DEVICE_INFO_ACK: u16 = 0x001f;
pub const NOTIFY: u16 = 0x0022;
/// 立体声对声道（libaudiomirror-jni.so `CmdControl::setChannel` 0x1ba7cc：sendCmdPayload(0x16, &u8, 1)；
/// `getChannel` 0x1ba98c：sendCmdPayload(0x18, 无载荷)）。取值见 `CmdSessionControl.CHANNEL_*`：0=中置/立体声，1=左，2=右。
pub const SET_CHANNEL: u16 = 0x0016;
pub const SET_CHANNEL_ACK: u16 = 0x0017;
pub const GET_CHANNEL: u16 = 0x0018;
pub const GET_CHANNEL_ACK: u16 = 0x0019;
pub const CHANNEL_CENTER: u8 = 0;
pub const CHANNEL_FRONT_LEFT: u8 = 1;
pub const CHANNEL_FRONT_RIGHT: u8 = 2;
/// 接收端先发：16~17 位十进制数字串（FusionPlay 称 DEVICE_ID）
pub const LEGACY_CHALLENGE: u16 = 0x0028;
/// 发送端回：HMAC-SHA1 十六进制文本（FusionPlay 称 AUTH_ACK）
pub const LEGACY_CHALLENGE_ACK: u16 = 0x0029;
pub const GET_MIRROR_MODE: u16 = 0x0034;
pub const GET_MIRROR_MODE_ACK: u16 = 0x0035;
pub const NATIVE_VERSION: u16 = 0x0036;
pub const NATIVE_VERSION_ACK: u16 = 0x0037;
pub const SET_PLAY_SOURCE: u16 = 0x0040;
pub const SET_PLAY_SOURCE_ACK: u16 = 0x0041;
pub const SET_LOCAL_DEVICE_INFO: u16 = 0x0058;
pub const SET_LOCAL_DEVICE_INFO_ACK: u16 = 0x0059;
/// 现代链路：发送端下发媒体密钥 JSON {authKey, streamKey, streamIV}（FusionPlay protocol.rs:1393-1417）
pub const SET_MIRROR_KEY: u16 = 0x006c;
pub const SET_MIRROR_KEY_ACK: u16 = 0x006d;

// 现代 Safety 命名空间（outer=0x14）
pub const SAFETY_INFO: u16 = 0x1400;
pub const SAFETY_INFO_ACK: u16 = 0x1401;
pub const SAFETY_AUTH: u16 = 0x1402;
pub const SAFETY_AUTH_ACK: u16 = 0x1403;
