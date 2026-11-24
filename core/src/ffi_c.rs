//! 面向 iOS（Swift）及通用原生宿主的 C ABI 薄插头。
//!
//! 对应头文件：`core/include/cozyting_core.h`。
//! 所有返回 `char *` 的函数均由 Rust 分配以 NUL 结尾的 UTF-8 字符串，调用方使用完毕后
//! 必须调用 [`cozyting_string_free`] 释放。

use crate::device_catalog;
use crate::engine;
use crate::update;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;

unsafe fn cstr_to_str<'a>(ptr: *const c_char) -> &'a str {
    if ptr.is_null() {
        return "";
    }
    CStr::from_ptr(ptr).to_str().unwrap_or("")
}

fn into_c_string(s: String) -> *mut c_char {
    // 清理潜在内部 NUL 字节，确保 CString::new 绝不失败
    let sanitized = if s.as_bytes().contains(&0) {
        s.replace('\0', "")
    } else {
        s
    };
    CString::new(sanitized)
        .map(CString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

/// 释放所有 `cozyting_*` 返回的堆字符串；允许传入 NULL（no-op）。
#[no_mangle]
pub unsafe extern "C" fn cozyting_string_free(ptr: *mut c_char) {
    if !ptr.is_null() {
        let _ = CString::from_raw(ptr);
    }
}

/// 扫描局域网设备并融合本地缓存，返回结构化 JSON。
#[no_mangle]
pub unsafe extern "C" fn cozyting_discover_json(
    local_ip: *const c_char,
    timeout_ms: i32,
    cached_json: *const c_char,
) -> *mut c_char {
    let ip = cstr_to_str(local_ip);
    let cached = cstr_to_str(cached_json);
    into_c_string(engine::discover_json(ip, timeout_ms, cached))
}

/// 扫描局域网设备并融合本地缓存，按需并发只读查询 8899 `miName`/`roomName`，返回结构化 JSON。
#[no_mangle]
pub unsafe extern "C" fn cozyting_discover_json_ex(
    local_ip: *const c_char,
    timeout_ms: i32,
    cached_json: *const c_char,
    force_refresh_names: bool,
) -> *mut c_char {
    let ip = cstr_to_str(local_ip);
    let cached = cstr_to_str(cached_json);
    into_c_string(engine::discover_json_with_options(
        ip,
        timeout_ms,
        cached,
        force_refresh_names,
    ))
}

/// 扫描局域网设备，返回 6 列 TSV（兼容调试与轻量调用）。
#[no_mangle]
pub unsafe extern "C" fn cozyting_discover_tsv(
    local_ip: *const c_char,
    timeout_ms: i32,
) -> *mut c_char {
    let ip = cstr_to_str(local_ip);
    into_c_string(engine::discover_tsv(ip, timeout_ms))
}

/// 驱动跨端共享路由互斥状态机；返回 `{"state":...,"effects":[...]}` JSON。
#[no_mangle]
pub unsafe extern "C" fn cozyting_route_apply_json(
    devices_json: *const c_char,
    action: *const c_char,
    target: *const c_char,
) -> *mut c_char {
    let dj = cstr_to_str(devices_json);
    let act = cstr_to_str(action);
    let tgt = cstr_to_str(target);
    into_c_string(device_catalog::route_apply_json(dj, act, tgt))
}

/// Wi-Fi 频段（MHz）→ 推荐播放缓冲延迟（ms）。
#[no_mangle]
pub extern "C" fn cozyting_delay_for_frequency(mhz: i32) -> i32 {
    engine::delay_for_frequency(mhz)
}

#[no_mangle]
pub extern "C" fn cozyting_startup_runway_aus(delay_ms: i32) -> i32 {
    crate::latency::startup_runway_aus_for_delay_ms(delay_ms) as i32
}

/// 解析认证模式字符串（`"auto"`/`"legacy"`/`"safety"` → `0`/`1`/`2`，无法识别返回 `-1`）。
#[no_mangle]
pub unsafe extern "C" fn cozyting_parse_auth_mode(raw: *const c_char) -> i32 {
    device_catalog::parse_auth_mode(cstr_to_str(raw)).unwrap_or(-1)
}

/// 设置全局认证模式（0=auto, 1=legacy, 2=safety），对后续 connect 生效。
#[no_mangle]
pub extern "C" fn cozyting_set_auth_mode(mode: i32) {
    engine::set_auth_mode(mode);
}

/// 设置全局音量过大保护策略（默认开启、上限 60%，合法范围 30..=90）。
#[no_mangle]
pub extern "C" fn cozyting_set_volume_guard_policy(enabled: bool, safe_limit: i32) {
    engine::set_volume_guard_policy(enabled, safe_limit);
}

/// 裁决一次音量变更是否需要二次确认（仅在跨过安全上限线时返回 `require_confirm`）。
#[no_mangle]
pub extern "C" fn cozyting_volume_guard_evaluate_json(
    enabled: bool,
    safe_limit: i32,
    current_volume: i32,
    target_volume: i32,
) -> *mut c_char {
    into_c_string(engine::evaluate_volume_change_json(
        enabled,
        safe_limit,
        current_volume,
        target_volume,
    ))
}

#[no_mangle]
pub extern "C" fn cozyting_engine_create(silent: bool) -> i64 {
    engine::create(silent)
}

#[no_mangle]
pub unsafe extern "C" fn cozyting_engine_connect(
    handle: i64,
    ips: *const c_char,
    delay_ms: i32,
    premute: i32,
    volume_after_play: i32,
    ref_function: *const c_char,
    source_name: *const c_char,
) -> *mut c_char {
    let ips = cstr_to_str(ips);
    let rf = cstr_to_str(ref_function);
    let sn = cstr_to_str(source_name);
    into_c_string(engine::connect(
        handle,
        ips,
        delay_ms,
        premute,
        volume_after_play,
        rf,
        sn,
    ))
}

#[no_mangle]
pub unsafe extern "C" fn cozyting_engine_connect_ex(
    handle: i64,
    ips: *const c_char,
    delay_ms: i32,
    premute: i32,
    volume_after_play: i32,
    ref_function: *const c_char,
    source_name: *const c_char,
    volume_confirmed: bool,
) -> *mut c_char {
    let ips = cstr_to_str(ips);
    let rf = cstr_to_str(ref_function);
    let sn = cstr_to_str(source_name);
    into_c_string(engine::connect_with_volume_confirmed(
        handle,
        ips,
        delay_ms,
        premute,
        volume_after_play,
        rf,
        sn,
        volume_confirmed,
    ))
}

/// 推入一帧裸 AAC AU（48kHz 立体声，不含 ADTS 头）。返回 0 成功，负数为错误码。
#[no_mangle]
pub unsafe extern "C" fn cozyting_engine_push_aac(
    handle: i64,
    data: *const u8,
    len: usize,
) -> i32 {
    if data.is_null() || len == 0 {
        return -3;
    }
    let slice = std::slice::from_raw_parts(data, len);
    engine::push_aac(handle, slice.to_vec())
}

#[no_mangle]
pub unsafe extern "C" fn cozyting_engine_add_receiver(handle: i64, ip: *const c_char) -> *mut c_char {
    into_c_string(engine::queue_add_receiver(handle, cstr_to_str(ip)))
}

#[no_mangle]
pub unsafe extern "C" fn cozyting_engine_remove_receiver(handle: i64, ip: *const c_char) -> bool {
    engine::queue_remove_receiver(handle, cstr_to_str(ip))
}

#[no_mangle]
pub unsafe extern "C" fn cozyting_engine_set_volume(
    handle: i64,
    ip: *const c_char,
    volume: i32,
) -> *mut c_char {
    into_c_string(engine::queue_set_volume(handle, cstr_to_str(ip), volume))
}

#[no_mangle]
pub unsafe extern "C" fn cozyting_engine_set_volume_checked(
    handle: i64,
    ip: *const c_char,
    volume: i32,
    confirmed: bool,
) -> *mut c_char {
    into_c_string(engine::queue_set_volume_checked(
        handle,
        cstr_to_str(ip),
        volume,
        confirmed,
    ))
}

#[no_mangle]
pub extern "C" fn cozyting_engine_pause(handle: i64) -> *mut c_char {
    into_c_string(engine::pause(handle))
}

#[no_mangle]
pub extern "C" fn cozyting_engine_resume(handle: i64) -> *mut c_char {
    into_c_string(engine::resume(handle))
}

#[no_mangle]
pub extern "C" fn cozyting_engine_switch_source(handle: i64) -> *mut c_char {
    into_c_string(engine::switch_source(handle))
}

#[no_mangle]
pub extern "C" fn cozyting_engine_set_playback_delay(handle: i64, delay_ms: i32) -> *mut c_char {
    into_c_string(engine::set_playback_delay(handle, delay_ms))
}

#[no_mangle]
pub extern "C" fn cozyting_engine_stats_json(handle: i64) -> *mut c_char {
    into_c_string(engine::stats_json(handle))
}

#[no_mangle]
pub extern "C" fn cozyting_engine_stop(handle: i64) {
    engine::stop(handle);
}

/// 比较两个版本号字符串（自动剥离前导 `v` 与 `-release`/`-acceptance`/`-debug` 后缀）。
/// 返回值：`-1` (a < b), `0` (a == b), `1` (a > b), `-2` (非法格式)。
#[no_mangle]
pub unsafe extern "C" fn cozyting_compare_versions(a: *const c_char, b: *const c_char) -> i32 {
    update::compare_versions(cstr_to_str(a), cstr_to_str(b))
}

/// 跨端更新裁决与跨版本区间日志聚合入口；返回归一化决策 JSON。
#[no_mangle]
pub unsafe extern "C" fn cozyting_update_evaluate_json(
    current_version: *const c_char,
    channel: *const c_char,
    platform: *const c_char,
    abi: *const c_char,
    os_version: *const c_char,
    ignored_tag: *const c_char,
    raw_response_json: *const c_char,
) -> *mut c_char {
    into_c_string(update::evaluate_update_json(
        cstr_to_str(current_version),
        cstr_to_str(channel),
        cstr_to_str(platform),
        cstr_to_str(abi),
        cstr_to_str(os_version),
        cstr_to_str(ignored_tag),
        cstr_to_str(raw_response_json),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    unsafe fn take_str(ptr: *mut c_char) -> String {
        assert!(!ptr.is_null());
        let s = CStr::from_ptr(ptr).to_string_lossy().into_owned();
        cozyting_string_free(ptr);
        s
    }

    #[test]
    fn c_abi_lifecycle_queue_stats_and_route_work_end_to_end() {
        unsafe {
            assert_eq!(cozyting_delay_for_frequency(5180), 800);
            assert_eq!(cozyting_delay_for_frequency(2412), 1000);
            assert_eq!(cozyting_startup_runway_aus(0), 14);
            assert_eq!(cozyting_startup_runway_aus(400), 8);
            assert_eq!(cozyting_startup_runway_aus(250), 5);
            assert_eq!(cozyting_startup_runway_aus(150), 3);

            let mode_s = CString::new("safety").unwrap();
            assert_eq!(cozyting_parse_auth_mode(mode_s.as_ptr()), 2);

            let h = cozyting_engine_create(false);
            assert!(h > 0);
            let aac = [0x21u8, 0x10, 0x04, 0x60];
            assert_eq!(cozyting_engine_push_aac(h, aac.as_ptr(), aac.len()), 0);
            assert_eq!(cozyting_engine_push_aac(h, std::ptr::null(), 0), -3);

            let ip = CString::new("192.0.2.88").unwrap();
            let q = take_str(cozyting_engine_add_receiver(h, ip.as_ptr()));
            assert!(q.contains("\"queued\":true"));
            let stats = take_str(cozyting_engine_stats_json(h));
            assert!(stats.contains("\"ip\":\"192.0.2.88\""));
            assert!(stats.contains("\"ui_state\":\"connecting\""));
            assert!(cozyting_engine_remove_receiver(h, ip.as_ptr()));

            let delay_res = take_str(cozyting_engine_set_playback_delay(h, 400));
            assert!(delay_res.contains("\"error\":\"no session\""));

            cozyting_engine_stop(h);
            assert_eq!(cozyting_engine_push_aac(h, aac.as_ptr(), aac.len()), -1);

            let devs = CString::new(
                "[{\"id\":\"s1\",\"name\":\"Speaker\",\"kind\":\"SPEAKER\",\"castable\":true,\"selected\":false,\"protocol\":\"MIPLAY\"}]",
            )
            .unwrap();
            let act = CString::new("check").unwrap();
            let tgt = CString::new("s1").unwrap();
            let tr = take_str(cozyting_route_apply_json(devs.as_ptr(), act.as_ptr(), tgt.as_ptr()));
            assert!(tr.contains("\"type\":\"start_cast\""));

            let va = CString::new("0.9.6-release").unwrap();
            let vb = CString::new("v0.9.7").unwrap();
            assert_eq!(cozyting_compare_versions(va.as_ptr(), vb.as_ptr()), -1);

            let ch = CString::new("stable").unwrap();
            let pl = CString::new("ios").unwrap();
            let ab = CString::new("").unwrap();
            let os = CString::new("17.4").unwrap();
            let ig = CString::new("").unwrap();
            let raw = CString::new(
                "{\"schema_version\":1,\"channels\":{\"stable\":{\"tag\":\"v0.9.7\",\"prerelease\":false,\"release_url\":\"https://github.com/JasirVoriya/cozyting-releases/releases/tag/v0.9.7\",\"notes\":\"- 新功能\",\"ios\":{\"min_os\":\"16.0\"}}}}",
            )
            .unwrap();
            let dec = take_str(cozyting_update_evaluate_json(
                va.as_ptr(),
                ch.as_ptr(),
                pl.as_ptr(),
                ab.as_ptr(),
                os.as_ptr(),
                ig.as_ptr(),
                raw.as_ptr(),
            ));
            assert!(dec.contains("\"status\":\"update_available\""));
            assert!(dec.contains("\"latest_tag\":\"v0.9.7\""));

            cozyting_set_volume_guard_policy(true, 60);
            let vg_cross = take_str(cozyting_volume_guard_evaluate_json(true, 60, 40, 85));
            assert!(vg_cross.contains("\"decision\":\"require_confirm\""));
            let vg_high = take_str(cozyting_volume_guard_evaluate_json(true, 60, 75, 85));
            assert!(vg_high.contains("\"decision\":\"allow\""));
            let vh = cozyting_engine_create(true);
            let vq = take_str(cozyting_engine_set_volume_checked(vh, ip.as_ptr(), 85, true));
            assert!(vq.contains("\"volume\":85"));
            assert!(vq.contains("\"clamped\":false"));
            cozyting_engine_stop(vh);
        }
    }
}
