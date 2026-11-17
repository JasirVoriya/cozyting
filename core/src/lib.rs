//! MiPlay 发送端核心（骨架）。
//!
//! 角色划分（全部来自开源代码与其抓包文档，详见 MiPlay发送端方案.md）：
//! - 发送端（手机）= 8899 控制通道的 TCP **客户端**；
//! - 发送端 = WFD RTSP 的 **服务端**（音箱收到 Open 后反向连回手机，同一监听端口先后建立
//!   RTSP / 辅助 / 音频 三条 TCP 连接）；
//! - 发送端 = UDP 计时器 **服务端**（时钟主），音箱把自己的时钟对齐到手机；
//! - 组内所有音箱共享同一 AAC 帧序列、同一 PTS 时间线、同一 TIME_OFFSET。

pub mod clock;
pub mod commands;
pub mod frame;
pub mod discovery;
pub mod auth;
pub mod control;
pub mod rtsp;
pub mod clock_sync;
pub mod media;
pub mod timeline;
pub mod group;
pub mod netinfo;
pub mod session;
pub mod ffi;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("protocol: {0}")]
    Protocol(String),
    #[error("crypto: {0}")]
    Crypto(String),
    #[error("timeout: {0}")]
    Timeout(String),
    #[error("not implemented yet: {0}")]
    Unimplemented(&'static str),
}

pub type Result<T> = std::result::Result<T, Error>;

pub(crate) fn proto<T>(msg: impl Into<String>) -> Result<T> {
    Err(Error::Protocol(msg.into()))
}
