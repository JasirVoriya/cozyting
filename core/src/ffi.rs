//! Android JNI 薄插头（feature "jni"）：对应 Kotlin `com.miaocast.app.NativeBridge`。
//!
//! 仅负责 JNI 类型转换，全部状态、句柄管理与多箱并发协调均已下沉至 [`crate::engine`]
//! 与 [`crate::device_catalog`]，与 iOS C ABI (`ffi_c.rs`) 共享同一套实现。

#[cfg(feature = "jni")]
mod android {
    use crate::device_catalog;
    use crate::engine;
    use crate::update;
    use jni::objects::{JByteArray, JClass, JString};
    use jni::sys::{jboolean, jint, jlong, jstring};
    use jni::JNIEnv;

    fn jstr(env: &mut JNIEnv, s: &JString) -> String {
        env.get_string(s).map(|s| s.into()).unwrap_or_default()
    }

    fn out(env: &mut JNIEnv, s: String) -> jstring {
        env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_init(_e: JNIEnv, _c: JClass) {
        #[cfg(target_os = "android")]
        android_logger::init_once(
            android_logger::Config::default()
                .with_tag("MiaoCastCore")
                .with_max_level(log::LevelFilter::Info),
        );
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_discover(
        mut env: JNIEnv,
        _c: JClass,
        local_ip: JString,
        timeout_ms: jint,
    ) -> jstring {
        let ip = jstr(&mut env, &local_ip);
        out(&mut env, engine::discover_tsv(&ip, timeout_ms))
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_discoverJson(
        mut env: JNIEnv,
        _c: JClass,
        local_ip: JString,
        timeout_ms: jint,
        cached_json: JString,
    ) -> jstring {
        let ip = jstr(&mut env, &local_ip);
        let cached = jstr(&mut env, &cached_json);
        out(&mut env, engine::discover_json(&ip, timeout_ms, &cached))
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_discoverJsonEx(
        mut env: JNIEnv,
        _c: JClass,
        local_ip: JString,
        timeout_ms: jint,
        cached_json: JString,
        force_refresh_names: jboolean,
    ) -> jstring {
        let ip = jstr(&mut env, &local_ip);
        let cached = jstr(&mut env, &cached_json);
        out(
            &mut env,
            engine::discover_json_with_options(&ip, timeout_ms, &cached, force_refresh_names != 0),
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_routeApplyJson(
        mut env: JNIEnv,
        _c: JClass,
        devices_json: JString,
        action: JString,
        target: JString,
    ) -> jstring {
        let dj = jstr(&mut env, &devices_json);
        let act = jstr(&mut env, &action);
        let tgt = jstr(&mut env, &target);
        out(&mut env, device_catalog::route_apply_json(&dj, &act, &tgt))
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_probe(
        mut env: JNIEnv,
        _c: JClass,
        ip: JString,
    ) -> jstring {
        let s = jstr(&mut env, &ip);
        out(&mut env, engine::probe_json(&s))
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_create(
        _env: JNIEnv,
        _c: JClass,
        silent: jboolean,
    ) -> jlong {
        engine::create(silent != 0)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_connect(
        mut env: JNIEnv,
        _c: JClass,
        h: jlong,
        ips: JString,
        delay_ms: jint,
        premute: jint,
        volume_after_play: jint,
        ref_function: JString,
        source_name: JString,
    ) -> jstring {
        let ips = jstr(&mut env, &ips);
        let rf = jstr(&mut env, &ref_function);
        let sn = jstr(&mut env, &source_name);
        out(
            &mut env,
            engine::connect(h, &ips, delay_ms, premute, volume_after_play, &rf, &sn),
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_pushAac(
        env: JNIEnv,
        _c: JClass,
        h: jlong,
        au: JByteArray,
    ) -> jint {
        let Ok(bytes) = env.convert_byte_array(&au) else {
            return -3;
        };
        engine::push_aac(h, bytes)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_queueAddReceiver(
        mut env: JNIEnv,
        _c: JClass,
        h: jlong,
        ip: JString,
    ) -> jstring {
        let ip = jstr(&mut env, &ip);
        out(&mut env, engine::queue_add_receiver(h, &ip))
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_queueRemoveReceiver(
        mut env: JNIEnv,
        _c: JClass,
        h: jlong,
        ip: JString,
    ) -> jboolean {
        let ip = jstr(&mut env, &ip);
        engine::queue_remove_receiver(h, &ip) as jboolean
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_queueSetVolume(
        mut env: JNIEnv,
        _c: JClass,
        h: jlong,
        ip: JString,
        v: jint,
    ) -> jstring {
        let ip = jstr(&mut env, &ip);
        out(&mut env, engine::queue_set_volume(h, &ip, v))
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_setVolume(
        mut env: JNIEnv,
        _c: JClass,
        h: jlong,
        ip: JString,
        v: jint,
    ) -> jstring {
        let ip = jstr(&mut env, &ip);
        out(&mut env, engine::set_volume(h, &ip, v))
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_addReceiver(
        mut env: JNIEnv,
        _c: JClass,
        h: jlong,
        ip: JString,
    ) -> jstring {
        let ip = jstr(&mut env, &ip);
        out(&mut env, engine::add_receiver(h, &ip))
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_removeReceiver(
        mut env: JNIEnv,
        _c: JClass,
        h: jlong,
        ip: JString,
    ) {
        let ip = jstr(&mut env, &ip);
        engine::remove_receiver(h, &ip);
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_pause(
        mut env: JNIEnv,
        _c: JClass,
        h: jlong,
    ) -> jstring {
        out(&mut env, engine::pause(h))
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_resume(
        mut env: JNIEnv,
        _c: JClass,
        h: jlong,
    ) -> jstring {
        out(&mut env, engine::resume(h))
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_switchSource(
        mut env: JNIEnv,
        _c: JClass,
        h: jlong,
    ) -> jstring {
        out(&mut env, engine::switch_source(h))
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_setPlaybackDelay(
        mut env: JNIEnv,
        _c: JClass,
        h: jlong,
        delay_ms: jint,
    ) -> jstring {
        out(&mut env, engine::set_playback_delay(h, delay_ms))
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_debugDrop(
        mut env: JNIEnv,
        _c: JClass,
        h: jlong,
        ip: JString,
    ) -> jboolean {
        let ip = jstr(&mut env, &ip);
        engine::debug_drop(h, &ip) as jboolean
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_debugBlackhole(
        mut env: JNIEnv,
        _c: JClass,
        h: jlong,
        ip: JString,
        secs: jint,
    ) -> jboolean {
        let ip = jstr(&mut env, &ip);
        engine::debug_blackhole(h, &ip, secs) as jboolean
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_queryChannel(
        mut env: JNIEnv,
        _c: JClass,
        h: jlong,
    ) -> jstring {
        out(&mut env, engine::query_channel(h))
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_stats(
        mut env: JNIEnv,
        _c: JClass,
        h: jlong,
    ) -> jstring {
        out(&mut env, engine::stats_json(h))
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_setAuthMode(
        _e: JNIEnv,
        _c: JClass,
        mode: jint,
    ) {
        engine::set_auth_mode(mode);
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_safetyProbe(
        mut env: JNIEnv,
        _c: JClass,
        ip: JString,
    ) -> jstring {
        let ip = jstr(&mut env, &ip);
        out(&mut env, engine::safety_probe_json(&ip))
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_delayForFrequency(
        _e: JNIEnv,
        _c: JClass,
        mhz: jint,
    ) -> jint {
        engine::delay_for_frequency(mhz)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_stop(
        _e: JNIEnv,
        _c: JClass,
        h: jlong,
    ) {
        engine::stop(h);
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_compareVersions(
        mut env: JNIEnv,
        _c: JClass,
        a: JString,
        b: JString,
    ) -> jint {
        let sa = jstr(&mut env, &a);
        let sb = jstr(&mut env, &b);
        update::compare_versions(&sa, &sb)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miaocast_app_NativeBridge_evaluateUpdateJson(
        mut env: JNIEnv,
        _c: JClass,
        current_version: JString,
        channel: JString,
        platform: JString,
        abi: JString,
        os_version: JString,
        ignored_tag: JString,
        raw_response_json: JString,
    ) -> jstring {
        let cv = jstr(&mut env, &current_version);
        let ch = jstr(&mut env, &channel);
        let pl = jstr(&mut env, &platform);
        let ab = jstr(&mut env, &abi);
        let os = jstr(&mut env, &os_version);
        let ig = jstr(&mut env, &ignored_tag);
        let raw = jstr(&mut env, &raw_response_json);
        out(
            &mut env,
            update::evaluate_update_json(&cv, &ch, &pl, &ab, &os, &ig, &raw),
        )
    }
}
