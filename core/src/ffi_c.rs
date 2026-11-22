//! 面向 iOS（Swift）及通用原生宿主的 C ABI 薄插头。
//!
//! 对应头文件：`core/include/miplay_core.h`。
//! 所有返回 `char *` 的函数均由 Rust 分配以 NUL 结尾的 UTF-8 字符串，调用方使用完毕后
//! 必须调用 [`miplay_string_free`] 释放。

use crate::device_catalog;
use crate::engine;
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

/// 释放所有 `miplay_*` 返回的堆字符串；允许传入 NULL（no-op）。
#[no_mangle]
pub unsafe extern "C" fn miplay_string_free(ptr: *mut c_char) {
    if !ptr.is_null() {
        let _ = CString::from_raw(ptr);
    }
}

/// 扫描局域网设备并融合本地缓存，返回结构化 JSON。
#[no_mangle]
pub unsafe extern "C" fn miplay_discover_json(
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
pub unsafe extern "C" fn miplay_discover_json_ex(
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
pub unsafe extern "C" fn miplay_discover_tsv(
    local_ip: *const c_char,
    timeout_ms: i32,
) -> *mut c_char {
    let ip = cstr_to_str(local_ip);
    into_c_string(engine::discover_tsv(ip, timeout_ms))
}

/// 驱动跨端共享路由互斥状态机；返回 `{"state":...,"effects":[...]}` JSON。
#[no_mangle]
pub unsafe extern "C" fn miplay_route_apply_json(
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
pub extern "C" fn miplay_delay_for_frequency(mhz: i32) -> i32 {
    engine::delay_for_frequency(mhz)
}

#[no_mangle]
pub extern "C" fn miplay_startup_runway_aus(delay_ms: i32) -> i32 {
    crate::latency::startup_runway_aus_for_delay_ms(delay_ms) as i32
}

/// 解析认证模式字符串（`"auto"`/`"legacy"`/`"safety"` → `0`/`1`/`2`，无法识别返回 `-1`）。
#[no_mangle]
pub unsafe extern "C" fn miplay_parse_auth_mode(raw: *const c_char) -> i32 {
    device_catalog::parse_auth_mode(cstr_to_str(raw)).unwrap_or(-1)
}

/// 设置全局认证模式（0=auto, 1=legacy, 2=safety），对后续 connect 生效。
#[no_mangle]
pub extern "C" fn miplay_set_auth_mode(mode: i32) {
    engine::set_auth_mode(mode);
}

#[no_mangle]
pub extern "C" fn miplay_engine_create(silent: bool) -> i64 {
    engine::create(silent)
}

#[no_mangle]
pub unsafe extern "C" fn miplay_engine_connect(
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

/// 推入一帧裸 AAC AU（48kHz 立体声，不含 ADTS 头）。返回 0 成功，负数为错误码。
#[no_mangle]
pub unsafe extern "C" fn miplay_engine_push_aac(
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
pub unsafe extern "C" fn miplay_engine_add_receiver(handle: i64, ip: *const c_char) -> *mut c_char {
    into_c_string(engine::queue_add_receiver(handle, cstr_to_str(ip)))
}

#[no_mangle]
pub unsafe extern "C" fn miplay_engine_remove_receiver(handle: i64, ip: *const c_char) -> bool {
    engine::queue_remove_receiver(handle, cstr_to_str(ip))
}

#[no_mangle]
pub unsafe extern "C" fn miplay_engine_set_volume(
    handle: i64,
    ip: *const c_char,
    volume: i32,
) -> *mut c_char {
    into_c_string(engine::queue_set_volume(handle, cstr_to_str(ip), volume))
}

#[no_mangle]
pub extern "C" fn miplay_engine_pause(handle: i64) -> *mut c_char {
    into_c_string(engine::pause(handle))
}

#[no_mangle]
pub extern "C" fn miplay_engine_resume(handle: i64) -> *mut c_char {
    into_c_string(engine::resume(handle))
}

#[no_mangle]
pub extern "C" fn miplay_engine_switch_source(handle: i64) -> *mut c_char {
    into_c_string(engine::switch_source(handle))
}

#[no_mangle]
pub extern "C" fn miplay_engine_set_playback_delay(handle: i64, delay_ms: i32) -> *mut c_char {
    into_c_string(engine::set_playback_delay(handle, delay_ms))
}

#[no_mangle]
pub extern "C" fn miplay_engine_stats_json(handle: i64) -> *mut c_char {
    into_c_string(engine::stats_json(handle))
}

#[no_mangle]
pub extern "C" fn miplay_engine_stop(handle: i64) {
    engine::stop(handle);
}

#[cfg(test)]
mod tests {
    use super::*;

    unsafe fn take_str(ptr: *mut c_char) -> String {
        assert!(!ptr.is_null());
        let s = CStr::from_ptr(ptr).to_string_lossy().into_owned();
        miplay_string_free(ptr);
        s
    }

    #[test]
    fn c_abi_lifecycle_queue_stats_and_route_work_end_to_end() {
        unsafe {
            assert_eq!(miplay_delay_for_frequency(5180), 800);
            assert_eq!(miplay_delay_for_frequency(2412), 1000);
            assert_eq!(miplay_startup_runway_aus(0), 14);
            assert_eq!(miplay_startup_runway_aus(400), 8);
            assert_eq!(miplay_startup_runway_aus(250), 5);
            assert_eq!(miplay_startup_runway_aus(150), 3);

            let mode_s = CString::new("safety").unwrap();
            assert_eq!(miplay_parse_auth_mode(mode_s.as_ptr()), 2);

            let h = miplay_engine_create(false);
            assert!(h > 0);
            let aac = [0x21u8, 0x10, 0x04, 0x60];
            assert_eq!(miplay_engine_push_aac(h, aac.as_ptr(), aac.len()), 0);
            assert_eq!(miplay_engine_push_aac(h, std::ptr::null(), 0), -3);

            let ip = CString::new("192.0.2.88").unwrap();
            let q = take_str(miplay_engine_add_receiver(h, ip.as_ptr()));
            assert!(q.contains("\"queued\":true"));
            let stats = take_str(miplay_engine_stats_json(h));
            assert!(stats.contains("\"ip\":\"192.0.2.88\""));
            assert!(stats.contains("\"ui_state\":\"connecting\""));
            assert!(miplay_engine_remove_receiver(h, ip.as_ptr()));

            let delay_res = take_str(miplay_engine_set_playback_delay(h, 400));
            assert!(delay_res.contains("\"error\":\"no session\""));

            miplay_engine_stop(h);
            assert_eq!(miplay_engine_push_aac(h, aac.as_ptr(), aac.len()), -1);

            let devs = CString::new(
                "[{\"id\":\"s1\",\"name\":\"Speaker\",\"kind\":\"SPEAKER\",\"castable\":true,\"selected\":false,\"protocol\":\"MIPLAY\"}]",
            )
            .unwrap();
            let act = CString::new("check").unwrap();
            let tgt = CString::new("s1").unwrap();
            let tr = take_str(miplay_route_apply_json(devs.as_ptr(), act.as_ptr(), tgt.as_ptr()));
            assert!(tr.contains("\"type\":\"start_cast\""));
        }
    }
}
