//! 本机 Wi-Fi 频段 → 播放延迟策略。
//!
//! 5 GHz / 6 GHz 频段（≥ 4900 MHz）默认采用 800 ms 播放缓冲延迟，2.4 GHz 或未知频段采用 1000 ms 播放缓冲延迟。
//! App 内由 Kotlin（`WifiManager`）传入频率；CLI / `app_process` 下尝试通过系统命令读取。

use std::process::Command;

pub const DELAY_5GHZ_US: i64 = 800_000;
pub const DELAY_DEFAULT_US: i64 = 1_000_000;

/// 频率（MHz）→ 播放延迟（µs）。4900 MHz 以上（5 GHz / 6 GHz）用 800 ms，其余（含未知）1000 ms。
pub fn playback_delay_for_freq(mhz: Option<u32>) -> i64 {
    match mhz {
        Some(f) if f >= 4900 => DELAY_5GHZ_US,
        _ => DELAY_DEFAULT_US,
    }
}

fn first_number_after(text: &str, key: &str) -> Option<u32> {
    let i = text.find(key)? + key.len();
    let digits: String = text[i..].chars().skip_while(|c| !c.is_ascii_digit()).take_while(|c| c.is_ascii_digit()).collect();
    digits.parse().ok().filter(|f: &u32| (2000..8000).contains(f))
}

fn run(cmd: &str, args: &[&str]) -> Option<String> {
    let o = Command::new(cmd).args(args).output().ok()?;
    o.status.success().then(|| String::from_utf8_lossy(&o.stdout).into_owned())
}

/// 尽力读取当前 Wi-Fi 频率：Android `cmd wifi status`（shell 身份可用）、Linux `iw dev <if> link`、`nmcli`。
pub fn wifi_frequency_mhz() -> Option<u32> {
    if let Some(t) = run("cmd", &["wifi", "status"]) {
        if let Some(f) = first_number_after(&t, "Frequency: ").or_else(|| first_number_after(&t, "frequency=")) { return Some(f) }
    }
    if let Some(t) = run("iw", &["dev"]) {
        for line in t.lines() {
            if let Some(ifname) = line.trim().strip_prefix("Interface ") {
                if let Some(l) = run("iw", &["dev", ifname, "link"]) {
                    if let Some(f) = first_number_after(&l, "freq: ") { return Some(f) }
                }
            }
        }
    }
    if let Some(t) = run("nmcli", &["-t", "-f", "ACTIVE,FREQ", "dev", "wifi"]) {
        for line in t.lines() {
            if let Some(rest) = line.strip_prefix("yes:") { if let Some(f) = first_number_after(rest, "") { return Some(f) } }
        }
    }
    None
}

/// 自动选择：读不到频率时按非 5 GHz 分支使用 1000 ms。
pub fn auto_playback_delay_us() -> (i64, Option<u32>) {
    let f = wifi_frequency_mhz();
    (playback_delay_for_freq(f), f)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bands() {
        assert_eq!(playback_delay_for_freq(Some(5805)), 800_000);
        assert_eq!(playback_delay_for_freq(Some(2437)), 1_000_000);
        assert_eq!(playback_delay_for_freq(Some(5955)), 800_000);
        assert_eq!(playback_delay_for_freq(None), 1_000_000);
        assert_eq!(first_number_after("mWifiInfo SSID: x, Frequency: 5805MHz", "Frequency: "), Some(5805));
        assert_eq!(first_number_after("\tfreq: 2412.0\n", "freq: "), Some(2412));
    }
}
