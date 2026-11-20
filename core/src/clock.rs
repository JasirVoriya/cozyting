//! 统一单调时钟（微秒）。
//!
//! 关键约束：UDP 计时器应答、RTSP TIME_OFFSET、PCR 必须来自**同一个**单调时钟源，否则多音箱组同步失效。
//! 本模块使用 `std::time::Instant`（Android/Linux 上对应 `CLOCK_MONOTONIC`）提供进程内单调微秒时间戳。

use std::sync::OnceLock;
use std::time::Instant;

static EPOCH: OnceLock<Instant> = OnceLock::new();

/// 进程内单调微秒（从首次调用起算，+1s 避免出现 0 值被误判为未初始化）。
pub fn now_us() -> i64 {
    let epoch = EPOCH.get_or_init(Instant::now);
    epoch.elapsed().as_micros() as i64 + 1_000_000
}
