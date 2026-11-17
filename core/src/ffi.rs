//! JNI 桥（feature "jni"）：Kotlin `com.miplay.sender.NativeBridge`。
//! 句柄 = Box<Engine> 指针（jlong）。所有函数都可在任意线程调用；start 会阻塞直到所有音箱就绪或失败。

#[cfg(feature = "jni")]
mod android {
    use crate::control::SourceIdentity;
    use crate::session::{CastConfig, CastSession, ChannelSource, SilenceSource, AuSource};
    use jni::objects::{JByteArray, JClass, JString};
    use jni::sys::{jboolean, jint, jlong, jstring};
    use jni::JNIEnv;
    use std::net::Ipv4Addr;
    use std::sync::mpsc::{sync_channel, SyncSender};
    use std::sync::{Arc, Mutex};

    pub struct Engine {
        session: Mutex<Option<Arc<CastSession>>>,
        feed: Option<SyncSender<Vec<u8>>>,
        source: Mutex<Option<Box<dyn AuSource>>>,
        pub last_error: Mutex<String>,
    }

    fn jstr(env: &mut JNIEnv, s: &JString) -> String {
        env.get_string(s).map(|s| s.into()).unwrap_or_default()
    }
    fn out(env: &mut JNIEnv, s: String) -> jstring {
        env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miplay_sender_NativeBridge_init(_e: JNIEnv, _c: JClass) {
        #[cfg(target_os = "android")]
        android_logger::init_once(android_logger::Config::default().with_tag("MiPlayCore").with_max_level(log::LevelFilter::Info));
    }

    /// 返回每行一个 `ip\tname\tmodel` 的发现结果（_mi-connect._udp）。
    #[no_mangle]
    pub extern "system" fn Java_com_miplay_sender_NativeBridge_discover(mut env: JNIEnv, _c: JClass, local_ip: JString, timeout_ms: jint) -> jstring {
        let local: Ipv4Addr = jstr(&mut env, &local_ip).parse().unwrap_or(Ipv4Addr::UNSPECIFIED);
        let r = match crate::discovery::browse(local, std::time::Duration::from_millis(timeout_ms.max(500) as u64)) {
            Ok(mut list) => {
                // 声明 apps=[5]（MiPlay）的排在前面；同一 IP 只保留一条
                list.sort_by_key(|s| !s.advertises_miplay());
                let mut seen = std::collections::HashSet::new();
                list.iter().filter_map(|s| s.ipv4.map(|ip| (ip, s)))
                    .filter(|(ip, _)| seen.insert(*ip))
                    .map(|(ip, s)| format!("{ip}\t{}\t{}\t{}", s.display_name(), s.model().unwrap_or_default(), s.advertises_miplay() as u8))
                    .collect::<Vec<_>>().join("\n")
            }
            Err(e) => format!("ERROR\t{e}"),
        };
        out(&mut env, r)
    }

    /// 只读状态（不改变任何东西）。返回 JSON。
    #[no_mangle]
    pub extern "system" fn Java_com_miplay_sender_NativeBridge_probe(mut env: JNIEnv, _c: JClass, ip: JString) -> jstring {
        let s = jstr(&mut env, &ip);
        let r = match s.parse::<Ipv4Addr>() {
            Ok(ip) => match crate::session::probe(ip, &SourceIdentity::default()) { Ok(st) => st.to_json(), Err(e) => format!("{{\"error\":\"{e}\"}}") },
            Err(_) => "{\"error\":\"bad ip\"}".into(),
        };
        out(&mut env, r)
    }

    /// 创建引擎（立即返回）。silent=true 用内置 AAC 静音帧；否则通过 pushAac 喂 AU。
    #[no_mangle]
    pub extern "system" fn Java_com_miplay_sender_NativeBridge_create(_env: JNIEnv, _c: JClass, silent: jboolean) -> jlong {
        let (source, feed): (Box<dyn AuSource>, Option<SyncSender<Vec<u8>>>) = if silent != 0 {
            (Box::new(SilenceSource { remaining: None }), None)
        } else {
            let (tx, rx) = sync_channel(512);
            (Box::new(ChannelSource(rx)), Some(tx))
        };
        let engine = Box::new(Engine { session: Mutex::new(None), feed, source: Mutex::new(Some(source)), last_error: Mutex::new(String::new()) });
        Box::into_raw(engine) as jlong
    }

    /// 连接音箱并开始推流（阻塞直到就绪/失败）。ips 逗号分隔。
    /// delayMs ≤ 0 = 自动（按 Wi-Fi 频段：5 GHz 800 ms，其他 1000 ms；App 应传 Kotlin 侧算好的值）。
    /// premute ≥ 0：推流前先用独立 8899 会话把音量设为该值；volumeAfterPlay ≥ 0：PLAY 后对每台（含中途加入/重连）再设一次。
    #[no_mangle]
    pub extern "system" fn Java_com_miplay_sender_NativeBridge_connect(
        mut env: JNIEnv, _c: JClass, h: jlong, ips: JString, delay_ms: jint, premute: jint, volume_after_play: jint,
        ref_function: JString, source_name: JString,
    ) -> jstring {
        let ips: Vec<Ipv4Addr> = jstr(&mut env, &ips).split(',').filter_map(|s| s.trim().parse().ok()).collect();
        let mut id = SourceIdentity::default();
        let rf = jstr(&mut env, &ref_function);
        id.ref_function = if !rf.is_empty() { rf } else if ips.len() > 1 { "multi_room".into() } else { "single_room".into() };
        let sn = jstr(&mut env, &source_name);
        if !sn.is_empty() { id.source_name = sn; }
        let (delay_us, freq) = if delay_ms > 0 { (delay_ms.clamp(200, 3000) as i64 * 1000, None) } else { crate::netinfo::auto_playback_delay_us() };
        let auth = match AUTH_MODE.load(std::sync::atomic::Ordering::SeqCst) {
            1 => crate::session::AuthMode::Legacy,
            2 => crate::session::AuthMode::Safety,
            _ => crate::session::AuthMode::Auto,
        };
        log::info!(
            "connect auth_mode_requested={} ref_function={} source_name_len={} delay_us={delay_us} wifi_freq={freq:?}",
            auth.as_str(),
            id.ref_function,
            id.source_name.len()
        );
        let cfg = CastConfig {
            identity: id,
            playback_delay_us: delay_us,
            premute: (premute >= 0).then(|| premute.min(100) as u8),
            volume_after_play: (volume_after_play >= 0).then(|| volume_after_play.min(100) as u8),
            auth,
            ..Default::default()
        };
        let Some(e) = engine(h) else { return out(&mut env, "{\"error\":\"no engine\"}".into()) };
        let Some(source) = e.source.lock().unwrap().take() else { return out(&mut env, "{\"error\":\"already started\"}".into()) };
        let r = match CastSession::start(&ips, source, cfg) {
            Ok((s, init)) => {
                for st in init { log::info!("initial {}", st.to_json()); }
                let j = s.stats_json();
                *e.session.lock().unwrap() = Some(Arc::new(s));
                j
            }
            Err(err) => {
                log::error!("start failed: {err}");
                *e.last_error.lock().unwrap() = err.to_string();
                format!("{{\"error\":{:?}}}", err.to_string())
            }
        };
        out(&mut env, r)
    }

    fn engine<'a>(h: jlong) -> Option<&'a Engine> {
        if h == 0 { return None }
        unsafe { (h as *const Engine).as_ref() }
    }
    /// 取出会话的 Arc 后立即释放锁，长时间调用（加入音箱、设音量）不会挡住 stats。
    fn session(h: jlong) -> Option<Arc<CastSession>> {
        engine(h).and_then(|e| e.session.lock().unwrap().clone())
    }
    fn err_json(e: impl std::fmt::Display) -> String { format!("{{\"error\":{:?}}}", e.to_string()) }

    #[no_mangle]
    pub extern "system" fn Java_com_miplay_sender_NativeBridge_pushAac(env: JNIEnv, _c: JClass, h: jlong, au: JByteArray) -> jint {
        let Some(e) = engine(h) else { return -1 };
        let Some(feed) = &e.feed else { return -2 };
        let Ok(bytes) = env.convert_byte_array(&au) else { return -3 };
        match feed.try_send(bytes) { Ok(()) => 0, Err(_) => -4 }
    }

    /// ip 为空 = 全组。返回 "ip:Ok(v);ip:Err(..)"。
    #[no_mangle]
    pub extern "system" fn Java_com_miplay_sender_NativeBridge_setVolume(mut env: JNIEnv, _c: JClass, h: jlong, ip: JString, v: jint) -> jstring {
        let ip = jstr(&mut env, &ip).parse::<Ipv4Addr>().ok();
        let r = session(h).map(|s| s.set_volume(ip, v.clamp(0, 100) as u8))
            .map(|v| v.iter().map(|(ip, r)| format!("{ip}:{r:?}")).collect::<Vec<_>>().join(";"))
            .unwrap_or_else(|| "no session".into());
        out(&mut env, r)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miplay_sender_NativeBridge_addReceiver(mut env: JNIEnv, _c: JClass, h: jlong, ip: JString) -> jstring {
        let ip = jstr(&mut env, &ip).parse::<Ipv4Addr>();
        let r = match (session(h), ip) {
            (Some(s), Ok(ip)) => match s.add_receiver(ip) { Ok(st) => st.to_json(), Err(err) => err_json(err) },
            (None, _) => err_json("no session"),
            _ => err_json("bad ip"),
        };
        out(&mut env, r)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miplay_sender_NativeBridge_removeReceiver(mut env: JNIEnv, _c: JClass, h: jlong, ip: JString) {
        if let (Some(s), Ok(ip)) = (session(h), jstr(&mut env, &ip).parse::<Ipv4Addr>()) { s.remove_receiver(ip) }
    }

    /// 暂停全组：停发媒体 + 每台 8899 Pause。返回 "ip:Ok(seq);..."。
    #[no_mangle]
    pub extern "system" fn Java_com_miplay_sender_NativeBridge_pause(mut env: JNIEnv, _c: JClass, h: jlong) -> jstring {
        let r = session(h).map(|s| s.pause().iter().map(|(ip, r)| format!("{ip}:{r:?}")).collect::<Vec<_>>().join(";"))
            .unwrap_or_else(|| "no session".into());
        out(&mut env, r)
    }

    /// 恢复全组：运行期 TIME_OFFSET（全员同值）→ Resume ×2 → 恢复发送。返回新 offset 的 JSON。
    #[no_mangle]
    pub extern "system" fn Java_com_miplay_sender_NativeBridge_debugBlackhole(mut env: JNIEnv, _c: JClass, h: jlong, ip: JString, secs: jint) -> jboolean {
        let ip: String = env.get_string(&ip).map(|s| s.into()).unwrap_or_default();
        match (session(h), ip.parse::<Ipv4Addr>()) {
            (Some(s), Ok(a)) => s.debug_blackhole(a, secs.max(1) as u64) as jboolean,
            _ => 0,
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miplay_sender_NativeBridge_switchSource(mut env: JNIEnv, _c: JClass, h: jlong) -> jstring {
        let r = match session(h) {
            Some(s) => match s.switch_source() { Ok(o) => format!("{{\"time_offset_us\":{o}}}"), Err(e) => err_json(e) },
            None => err_json("no session"),
        };
        out(&mut env, r)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miplay_sender_NativeBridge_resume(mut env: JNIEnv, _c: JClass, h: jlong) -> jstring {
        let r = match session(h) {
            Some(s) => match s.resume() { Ok(o) => format!("{{\"time_offset_us\":{o}}}"), Err(e) => err_json(e) },
            None => err_json("no session"),
        };
        out(&mut env, r)
    }

    /// 调试：模拟某台断网（直接断开 socket，不发 CLOSE），用来验证自动重连。
    #[no_mangle]
    pub extern "system" fn Java_com_miplay_sender_NativeBridge_debugDrop(mut env: JNIEnv, _c: JClass, h: jlong, ip: JString) -> jboolean {
        match (session(h), jstr(&mut env, &ip).parse::<Ipv4Addr>()) {
            (Some(s), Ok(ip)) => s.debug_drop_connection(ip) as jboolean,
            _ => 0,
        }
    }

    /// 只读：查询运行中每台的声道（GetChannel 0x18 → 0x19）。返回 "ip:payloadhex|none;..."。
    #[no_mangle]
    pub extern "system" fn Java_com_miplay_sender_NativeBridge_queryChannel(mut env: JNIEnv, _c: JClass, h: jlong) -> jstring {
        let r = session(h).map(|s| s.query_channel().iter().map(|(ip, p)| format!("{ip}:{}", p.as_deref().unwrap_or("none"))).collect::<Vec<_>>().join(";"))
            .unwrap_or_else(|| "no session".into());
        out(&mut env, r)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miplay_sender_NativeBridge_stats(mut env: JNIEnv, _c: JClass, h: jlong) -> jstring {
        let r = match engine(h) {
            Some(e) => match e.session.lock().unwrap().clone() {
                Some(s) => s.stats_json(),
                None => err_json(e.last_error.lock().unwrap().as_str()),
            },
            None => err_json("no engine"),
        };
        out(&mut env, r)
    }

    /// 0=auto（先明文，失败回退 Safety），1=legacy，2=safety。之后的 connect 生效。
    static AUTH_MODE: std::sync::atomic::AtomicI32 = std::sync::atomic::AtomicI32::new(0);

    #[no_mangle]
    pub extern "system" fn Java_com_miplay_sender_NativeBridge_setAuthMode(_e: JNIEnv, _c: JClass, mode: jint) {
        AUTH_MODE.store(mode, std::sync::atomic::Ordering::SeqCst);
        let label = match mode { 1 => "legacy", 2 => "safety", _ => "auto" };
        log::info!("AUTH_MODE set to {mode} ({label})");
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miplay_sender_NativeBridge_safetyProbe(mut env: JNIEnv, _c: JClass, ip: JString) -> jstring {
        let ip: String = env.get_string(&ip).map(|s| s.into()).unwrap_or_default();
        let r = match ip.parse::<Ipv4Addr>() {
            Ok(a) => match crate::session::safety_probe_ex(a, crate::control::SAFETY_NATIVE_VERSION, &[0x1e]) {
                Ok(r) => r.to_json(),
                Err(e) => format!("{{\"error\":{:?}}}", e.to_string()),
            },
            Err(_) => "{\"error\":\"bad ip\"}".into(),
        };
        out(&mut env, r)
    }

    /// Wi-Fi 频率（MHz）→ 播放延迟（ms），与官方 isCurrentWifi5GHz ? 800 : 1000 一致。
    #[no_mangle]
    pub extern "system" fn Java_com_miplay_sender_NativeBridge_delayForFrequency(_e: JNIEnv, _c: JClass, mhz: jint) -> jint {
        (crate::netinfo::playback_delay_for_freq((mhz > 0).then_some(mhz as u32)) / 1000) as jint
    }

    #[no_mangle]
    pub extern "system" fn Java_com_miplay_sender_NativeBridge_stop(_e: JNIEnv, _c: JClass, h: jlong) {
        if h == 0 { return }
        let e = unsafe { Box::from_raw(h as *mut Engine) };
        let taken = e.session.lock().unwrap().take();
        if let Some(s) = taken { s.stop() }
        drop(e);
    }
}
