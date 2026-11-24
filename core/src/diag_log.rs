//! 跨端共享运行日志转发与异步轮转落盘模块（Android JNI 与 iOS C ABI 共用）。
//!
//! - Android 端（`target_os = "android"` + `feature = "jni"`）：同时转发给 `android_logger`
//!   （保持 `CozyTingCore` Logcat 输出不变）与后台异步文件落盘；
//! - iOS / 桌面 / 单测端：写入内存环形缓冲与可选的本地轮转文件；
//! - 所有文件 I/O 在独立后台线程经有界通道（`try_send`）执行，绝不阻塞音频或网络线程。

use log::{Level, LevelFilter, Log, Metadata, Record};
use std::collections::VecDeque;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::mpsc::{self, SyncSender};
use std::sync::{Mutex, Once, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

pub const DEFAULT_MAX_LOG_BYTES: u64 = 256 * 1024;
const MAX_RING_LINES: usize = 200;
const CHANNEL_CAPACITY: usize = 512;

enum LogCmd {
    WriteLine(String),
    Reconfigure { path: Option<PathBuf>, max_bytes: u64 },
    Clear { ack: SyncSender<()> },
}

struct DiagState {
    ring: Mutex<VecDeque<String>>,
    tx: SyncSender<LogCmd>,
    tz_offset_secs: AtomicI32,
}

static STATE: OnceLock<DiagState> = OnceLock::new();
static LOGGER_INIT: Once = Once::new();

#[cfg(all(target_os = "android", feature = "jni"))]
static ANDROID_LOGGER: OnceLock<android_logger::AndroidLogger> = OnceLock::new();

struct DiagLogger;

static GLOBAL_LOGGER: DiagLogger = DiagLogger;

fn state() -> &'static DiagState {
    STATE.get_or_init(|| {
        let (tx, rx) = mpsc::sync_channel::<LogCmd>(CHANNEL_CAPACITY);
        let _ = std::thread::Builder::new()
            .name("cozyting-log".to_string())
            .spawn(move || writer_loop(rx));
        DiagState {
            ring: Mutex::new(VecDeque::with_capacity(MAX_RING_LINES)),
            tx,
            tz_offset_secs: AtomicI32::new(0),
        }
    })
}

fn writer_loop(rx: mpsc::Receiver<LogCmd>) {
    let mut path: Option<PathBuf> = None;
    let mut max_bytes: u64 = DEFAULT_MAX_LOG_BYTES;

    while let Ok(cmd) = rx.recv() {
        match cmd {
            LogCmd::Reconfigure {
                path: new_path,
                max_bytes: new_max,
            } => {
                path = new_path;
                max_bytes = if new_max == 0 {
                    DEFAULT_MAX_LOG_BYTES
                } else {
                    new_max
                };
            }
            LogCmd::WriteLine(line) => {
                if let Some(ref p) = path {
                    append_rotating_line(p, max_bytes, &line);
                }
            }
            LogCmd::Clear { ack } => {
                if let Some(ref p) = path {
                    let _ = fs::remove_file(p);
                    let _ = fs::remove_file(rotated_path(p));
                }
                let _ = ack.try_send(());
            }
        }
    }
}

fn rotated_path(path: &Path) -> PathBuf {
    let mut s = path.as_os_str().to_os_string();
    s.push(".1");
    PathBuf::from(s)
}

fn append_rotating_line(path: &Path, max_bytes: u64, line: &str) {
    if let Some(parent) = path.parent() {
        if !parent.as_os_str().is_empty() {
            let _ = fs::create_dir_all(parent);
        }
    }
    if let Ok(meta) = fs::metadata(path) {
        if meta.len() >= max_bytes {
            let backup = rotated_path(path);
            let _ = fs::copy(path, &backup);
            let _ = fs::write(path, b"");
        }
    }
    if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(f, "{line}");
    }
}

fn level_char(level: Level) -> char {
    match level {
        Level::Error => 'E',
        Level::Warn => 'W',
        Level::Info => 'I',
        Level::Debug => 'D',
        Level::Trace => 'V',
    }
}

/// 将 Unix 毫秒时间戳 + 时区偏移（秒）格式化为 `YYYY-MM-DD HH:MM:SS.SSS`。
pub fn format_timestamp_ms(epoch_ms: i64, tz_offset_secs: i32) -> String {
    let local_ms = epoch_ms.saturating_add((tz_offset_secs as i64) * 1000);
    let total_secs = local_ms.div_euclid(1000);
    let millis = local_ms.rem_euclid(1000);
    let days = total_secs.div_euclid(86_400);
    let secs_of_day = total_secs.rem_euclid(86_400);
    let hour = secs_of_day / 3600;
    let minute = (secs_of_day % 3600) / 60;
    let second = secs_of_day % 60;
    let (year, month, day) = civil_from_days(days);
    format!(
        "{year:04}-{month:02}-{day:02} {hour:02}:{minute:02}:{second:02}.{millis:03}"
    )
}

/// Howard Hinnant 的无溢出公历换算算法（Unix epoch 1970-01-01 为第 0 天）。
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64; // [0, 146096]
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365; // [0, 399]
    let y = (yoe as i64) + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100); // [0, 365]
    let mp = (5 * doy + 2) / 153; // [0, 11]
    let d = doy - (153 * mp + 2) / 5 + 1; // [1, 31]
    let m = if mp < 10 { mp + 3 } else { mp - 9 }; // [1, 12]
    let year = if m <= 2 { y + 1 } else { y };
    (year, m as u32, d as u32)
}

fn now_epoch_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

pub(crate) fn record_formatted(level: Level, msg: &str) {
    let st = state();
    let ts = format_timestamp_ms(now_epoch_ms(), st.tz_offset_secs.load(Ordering::Relaxed));
    let single_line = msg.replace('\r', " ").replace('\n', " | ");
    let line = format!("{ts} {}/CozyTingCore: {single_line}", level_char(level));
    if let Ok(mut ring) = st.ring.lock() {
        while ring.len() >= MAX_RING_LINES {
            ring.pop_front();
        }
        ring.push_back(line.clone());
    }
    let _ = st.tx.try_send(LogCmd::WriteLine(line));
}

impl Log for DiagLogger {
    fn enabled(&self, metadata: &Metadata) -> bool {
        metadata.level() <= Level::Info
    }

    fn log(&self, record: &Record) {
        if !self.enabled(record.metadata()) {
            return;
        }
        #[cfg(all(target_os = "android", feature = "jni"))]
        if let Some(al) = ANDROID_LOGGER.get() {
            al.log(record);
        }
        let msg = format!("{}", record.args());
        record_formatted(record.level(), &msg);
    }

    fn flush(&self) {}
}

/// 初始化全局日志器（幂等，多次调用安全）。
pub fn init_logger() {
    let _ = state();
    LOGGER_INIT.call_once(|| {
        #[cfg(all(target_os = "android", feature = "jni"))]
        {
            let _ = ANDROID_LOGGER.set(android_logger::AndroidLogger::new(
                android_logger::Config::default()
                    .with_tag("CozyTingCore")
                    .with_max_level(LevelFilter::Info),
            ));
        }
        let _ = log::set_logger(&GLOBAL_LOGGER);
        log::set_max_level(LevelFilter::Info);
    });
}

/// 配置 Rust 核心层的本地轮转日志文件路径与时区偏移（秒）。
/// 传入空路径将停用文件写入（仍保留内存环形缓冲与 Logcat）。
pub fn configure_log_file(path: &str, max_bytes: u64, tz_offset_secs: i32) {
    init_logger();
    let st = state();
    st.tz_offset_secs.store(tz_offset_secs, Ordering::Relaxed);
    let trimmed = path.trim();
    let target = if trimmed.is_empty() {
        None
    } else {
        Some(PathBuf::from(trimmed))
    };
    let _ = st.tx.send(LogCmd::Reconfigure {
        path: target,
        max_bytes: if max_bytes == 0 {
            DEFAULT_MAX_LOG_BYTES
        } else {
            max_bytes
        },
    });
}

/// 返回内存环形缓冲中最近的核心日志（按换行拼接）。
pub fn recent_logs() -> String {
    let st = state();
    match st.ring.lock() {
        Ok(ring) => ring.iter().cloned().collect::<Vec<_>>().join("\n"),
        Err(_) => String::new(),
    }
}

/// 清空内存环形缓冲及已配置的落盘日志文件（等待后台写线程完成清理）。
pub fn clear_logs() {
    let st = state();
    if let Ok(mut ring) = st.ring.lock() {
        ring.clear();
    }
    let (ack_tx, ack_rx) = mpsc::sync_channel(1);
    if st.tx.send(LogCmd::Clear { ack: ack_tx }).is_ok() {
        let _ = ack_rx.recv_timeout(std::time::Duration::from_millis(500));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn format_timestamp_handles_epoch_and_tz_offset() {
        assert_eq!(format_timestamp_ms(0, 0), "1970-01-01 00:00:00.000");
        assert_eq!(format_timestamp_ms(0, 8 * 3600), "1970-01-01 08:00:00.000");
        // 2026-03-01 12:34:56.789 UTC = 1772368496789 ms
        assert_eq!(
            format_timestamp_ms(1_772_368_496_789, 0),
            "2026-03-01 12:34:56.789"
        );
    }

    #[test]
    fn append_rotating_line_rotates_when_exceeding_max_bytes() {
        let dir = std::env::temp_dir().join(format!(
            "cozyting_diag_log_test_{}",
            std::process::id()
        ));
        let _ = fs::remove_dir_all(&dir);
        let file = dir.join("core.log");
        let backup = rotated_path(&file);

        append_rotating_line(&file, 64, "2026-03-01 00:00:01.000 I/CozyTingCore: first line payload 1234567890");
        assert!(file.exists());
        assert!(!backup.exists());

        append_rotating_line(&file, 64, "2026-03-01 00:00:02.000 I/CozyTingCore: second line after rotation");
        assert!(backup.exists());
        let old_content = fs::read_to_string(&backup).unwrap();
        let new_content = fs::read_to_string(&file).unwrap();
        assert!(old_content.contains("first line payload"));
        assert!(new_content.contains("second line after rotation"));

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn ring_buffer_and_clear_work() {
        clear_logs();
        record_formatted(Level::Info, "hello core log");
        record_formatted(Level::Warn, "warn line\nnext");
        let snap = recent_logs();
        assert!(snap.contains("I/CozyTingCore: hello core log"));
        assert!(snap.contains("W/CozyTingCore: warn line | next"));
        clear_logs();
        assert!(recent_logs().is_empty());
    }
}
