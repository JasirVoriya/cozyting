//! 统一单调时钟（微秒）。
//!
//! 关键约束：UDP 计时器应答、RTSP TIME_OFFSET、PCR 必须来自**同一个**时钟，否则组同步失效。
//! 参考：MiPlayForWindows android `MiPlayRtsp.kt:324` 使用 `SystemClock.elapsedRealtimeNanos()/1000`
//! 同时用于计时器应答(`:251-269`)与 TIME_OFFSET(`:99-103`)。
//! 这里用 `std::time::Instant`（Android 上为 CLOCK_MONOTONIC，休眠时不走，投送期间设备不会休眠，可接受）。

use std::sync::OnceLock;
use std::time::Instant;

static EPOCH: OnceLock<Instant> = OnceLock::new();

/// 进程内单调微秒（从首次调用起算，+1s 避免出现 0 值被误判为未初始化）。
pub fn now_us() -> i64 {
    let epoch = EPOCH.get_or_init(Instant::now);
    epoch.elapsed().as_micros() as i64 + 1_000_000
}
