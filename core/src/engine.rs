//! 跨端公共引擎门面与多音箱并发协调器（第 1 层 Rust 核心对外入口）。
//!
//! 职责：
//! - 管理平台无关的 `Engine` 句柄表、有界 AAC 喂入队列与认证模式全局配置；
//! - 下沉原 Android `CastService` 中的多箱并发协调状态机（首台建连期间加减箱/音量排队、
//!   串行 reconcile、中途取消与重勾重试、连接期 `phase="connecting"` 叠加输出）；
//! - 向上分别支撑极薄的 Android JNI (`ffi.rs`) 与 iOS C ABI (`ffi_c.rs`)，消除双端重复造轮子。

use crate::clock::now_us;
use crate::control::SourceIdentity;
use crate::device_catalog::{
    self, coerce_auth_mode, discovered_result_to_json, extract_cacheable_devices,
    merge_cached_and_discovered, parse_devices_json, DEFAULT_CACHE_LIMIT,
};
use crate::session::{
    AuSource, AuthMode, CapturedAu, CastConfig, CastSession, ChannelSource, ReceiverStats,
    SilenceSource,
};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::net::Ipv4Addr;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicI64, Ordering};
use std::sync::mpsc::{sync_channel, SyncSender};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

/// 喂入队列上限：约 96×21.3ms ≈ 2s。连接期积压由 TIME_OFFSET 屏障 flush 丢掉；
/// 稳态若仍塞满则 `push_aac` 返回 -4（可观测），避免堆到十余秒。
pub const FEED_QUEUE_AUS: usize = 96;

/// 0=auto（先明文，失败回退 Safety），1=legacy，2=safety。之后的 connect 生效。
static AUTH_MODE: AtomicI32 = AtomicI32::new(0);
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
static ENGINES: OnceLock<Mutex<HashMap<i64, Arc<Engine>>>> = OnceLock::new();

fn engines() -> &'static Mutex<HashMap<i64, Arc<Engine>>> {
    ENGINES.get_or_init(|| Mutex::new(HashMap::new()))
}

#[derive(Default)]
struct CoordState {
    connect_started: bool,
    connect_finished: bool,
    desired: Vec<Ipv4Addr>,
    submitted: Vec<Ipv4Addr>,
    pending_connecting: Vec<Ipv4Addr>,
    pending_group_volume: Option<u8>,
    pending_speaker_volumes: BTreeMap<Ipv4Addr, u8>,
    in_flight_add: Option<Ipv4Addr>,
    reconcile_running: bool,
}

impl CoordState {
    fn add_unique(list: &mut Vec<Ipv4Addr>, ip: Ipv4Addr) -> bool {
        if list.contains(&ip) {
            false
        } else {
            list.push(ip);
            true
        }
    }

    fn clear(&mut self) {
        self.desired.clear();
        self.submitted.clear();
        self.pending_connecting.clear();
        self.pending_group_volume = None;
        self.pending_speaker_volumes.clear();
        self.in_flight_add = None;
        self.reconcile_running = false;
    }
}

pub struct Engine {
    session: Mutex<Option<Arc<CastSession>>>,
    feed: Mutex<Option<SyncSender<CapturedAu>>>,
    source: Mutex<Option<Box<dyn AuSource>>>,
    stopped: AtomicBool,
    last_error: Mutex<String>,
    coord: Mutex<CoordState>,
}

fn engine(handle: i64) -> Option<Arc<Engine>> {
    if handle == 0 {
        return None;
    }
    engines().lock().unwrap().get(&handle).cloned()
}

fn take_engine(handle: i64) -> Option<Arc<Engine>> {
    if handle == 0 {
        return None;
    }
    engines().lock().unwrap().remove(&handle)
}

fn session(handle: i64) -> Option<Arc<CastSession>> {
    engine(handle).and_then(|e| e.session.lock().unwrap().clone())
}

fn err_json(e: impl std::fmt::Display) -> String {
    format!("{{\"error\":{}}}", device_catalog::json_escape(&e.to_string()))
}

fn parse_ip_list(raw: &str) -> Vec<Ipv4Addr> {
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for part in raw.split(',') {
        if let Ok(ip) = part.trim().parse::<Ipv4Addr>() {
            if seen.insert(ip) {
                out.push(ip);
            }
        }
    }
    out
}

// ---------------------------------------------------------------------------
// 发现与无状态查询
// ---------------------------------------------------------------------------

pub fn set_auth_mode(mode: i32) {
    let coerced = coerce_auth_mode(mode);
    AUTH_MODE.store(coerced, Ordering::SeqCst);
    log::info!("AUTH_MODE set to {coerced} ({})", device_catalog::auth_mode_label(coerced));
}

pub fn current_auth_mode() -> AuthMode {
    match AUTH_MODE.load(Ordering::SeqCst) {
        1 => AuthMode::Legacy,
        2 => AuthMode::Safety,
        _ => AuthMode::Auto,
    }
}

pub fn delay_for_frequency(mhz: i32) -> i32 {
    (crate::netinfo::playback_delay_for_freq((mhz > 0).then_some(mhz as u32)) / 1000) as i32
}

/// 6 列 TSV 发现结果：`ip\tname\tmodel\tadvertises_miplay\tservice\tapps`。
pub fn discover_tsv(local_ip: &str, timeout_ms: i32) -> String {
    let local: Ipv4Addr = local_ip.parse().unwrap_or(Ipv4Addr::UNSPECIFIED);
    match crate::discovery::browse(local, Duration::from_millis(timeout_ms.max(500) as u64)) {
        Ok(mut list) => {
            list.sort_by_key(|s| !s.advertises_miplay());
            let mut seen = HashSet::new();
            list.iter()
                .filter_map(|s| s.ipv4.map(|ip| (ip, s)))
                .filter(|(ip, _)| seen.insert(*ip))
                .map(|(ip, s)| s.format_discover_line(ip))
                .collect::<Vec<_>>()
                .join("\n")
        }
        Err(e) => format!("ERROR\t{e}"),
    }
}

/// 跨端结构化发现 JSON：一次完成 mDNS 扫描、可投性分类、历史缓存合并与可缓存列表提取。
pub fn discover_json(local_ip: &str, timeout_ms: i32, cached_json: &str) -> String {
    let local: Ipv4Addr = local_ip.parse().unwrap_or(Ipv4Addr::UNSPECIFIED);
    match crate::discovery::browse(local, Duration::from_millis(timeout_ms.max(500) as u64)) {
        Ok(mut list) => {
            list.sort_by_key(|s| !s.advertises_miplay());
            let mut seen = HashSet::new();
            let fresh_rows: Vec<_> = list
                .iter()
                .filter_map(|s| s.ipv4.map(|ip| (ip, s)))
                .filter(|(ip, _)| seen.insert(*ip))
                .map(|(ip, s)| device_catalog::from_speaker(ip, s))
                .collect();
            let fresh_devices: Vec<_> = fresh_rows.iter().map(|r| r.device.clone()).collect();
            let cached = parse_devices_json(cached_json);
            let merged = merge_cached_and_discovered(&cached, &fresh_devices);
            let cacheable = extract_cacheable_devices(&merged, DEFAULT_CACHE_LIMIT);
            discovered_result_to_json(&fresh_rows, &merged, &cacheable)
        }
        Err(e) => err_json(e),
    }
}

pub fn probe_json(ip: &str) -> String {
    match ip.trim().parse::<Ipv4Addr>() {
        Ok(addr) => match crate::session::probe(addr, &SourceIdentity::default()) {
            Ok(st) => st.to_json(),
            Err(e) => err_json(e),
        },
        Err(_) => err_json("bad ip"),
    }
}

pub fn safety_probe_json(ip: &str) -> String {
    match ip.trim().parse::<Ipv4Addr>() {
        Ok(addr) => match crate::session::safety_probe_ex(addr, crate::control::SAFETY_NATIVE_VERSION, &[0x1e]) {
            Ok(r) => r.to_json(),
            Err(e) => err_json(e),
        },
        Err(_) => err_json("bad ip"),
    }
}

// ---------------------------------------------------------------------------
// 引擎生命周期与媒体喂入
// ---------------------------------------------------------------------------

pub fn create(silent: bool) -> i64 {
    let (source, feed): (Box<dyn AuSource>, Option<SyncSender<CapturedAu>>) = if silent {
        (Box::new(SilenceSource { remaining: None }), None)
    } else {
        let (tx, rx) = sync_channel(FEED_QUEUE_AUS);
        (Box::new(ChannelSource(rx)), Some(tx))
    };
    let eng = Arc::new(Engine {
        session: Mutex::new(None),
        feed: Mutex::new(feed),
        source: Mutex::new(Some(source)),
        stopped: AtomicBool::new(false),
        last_error: Mutex::new(String::new()),
        coord: Mutex::new(CoordState::default()),
    });
    let id = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    engines().lock().unwrap().insert(id, eng);
    id
}

pub fn connect(
    handle: i64,
    ips_raw: &str,
    delay_ms: i32,
    premute: i32,
    volume_after_play: i32,
    ref_function: &str,
    source_name: &str,
) -> String {
    let ips = parse_ip_list(ips_raw);
    let mut id = SourceIdentity::default();
    let rf = ref_function.trim();
    id.ref_function = if !rf.is_empty() {
        rf.to_string()
    } else if ips.len() > 1 {
        "multi_room".into()
    } else {
        "single_room".into()
    };
    let sn = source_name.trim();
    if !sn.is_empty() {
        id.source_name = sn.to_string();
    }
    let (delay_us, freq) = if delay_ms > 0 {
        (delay_ms.clamp(200, 3000) as i64 * 1000, None)
    } else {
        crate::netinfo::auto_playback_delay_us()
    };
    let auth = current_auth_mode();
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
    let Some(e) = engine(handle) else {
        return err_json("no engine");
    };
    if e.stopped.load(Ordering::SeqCst) {
        return err_json("stopped");
    }
    let Some(source) = e.source.lock().unwrap().take() else {
        return err_json("already started");
    };
    {
        let mut c = e.coord.lock().unwrap();
        c.connect_started = true;
        for &ip in &ips {
            CoordState::add_unique(&mut c.desired, ip);
            CoordState::add_unique(&mut c.submitted, ip);
            CoordState::add_unique(&mut c.pending_connecting, ip);
        }
    }
    match CastSession::start(&ips, source, cfg) {
        Ok((s, init)) => {
            for st in init {
                log::info!("initial {}", st.to_json());
            }
            if e.stopped.load(Ordering::SeqCst) {
                log::info!("engine stopped while connect was in flight; shutting down new session");
                s.stop();
                return err_json("stopped");
            }
            let session_arc = Arc::new(s);
            *e.session.lock().unwrap() = Some(session_arc.clone());
            let (extra_connecting, excluded) = {
                let mut c = e.coord.lock().unwrap();
                c.pending_connecting.retain(|ip| !ips.contains(ip));
                c.connect_finished = true;
                let extra = c.pending_connecting.clone();
                let excl: Vec<Ipv4Addr> = ips
                    .iter()
                    .copied()
                    .filter(|ip| !c.desired.contains(ip))
                    .collect();
                (extra, excl)
            };
            let json = session_arc.stats_json_with_overlay(&extra_connecting, &excluded);
            trigger_reconcile(e);
            json
        }
        Err(err) => {
            log::error!("start failed: {err}");
            *e.last_error.lock().unwrap() = err.to_string();
            e.coord.lock().unwrap().clear();
            err_json(err)
        }
    }
}

pub fn push_aac(handle: i64, bytes: Vec<u8>) -> i32 {
    let Some(e) = engine(handle) else { return -1 };
    let feed = e.feed.lock().unwrap().clone();
    let Some(feed) = feed else { return -2 };
    match feed.try_send(CapturedAu { capture_us: now_us(), aac: bytes }) {
        Ok(()) => 0,
        Err(_) => -4,
    }
}

// ---------------------------------------------------------------------------
// 跨端协调式控制（非阻塞排队 + 单线程串行 reconcile）
// ---------------------------------------------------------------------------

/// 协调式加入接收端：若首台尚未建连完成则排队并立刻在 `stats` 体现 `connecting`，建连后自动 reconcile。
pub fn queue_add_receiver(handle: i64, ip_raw: &str) -> String {
    let Ok(ip) = ip_raw.trim().parse::<Ipv4Addr>() else {
        return err_json("bad ip");
    };
    let Some(e) = engine(handle) else {
        return err_json("no engine");
    };
    if e.stopped.load(Ordering::SeqCst) {
        return err_json("stopped");
    }
    let ready = {
        let mut c = e.coord.lock().unwrap();
        CoordState::add_unique(&mut c.desired, ip);
        if !c.submitted.contains(&ip) {
            CoordState::add_unique(&mut c.pending_connecting, ip);
        }
        c.connect_finished
    };
    if ready {
        trigger_reconcile(e);
    }
    format!(
        "{{\"queued\":true,\"ip\":{},\"connect_finished\":{ready}}}",
        device_catalog::json_escape(&ip.to_string())
    )
}

/// 协调式移除接收端：立即从 `desired`/`pending_connecting`/`stats` 剔除，若正在握手则并发取消并触发 reconcile。
pub fn queue_remove_receiver(handle: i64, ip_raw: &str) -> bool {
    let Ok(ip) = ip_raw.trim().parse::<Ipv4Addr>() else {
        return false;
    };
    let Some(e) = engine(handle) else {
        return false;
    };
    let (cancel_in_flight, ready) = {
        let mut c = e.coord.lock().unwrap();
        c.desired.retain(|x| *x != ip);
        c.pending_connecting.retain(|x| *x != ip);
        c.pending_speaker_volumes.remove(&ip);
        (c.in_flight_add == Some(ip), c.connect_finished)
    };
    if cancel_in_flight {
        if let Some(s) = e.session.lock().unwrap().clone() {
            let e_clone = e.clone();
            let _ = std::thread::Builder::new().name("engine-cancel-add".into()).spawn(move || {
                let still_removed = !e_clone.coord.lock().unwrap().desired.contains(&ip);
                if still_removed {
                    s.remove_receiver(ip);
                }
            });
        }
    }
    if ready {
        trigger_reconcile(e);
    }
    true
}

/// 协调式调音量：`ip_raw` 为空表示全组；若首台尚未建连或目标音箱尚在连接队列则排队等待就绪后自动应用。
pub fn queue_set_volume(handle: i64, ip_raw: &str, volume: i32) -> String {
    let v = volume.clamp(0, 100) as u8;
    let ip_opt = if ip_raw.trim().is_empty() {
        None
    } else {
        match ip_raw.trim().parse::<Ipv4Addr>() {
            Ok(ip) => Some(ip),
            Err(_) => return err_json("bad ip"),
        }
    };
    let Some(e) = engine(handle) else {
        return err_json("no engine");
    };
    if e.stopped.load(Ordering::SeqCst) {
        return err_json("stopped");
    }
    let ready = {
        let mut c = e.coord.lock().unwrap();
        match ip_opt {
            None => c.pending_group_volume = Some(v),
            Some(ip) => {
                c.pending_speaker_volumes.insert(ip, v);
            }
        }
        c.connect_finished
    };
    if ready {
        trigger_reconcile(e);
    }
    format!(
        "{{\"queued\":true,\"ip\":{},\"volume\":{v},\"connect_finished\":{ready}}}",
        ip_opt
            .map(|ip| device_catalog::json_escape(&ip.to_string()))
            .unwrap_or_else(|| "\"\"".into())
    )
}

enum ReconcileAction {
    Remove(Ipv4Addr),
    Add(Ipv4Addr),
    GroupVolume(u8),
    SpeakerVolume(Ipv4Addr, u8),
}

fn next_reconcile_action(c: &mut CoordState) -> Option<ReconcileAction> {
    if let Some(ip) = c.submitted.iter().copied().find(|ip| !c.desired.contains(ip)) {
        c.submitted.retain(|x| *x != ip);
        c.pending_connecting.retain(|x| *x != ip);
        return Some(ReconcileAction::Remove(ip));
    }
    if let Some(ip) = c.desired.iter().copied().find(|ip| !c.submitted.contains(ip)) {
        CoordState::add_unique(&mut c.submitted, ip);
        CoordState::add_unique(&mut c.pending_connecting, ip);
        c.in_flight_add = Some(ip);
        return Some(ReconcileAction::Add(ip));
    }
    if let Some(v) = c.pending_group_volume.take() {
        return Some(ReconcileAction::GroupVolume(v));
    }
    let ready_vol = c
        .pending_speaker_volumes
        .keys()
        .copied()
        .find(|ip| !c.pending_connecting.contains(ip));
    if let Some(ip) = ready_vol {
        if let Some(v) = c.pending_speaker_volumes.remove(&ip) {
            return Some(ReconcileAction::SpeakerVolume(ip, v));
        }
    }
    None
}

fn trigger_reconcile(e: Arc<Engine>) {
    {
        let mut c = e.coord.lock().unwrap();
        if c.reconcile_running || !c.connect_finished || e.stopped.load(Ordering::SeqCst) {
            return;
        }
        c.reconcile_running = true;
    }
    let _ = std::thread::Builder::new()
        .name("engine-reconcile".into())
        .spawn(move || {
            loop {
                if e.stopped.load(Ordering::SeqCst) {
                    break;
                }
                let action = {
                    let mut c = e.coord.lock().unwrap();
                    if !c.connect_finished || e.stopped.load(Ordering::SeqCst) {
                        c.reconcile_running = false;
                        return;
                    }
                    match next_reconcile_action(&mut c) {
                        Some(a) => a,
                        None => {
                            c.reconcile_running = false;
                            return;
                        }
                    }
                };
                let Some(sess) = e.session.lock().unwrap().clone() else {
                    break;
                };
                match action {
                    ReconcileAction::Remove(ip) => {
                        sess.remove_receiver(ip);
                        log::info!("engine reconcile: removed {ip}");
                    }
                    ReconcileAction::Add(ip) => {
                        log::info!("engine reconcile: adding {ip}");
                        let res = sess.add_receiver(ip);
                        let mut removed_during_add = false;
                        {
                            let mut c = e.coord.lock().unwrap();
                            if c.in_flight_add == Some(ip) {
                                c.in_flight_add = None;
                            }
                            if !c.desired.contains(&ip) {
                                c.submitted.retain(|x| *x != ip);
                                c.pending_connecting.retain(|x| *x != ip);
                                removed_during_add = true;
                            } else if let Err(ref err) = res {
                                if err.to_string().contains("receiver removed during connect") {
                                    // 取消后迅速重新勾选：立刻重新入队再连
                                    c.submitted.retain(|x| *x != ip);
                                    CoordState::add_unique(&mut c.pending_connecting, ip);
                                } else {
                                    // 其他失败已由 CastSession 迁入 pending 退避重连
                                    c.pending_connecting.retain(|x| *x != ip);
                                }
                            } else {
                                c.pending_connecting.retain(|x| *x != ip);
                            }
                        }
                        if removed_during_add {
                            sess.remove_receiver(ip);
                            log::info!("engine reconcile: cancelled {ip} during add");
                        }
                    }
                    ReconcileAction::GroupVolume(v) => {
                        let res = sess.set_volume(None, v);
                        log::info!("engine reconcile: group volume {v} -> {res:?}");
                    }
                    ReconcileAction::SpeakerVolume(ip, v) => {
                        let res = sess.set_volume(Some(ip), v);
                        log::info!("engine reconcile: speaker {ip} volume {v} -> {res:?}");
                    }
                }
            }
            let mut c = e.coord.lock().unwrap();
            c.in_flight_add = None;
            c.reconcile_running = false;
        });
}

// ---------------------------------------------------------------------------
// 直接同步控制（兼容旧接口与 DevMain / CLI 调试路径）
// ---------------------------------------------------------------------------

pub fn set_volume(handle: i64, ip_raw: &str, volume: i32) -> String {
    let ip = ip_raw.trim().parse::<Ipv4Addr>().ok();
    session(handle)
        .map(|s| s.set_volume(ip, volume.clamp(0, 100) as u8))
        .map(|v| {
            v.iter()
                .map(|(ip, r)| format!("{ip}:{r:?}"))
                .collect::<Vec<_>>()
                .join(";")
        })
        .unwrap_or_else(|| "no session".into())
}

pub fn add_receiver(handle: i64, ip_raw: &str) -> String {
    let ip = ip_raw.trim().parse::<Ipv4Addr>();
    if let (Some(e), Ok(addr)) = (engine(handle), ip.as_ref().copied()) {
        let mut c = e.coord.lock().unwrap();
        CoordState::add_unique(&mut c.desired, addr);
        CoordState::add_unique(&mut c.submitted, addr);
    }
    match (session(handle), ip) {
        (Some(s), Ok(addr)) => match s.add_receiver(addr) {
            Ok(st) => st.to_json(),
            Err(err) => err_json(err),
        },
        (None, _) => err_json("no session"),
        _ => err_json("bad ip"),
    }
}

pub fn remove_receiver(handle: i64, ip_raw: &str) {
    if let Ok(ip) = ip_raw.trim().parse::<Ipv4Addr>() {
        if let Some(e) = engine(handle) {
            let mut c = e.coord.lock().unwrap();
            c.desired.retain(|x| *x != ip);
            c.submitted.retain(|x| *x != ip);
            c.pending_connecting.retain(|x| *x != ip);
            c.pending_speaker_volumes.remove(&ip);
        }
        if let Some(s) = session(handle) {
            s.remove_receiver(ip);
        }
    }
}

pub fn pause(handle: i64) -> String {
    session(handle)
        .map(|s| {
            s.pause()
                .iter()
                .map(|(ip, r)| format!("{ip}:{r:?}"))
                .collect::<Vec<_>>()
                .join(";")
        })
        .unwrap_or_else(|| "no session".into())
}

pub fn resume(handle: i64) -> String {
    match session(handle) {
        Some(s) => match s.resume() {
            Ok(o) => format!("{{\"time_offset_us\":{o}}}"),
            Err(e) => err_json(e),
        },
        None => err_json("no session"),
    }
}

pub fn switch_source(handle: i64) -> String {
    match session(handle) {
        Some(s) => match s.switch_source() {
            Ok(o) => format!("{{\"time_offset_us\":{o}}}"),
            Err(e) => err_json(e),
        },
        None => err_json("no session"),
    }
}

pub fn debug_drop(handle: i64, ip_raw: &str) -> bool {
    match (session(handle), ip_raw.trim().parse::<Ipv4Addr>()) {
        (Some(s), Ok(ip)) => s.debug_drop_connection(ip),
        _ => false,
    }
}

pub fn debug_blackhole(handle: i64, ip_raw: &str, secs: i32) -> bool {
    match (session(handle), ip_raw.trim().parse::<Ipv4Addr>()) {
        (Some(s), Ok(ip)) => s.debug_blackhole(ip, secs.max(1) as u64),
        _ => false,
    }
}

pub fn query_channel(handle: i64) -> String {
    session(handle)
        .map(|s| {
            s.query_channel()
                .iter()
                .map(|(ip, p)| format!("{ip}:{}", p.as_deref().unwrap_or("none")))
                .collect::<Vec<_>>()
                .join(";")
        })
        .unwrap_or_else(|| "no session".into())
}

/// 统一统计 JSON：首台连接期间也直接吐出 `phase="connecting"` / `ui_state="connecting"`，
/// 运行中自动合并尚未建连完成的排队成员并隐藏已取消勾选的成员。
pub fn stats_json(handle: i64) -> String {
    let Some(e) = engine(handle) else {
        return err_json("no engine");
    };
    let sess = e.session.lock().unwrap().clone();
    let (pending, desired, connect_finished) = {
        let c = e.coord.lock().unwrap();
        (c.pending_connecting.clone(), c.desired.clone(), c.connect_finished)
    };
    if let Some(s) = sess {
        let excluded: Vec<Ipv4Addr> = if connect_finished {
            s.stats()
                .iter()
                .filter_map(|r| r.ip.parse::<Ipv4Addr>().ok())
                .filter(|ip| !desired.contains(ip))
                .collect()
        } else {
            Vec::new()
        };
        return s.stats_json_with_overlay(&pending, &excluded);
    }
    if !e.stopped.load(Ordering::SeqCst) && !pending.is_empty() {
        let receivers: Vec<String> = pending
            .iter()
            .map(|ip| {
                ReceiverStats {
                    ip: ip.to_string(),
                    phase: "connecting".into(),
                    ..Default::default()
                }
                .to_json()
            })
            .collect();
        return format!("{{\"receivers\":[{}]}}", receivers.join(","));
    }
    let last = e.last_error.lock().unwrap().clone();
    err_json(last)
}

pub fn stop(handle: i64) {
    if handle == 0 {
        return;
    }
    let Some(e) = take_engine(handle) else { return };
    e.stopped.store(true, Ordering::SeqCst);
    e.coord.lock().unwrap().clear();
    // 先释放 feed 发送端，使阻塞在 ChannelSource::recv 的 media-clock 或预热屏障立即退出，避免 join 死锁
    let _ = e.feed.lock().unwrap().take();
    let taken = e.session.lock().unwrap().take();
    if let Some(s) = taken {
        s.stop();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn coord_prioritizes_remove_then_add_then_volumes() {
        let ip1: Ipv4Addr = "192.0.2.1".parse().unwrap();
        let ip2: Ipv4Addr = "192.0.2.2".parse().unwrap();
        let mut c = CoordState {
            connect_started: true,
            connect_finished: true,
            desired: vec![ip2],
            submitted: vec![ip1],
            pending_connecting: vec![ip2],
            pending_group_volume: Some(50),
            pending_speaker_volumes: BTreeMap::from([(ip2, 35)]),
            in_flight_add: None,
            reconcile_running: true,
        };

        assert!(matches!(next_reconcile_action(&mut c), Some(ReconcileAction::Remove(x)) if x == ip1));
        assert!(matches!(next_reconcile_action(&mut c), Some(ReconcileAction::Add(x)) if x == ip2));
        assert_eq!(c.in_flight_add, Some(ip2));
        assert!(matches!(next_reconcile_action(&mut c), Some(ReconcileAction::GroupVolume(50))));
        // ip2 仍在 pending_connecting，单箱音量暂缓
        assert!(next_reconcile_action(&mut c).is_none());
        // 连接完成后单箱音量放行
        c.pending_connecting.clear();
        assert!(matches!(next_reconcile_action(&mut c), Some(ReconcileAction::SpeakerVolume(x, 35)) if x == ip2));
        assert!(next_reconcile_action(&mut c).is_none());
    }

    #[test]
    fn queued_receivers_appear_as_connecting_in_stats_before_session_ready() {
        let h = create(true);
        let q = queue_add_receiver(h, "192.0.2.40");
        assert!(q.contains("\"queued\":true"));
        let st = stats_json(h);
        assert!(st.contains("\"ip\":\"192.0.2.40\""), "stats={st}");
        assert!(st.contains("\"ui_state\":\"connecting\""), "stats={st}");
        assert!(st.contains("\"display_latency_ms\":null"), "stats={st}");

        assert!(queue_remove_receiver(h, "192.0.2.40"));
        let st2 = stats_json(h);
        assert!(!st2.contains("192.0.2.40"), "stats after remove={st2}");
        stop(h);
    }

    #[test]
    fn push_aac_error_codes_and_stop_cleanup() {
        let silent_h = create(true);
        assert_eq!(push_aac(silent_h, vec![1, 2, 3]), -2);
        stop(silent_h);
        assert_eq!(push_aac(silent_h, vec![1, 2, 3]), -1);

        let app_h = create(false);
        assert_eq!(push_aac(app_h, vec![0x21, 0x10]), 0);
        stop(app_h);
        assert_eq!(push_aac(app_h, vec![0x21, 0x10]), -1);
    }
}
