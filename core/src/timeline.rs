//! 组共享媒体时间线与 TIME_OFFSET。
//!
//! 时间线与同步规范：
//! - 媒体 PTS 从 0 起算（每 AU 推进 `1024 / 48000` 秒）；若实际交付时刻相对理论 PTS 落后超过 50µs，则只向前补偿修正量（不回退）；
//! - PES PTS 与 RTP timestamp 均采用 90kHz 时钟（`pts_us * 90 / 1000`）；
//! - MPEG-TS PCR 采用 27MHz 进程单调时钟（`now_us * 27`）；
//! - `TIME_OFFSET`：`offset = server_now_us - pts_us + playback_delay_us`（缺省 `1,000,000` µs），组内所有接收端共享同一 `TIME_OFFSET`；
//! - 发送端同时运行 UDP 计时器服务端，供接收端对齐单调时钟。

use std::sync::{Condvar, Mutex};

pub const SAMPLE_RATE: u64 = 48_000;
pub const SAMPLES_PER_AU: u64 = 1024;
pub const FIX_THRESHOLD_US: i64 = 50;
pub const DEFAULT_PLAYBACK_DELAY_US: i64 = 1_000_000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct OutputTimestamp {
    pub pts_90khz: u64,
    pub pcr_27mhz: u64,
}

pub fn encoder_pts_us(au_index: u64) -> i64 {
    (au_index * SAMPLES_PER_AU * 1_000_000 / SAMPLE_RATE) as i64
}

/// 组内唯一实例：每个 AU 只计算一次时间戳并广播给所有成员。
pub struct SharedTimeline {
    first_output_us: Option<i64>,
    correction_us: i64,
    last_index: Option<u64>,
    pub corrections: u64,
}

impl Default for SharedTimeline {
    fn default() -> Self {
        Self { first_output_us: None, correction_us: 0, last_index: None, corrections: 0 }
    }
}

impl SharedTimeline {
    /// `output_now_us`：该 AU 实际交付发送的时刻（clock::now_us）。
    pub fn stamp(&mut self, au_index: u64, output_now_us: i64) -> OutputTimestamp {
        debug_assert!(self.last_index.map_or(au_index == 0, |l| au_index == l + 1));
        let first = *self.first_output_us.get_or_insert(output_now_us);
        let mut pts = encoder_pts_us(au_index) + self.correction_us;
        let behind = (output_now_us - first) - pts;
        if behind > FIX_THRESHOLD_US {
            self.correction_us += behind;
            pts += behind;
            self.corrections += 1;
        }
        self.last_index = Some(au_index);
        OutputTimestamp {
            pts_90khz: (pts.max(0) as u64) * 90 / 1000,
            pcr_27mhz: (output_now_us.max(0) as u64 * 27) % ((1u64 << 33) * 300),
        }
    }

    /// 下一个 AU（序号 idx）的输出 PTS（µs，含已累计的前向修正）。
    pub fn next_pts_us(&self, idx: u64) -> i64 {
        encoder_pts_us(idx) + self.correction_us
    }

    /// 暂停恢复时重新锚定输出原点：让序号 idx 的 AU 在 `now_us` 交付时“恰好不落后”，
    /// 这样暂停时长不会被当作卡顿加进 PTS（PTS 连续，改由新的 TIME_OFFSET 吸收暂停时长）。
    pub fn reanchor(&mut self, idx: u64, now_us: i64) {
        self.first_output_us = Some(now_us - self.next_pts_us(idx));
    }
}

/// TIME_OFFSET：初始成员在屏障处一起取值，晚加入成员复用当前值。
pub struct SharedTimeOffset {
    value: Mutex<Option<i64>>,
    ready: Condvar,
    expected: usize,
    arrived: Mutex<usize>,
    pub playback_delay_us: i64,
}

impl SharedTimeOffset {
    pub fn new(initial_members: usize, playback_delay_us: i64) -> Self {
        Self { value: Mutex::new(None), ready: Condvar::new(), expected: initial_members, arrived: Mutex::new(0), playback_delay_us }
    }

    /// 每个初始成员在收到 RTSP PLAY 后调用；最后一个到达者决定数值（now + delay），其余阻塞等待。
    /// 注意：`TIME_OFFSET` 对应 “PTS=0 的播放时刻”，预热完成后才发送首帧。
    pub fn barrier(&self) -> i64 {
        let mut a = self.arrived.lock().unwrap();
        *a += 1;
        if *a >= self.expected {
            let v = crate::clock::now_us() + self.playback_delay_us;
            *self.value.lock().unwrap() = Some(v);
            self.ready.notify_all();
            return v;
        }
        drop(a);
        let mut g = self.value.lock().unwrap();
        while g.is_none() {
            g = self.ready.wait(g).unwrap();
        }
        g.unwrap()
    }

    pub fn current(&self) -> Option<i64> {
        *self.value.lock().unwrap()
    }

    /// 运行期更新（暂停恢复/传输停顿后）。是否以及如何下发给音箱 = 待抓包（方案 §9 C6）。
    pub fn update(&self, v: i64) {
        *self.value.lock().unwrap() = Some(v);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn pts_monotonic_and_forward_only_fix() {
        let mut t = SharedTimeline::default();
        let a = t.stamp(0, 1_000_000);
        assert_eq!(a.pts_90khz, 0);
        let b = t.stamp(1, 1_000_000 + 21_333);
        assert_eq!(b.pts_90khz, 21_333 * 90 / 1000);
        // 发送卡顿 300ms：PTS 被向前推，不回退
        let c = t.stamp(2, 1_000_000 + 342_666);
        assert!(c.pts_90khz >= 342_666 * 90 / 1000 - 1);
        assert_eq!(t.corrections, 1);
        let d = t.stamp(3, 1_000_000 + 350_000);
        assert!(d.pts_90khz > c.pts_90khz);
    }

    #[test]
    fn reanchor_keeps_pts_continuous_across_pause() {
        let mut t = SharedTimeline::default();
        for i in 0..100 { t.stamp(i, 1_000_000 + crate::timeline::encoder_pts_us(i)); }
        let next = t.next_pts_us(100);
        // 暂停 5 s 后恢复
        let resume_at = 1_000_000 + next + 5_000_000;
        t.reanchor(100, resume_at);
        let a = t.stamp(100, resume_at);
        assert_eq!(a.pts_90khz as i64, next * 90 / 1000);
        assert_eq!(t.corrections, 0);
    }
}
