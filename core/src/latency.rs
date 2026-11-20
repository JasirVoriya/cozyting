//! 发送端延迟估计（诊断用，非音箱协议 RTT）。
//!
//! # 测量定义
//!
//! - **kind = estimate**：用本机墙钟 + 共享 `TIME_OFFSET` + AU 捕获时间戳推算，
//!   **不是**音箱回传的协议实测往返，也不是扬声器内部缓冲实测。
//! - **capture_to_play_est_ms** = `(TIME_OFFSET_us + pts_us − capture_us) / 1000`
//!   其中 `capture_us` 为该 AU 进入发送端队列的时刻（App 路径 = `pushAac` 时刻；
//!   静音源 = 静音帧生成时刻）。包含：捕获后排队、编码后入队、发送调度、
//!   配置的 `playback_delay`（写入 TIME_OFFSET 的那段），以及起播 runway 造成的内容龄。
//! - **schedule_ahead_ms** = `(TIME_OFFSET_us + pts_us − now_us) / 1000`：
//!   相对「现在」，该 AU 按协议还应多久才到播放点（主要为剩余 play-ahead）。
//! - **未包含 / 未知**：单向网络传播时延、音箱内部 playout 缓冲、本机 AudioRecord
//!   硬件环缓在 stamp 之前的部分（未单独探针时不伪造成 0）。
//! - 缺失字段时 JSON 用 `null`，UI 显示「—」/「未知」，**禁止**用 0 或固定预算冒充实测。

use crate::timeline::{SAMPLES_PER_AU, SAMPLE_RATE};

/// 人类可读的测量说明（嵌入 stats JSON，供 App / 日志对照）。
pub const LATENCY_DEFINITION: &str = "estimate: capture_to_play_est_ms=(TIME_OFFSET+PTS-capture_stamp)/1000; \
includes capture_queue+encode_to_au+sender_schedule+configured_playback_delay(+startup_runway age); \
excludes speaker_playout_buffer+network_one_way (unknown). Not protocol RTT.";

/// 一帧 AAC-LC 48k 立体声时长（毫秒，约 21.333）。
pub const AU_DURATION_MS: f64 = (SAMPLES_PER_AU as f64) * 1000.0 / (SAMPLE_RATE as f64);

/// 起播 runway 折合毫秒（诊断预算用）。
pub fn startup_runway_ms(aus: usize) -> i64 {
    (aus as f64 * AU_DURATION_MS).round() as i64
}

/// 端到端估计（捕获戳 → 协议播放点）。任一前置条件不满足则 `None`（UI 显示 —）。
pub fn capture_to_play_est_ms(time_offset_us: i64, pts_us: i64, capture_us: i64) -> Option<i64> {
    if time_offset_us <= 0 || capture_us <= 0 {
        return None;
    }
    Some((time_offset_us + pts_us - capture_us) / 1000)
}

/// 相对墙钟的播放超前量。无 TIME_OFFSET 时 `None`。
pub fn schedule_ahead_ms(time_offset_us: i64, pts_us: i64, now_us: i64) -> Option<i64> {
    if time_offset_us <= 0 || now_us <= 0 {
        return None;
    }
    Some((time_offset_us + pts_us - now_us) / 1000)
}

/// 将可选毫秒格式化为诊断字符串；缺失为 "—"。
pub fn format_ms_or_dash(v: Option<i64>) -> String {
    match v {
        Some(ms) if ms >= 0 => format!("{ms}"),
        Some(ms) => format!("{ms}"), // 负值也如实显示（时钟回拨/异常），不改成 0
        None => "—".into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::session::STARTUP_RUNWAY_AUS;

    #[test]
    fn e2e_uses_offset_pts_and_capture() {
        // TIME_OFFSET=2_000_000, pts=0, capture=1_000_000 → 1000ms
        assert_eq!(capture_to_play_est_ms(2_000_000, 0, 1_000_000), Some(1000));
        // 含内容龄：capture 更早 → 更大
        assert_eq!(capture_to_play_est_ms(2_000_000, 0, 500_000), Some(1500));
        // 含 PTS 推进
        assert_eq!(capture_to_play_est_ms(2_000_000, 21333, 1_000_000), Some(1021));
    }

    #[test]
    fn e2e_unknown_when_missing_offset_or_capture() {
        assert_eq!(capture_to_play_est_ms(0, 0, 1_000_000), None);
        assert_eq!(capture_to_play_est_ms(2_000_000, 0, 0), None);
        assert_eq!(capture_to_play_est_ms(-1, 0, 1), None);
    }

    #[test]
    fn schedule_ahead_and_dash() {
        assert_eq!(schedule_ahead_ms(2_000_000, 0, 1_200_000), Some(800));
        assert_eq!(schedule_ahead_ms(0, 0, 1_000_000), None);
        assert_eq!(format_ms_or_dash(Some(850)), "850");
        assert_eq!(format_ms_or_dash(None), "—");
        // 禁止把未知显示成 0
        assert_ne!(format_ms_or_dash(None), "0");
    }

    #[test]
    fn runway_budget_matches_14_aus() {
        let ms = startup_runway_ms(STARTUP_RUNWAY_AUS);
        assert!((298..=300).contains(&ms), "got {ms}");
    }

    #[test]
    fn definition_states_estimate_not_rtt() {
        assert!(LATENCY_DEFINITION.contains("estimate"));
        assert!(LATENCY_DEFINITION.contains("Not protocol RTT"));
        assert!(LATENCY_DEFINITION.contains("playback_delay"));
    }
}
