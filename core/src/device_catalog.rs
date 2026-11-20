//! 跨端共享设备发现分类、缓存合并、路由互斥状态机与认证模式解析。
//!
//! 下沉自 Android 客户端 `CastRouteSelection.kt` 与 `AuthModeResolve.kt`，供 Android JNI
//! 与后续 iOS C ABI / SwiftUI 共用同一份业务口径，避免双端重复实现。

use crate::discovery::Speaker;
use std::collections::HashSet;
use std::net::Ipv4Addr;

pub const DEFAULT_CACHE_LIMIT: usize = 16;

// ---------------------------------------------------------------------------
// 认证模式解析（0=auto, 1=legacy, 2=safety）
// ---------------------------------------------------------------------------

pub fn parse_auth_mode(raw: &str) -> Option<i32> {
    match raw.trim().to_ascii_lowercase().as_str() {
        "auto" => Some(0),
        "legacy" => Some(1),
        "safety" => Some(2),
        _ => None,
    }
}

pub fn coerce_auth_mode(mode: i32) -> i32 {
    mode.clamp(0, 2)
}

pub fn auth_mode_label(mode: i32) -> &'static str {
    match coerce_auth_mode(mode) {
        1 => "legacy",
        2 => "safety",
        _ => "auto",
    }
}

// ---------------------------------------------------------------------------
// 设备模型与协议枚举
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DeviceKind {
    ThisDevice,
    Speaker,
    Tv,
}

impl DeviceKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::ThisDevice => "THIS_DEVICE",
            Self::Speaker => "SPEAKER",
            Self::Tv => "TV",
        }
    }

    pub fn from_str(raw: &str) -> Self {
        match raw.trim().to_ascii_uppercase().as_str() {
            "THIS_DEVICE" => Self::ThisDevice,
            "TV" => Self::Tv,
            _ => Self::Speaker,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CastProtocol {
    MiPlay,
    Dlna,
    AirPlay,
    GoogleCast,
}

impl CastProtocol {
    pub fn id(self) -> &'static str {
        match self {
            Self::MiPlay => "miplay",
            Self::Dlna => "dlna",
            Self::AirPlay => "airplay",
            Self::GoogleCast => "gcast",
        }
    }

    pub fn wire_name(self) -> &'static str {
        match self {
            Self::MiPlay => "MIPLAY",
            Self::Dlna => "DLNA",
            Self::AirPlay => "AIRPLAY",
            Self::GoogleCast => "GOOGLE_CAST",
        }
    }

    pub fn label_zh(self) -> &'static str {
        match self {
            Self::MiPlay => "MiPlay",
            Self::Dlna => "DLNA",
            Self::AirPlay => "AirPlay",
            Self::GoogleCast => "Google Cast",
        }
    }

    pub fn cast_implemented(self) -> bool {
        matches!(self, Self::MiPlay)
    }

    pub fn from_str(raw: &str) -> Option<Self> {
        match raw.trim().to_ascii_lowercase().as_str() {
            "miplay" => Some(Self::MiPlay),
            "dlna" => Some(Self::Dlna),
            "airplay" => Some(Self::AirPlay),
            "gcast" | "google_cast" | "googlecast" => Some(Self::GoogleCast),
            _ => None,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Device {
    pub id: String,
    pub name: String,
    pub kind: DeviceKind,
    pub castable: bool,
    pub selected: bool,
    pub detail: String,
    pub protocol: Option<CastProtocol>,
}

impl Device {
    pub fn this_device(selected: bool) -> Self {
        Self {
            id: "this".into(),
            name: "This device".into(),
            kind: DeviceKind::ThisDevice,
            castable: true,
            selected,
            detail: String::new(),
            protocol: None,
        }
    }

    pub fn is_cast_sink(&self) -> bool {
        matches!(self.kind, DeviceKind::Speaker | DeviceKind::Tv)
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DiscoveredRow {
    pub device: Device,
    pub advertises_miplay: bool,
    pub service: String,
    pub apps: String,
    pub visible_by_default: bool,
    pub log_line: String,
}

// ---------------------------------------------------------------------------
// 发现行分类、过滤与历史缓存合并
// ---------------------------------------------------------------------------

/// 从名称/型号推断 UI 形态（音箱 vs 电视/显示器）；不影响可投性。
pub fn looks_like_tv(name: &str, model: &str) -> bool {
    let combined = format!("{name} {model}");
    let lower = combined.to_ascii_lowercase();
    lower.contains("tv")
        || combined.contains("电视")
        || lower.contains("g pro")
        || combined.contains("显示器")
        || lower.contains("redmi g")
        || lower.contains("monitor")
}

pub fn classify_discovered(
    ip: &str,
    name: &str,
    model: &str,
    advertises_miplay: bool,
    service: &str,
    apps: &str,
) -> Option<DiscoveredRow> {
    let ip = ip.trim();
    if ip.is_empty() {
        return None;
    }
    let name = if name.trim().is_empty() { ip } else { name.trim() };
    let model = model.trim();
    let service = service.trim();
    let apps = apps.trim();
    let kind = if looks_like_tv(name, model) {
        DeviceKind::Tv
    } else {
        DeviceKind::Speaker
    };
    let is_mi_connect = service.is_empty() || service.to_ascii_lowercase().contains("_mi-connect");
    let castable = advertises_miplay || (kind == DeviceKind::Tv && is_mi_connect);
    let label = match (name.is_empty(), model.is_empty()) {
        (false, false) => format!("{name} · {model}"),
        (false, true) => name.to_string(),
        (true, false) => model.to_string(),
        (true, true) => ip.to_string(),
    };
    let svc_log = if service.is_empty() { "unknown" } else { service };
    let apps_log = if apps.is_empty() { "none" } else { apps };
    let detail = if castable {
        String::new()
    } else {
        format!("未声明 MiPlay 投送能力（service={svc_log} apps={apps_log}）")
    };
    let device = Device {
        id: ip.to_string(),
        name: label,
        kind,
        castable,
        selected: false,
        detail,
        protocol: if castable { Some(CastProtocol::MiPlay) } else { None },
    };
    let log_line = format!(
        "discover_row ip={ip} name={name} model={model} advertises_miplay={advertises_miplay} \
         service={svc_log} apps={apps_log} kind={} castable={castable} visible_default={castable}",
        kind.as_str()
    );
    Some(DiscoveredRow {
        device,
        advertises_miplay,
        service: service.to_string(),
        apps: apps.to_string(),
        visible_by_default: castable,
        log_line,
    })
}

pub fn from_speaker(ip: Ipv4Addr, speaker: &Speaker) -> DiscoveredRow {
    let ip_s = ip.to_string();
    let name = speaker.display_name();
    let model = speaker.model().unwrap_or_default();
    classify_discovered(
        &ip_s,
        &name,
        &model,
        speaker.advertises_miplay(),
        speaker.service_type(),
        speaker.apps_raw(),
    )
    .expect("valid Ipv4Addr always produces a DiscoveredRow")
}

/// 解析 6 列发现 TSV：`ip\tname\tmodel\tadvertises_miplay\tservice\tapps`。
pub fn parse_discovered_line(line: &str) -> Option<DiscoveredRow> {
    let trimmed = line.trim_start();
    if trimmed.is_empty() || trimmed.starts_with("ERROR") {
        return None;
    }
    let parts: Vec<&str> = line.split('\t').collect();
    let ip = parts.first().copied().unwrap_or("").trim();
    if ip.is_empty() {
        return None;
    }
    let name = parts.get(1).copied().unwrap_or(ip);
    let model = parts.get(2).copied().unwrap_or("");
    let advertises_miplay = parts.get(3).copied().unwrap_or("1").trim() == "1";
    let service = parts.get(4).copied().unwrap_or("");
    let apps = parts.get(5).copied().unwrap_or("");
    classify_discovered(ip, name, model, advertises_miplay, service, apps)
}

pub fn filter_visible_devices(devices: &[Device], show_all: bool) -> Vec<Device> {
    if show_all {
        return devices.to_vec();
    }
    devices
        .iter()
        .filter(|d| d.kind == DeviceKind::ThisDevice || d.castable || d.protocol.is_some())
        .cloned()
        .collect()
}

pub fn extract_cacheable_devices(devices: &[Device], limit: usize) -> Vec<Device> {
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for d in devices {
        if !d.is_cast_sink() || !d.castable {
            continue;
        }
        if seen.insert(d.id.clone()) {
            let mut copy = d.clone();
            copy.selected = false;
            out.push(copy);
            if out.len() >= limit {
                break;
            }
        }
    }
    out
}

pub fn merge_cached_and_discovered(cached: &[Device], discovered: &[Device]) -> Vec<Device> {
    let mut merged: Vec<Device> = discovered
        .iter()
        .filter(|d| d.kind != DeviceKind::ThisDevice)
        .cloned()
        .collect();
    let fresh_ids: HashSet<String> = merged.iter().map(|d| d.id.clone()).collect();
    for d in extract_cacheable_devices(cached, DEFAULT_CACHE_LIMIT) {
        if !fresh_ids.contains(&d.id) {
            merged.push(d);
        }
    }
    merged
}

// ---------------------------------------------------------------------------
// 路由互斥状态机（This device ↔ 多选音箱/电视）
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RouteState {
    pub this_device_selected: bool,
    pub selected_speaker_ids: Vec<String>,
    pub devices: Vec<Device>,
}

impl RouteState {
    pub fn casting(&self) -> bool {
        !self.selected_speaker_ids.is_empty()
    }

    pub fn initial(devices: Vec<Device>) -> Self {
        let mut has_this = false;
        let mut normalized: Vec<Device> = devices
            .into_iter()
            .map(|mut d| {
                if d.kind == DeviceKind::ThisDevice {
                    has_this = true;
                    d.selected = true;
                    d.castable = true;
                    d.protocol = None;
                } else {
                    d.selected = false;
                }
                d
            })
            .collect();
        if !has_this {
            normalized.insert(0, Device::this_device(true));
        }
        Self {
            this_device_selected: true,
            selected_speaker_ids: Vec::new(),
            devices: normalized,
        }
    }

    pub fn select_this_device(&self) -> RouteTransition {
        let was_casting = self.casting();
        let devices = self
            .devices
            .iter()
            .map(|d| {
                let mut copy = d.clone();
                copy.selected = d.kind == DeviceKind::ThisDevice;
                copy
            })
            .collect();
        let next = Self {
            this_device_selected: true,
            selected_speaker_ids: Vec::new(),
            devices,
        };
        let effects = if was_casting {
            vec![RouteEffect::StopCastToThisDevice]
        } else {
            Vec::new()
        };
        RouteTransition { state: next, effects }
    }

    pub fn set_speaker_checked(&self, speaker_id: &str, want_selected: bool) -> RouteTransition {
        let Some(target) = self.devices.iter().find(|d| d.id == speaker_id) else {
            return RouteTransition { state: self.clone(), effects: Vec::new() };
        };
        if !target.is_cast_sink() {
            return RouteTransition {
                state: self.clone(),
                effects: vec![RouteEffect::RejectNonCastable {
                    id: speaker_id.to_string(),
                    reason: "不是可投送接收端".into(),
                }],
            };
        }
        if !target.castable {
            let reason = if !target.detail.trim().is_empty() {
                target.detail.clone()
            } else if let Some(proto) = target.protocol.filter(|p| !p.cast_implemented()) {
                format!("{} 协议尚未在本 App 实现，不能假装投送成功", proto.label_zh())
            } else {
                "设备不可投送".into()
            };
            return RouteTransition {
                state: self.clone(),
                effects: vec![RouteEffect::RejectNonCastable {
                    id: speaker_id.to_string(),
                    reason,
                }],
            };
        }
        if let Some(proto) = target.protocol.filter(|p| !p.cast_implemented()) {
            return RouteTransition {
                state: self.clone(),
                effects: vec![RouteEffect::RejectNonCastable {
                    id: speaker_id.to_string(),
                    reason: format!("{} 协议尚未实现，不能假装投送成功", proto.label_zh()),
                }],
            };
        }
        let currently_selected = self.selected_speaker_ids.iter().any(|id| id == speaker_id);
        if want_selected == currently_selected {
            return RouteTransition { state: self.clone(), effects: Vec::new() };
        }
        let mut next_selected = self.selected_speaker_ids.clone();
        if want_selected {
            next_selected.push(speaker_id.to_string());
        } else {
            next_selected.retain(|id| id != speaker_id);
        }
        if next_selected.is_empty() {
            return self.select_this_device();
        }
        let devices = self
            .devices
            .iter()
            .map(|d| {
                let mut copy = d.clone();
                if d.kind == DeviceKind::ThisDevice {
                    copy.selected = false;
                } else if d.is_cast_sink() {
                    copy.selected = next_selected.iter().any(|id| id == &d.id);
                }
                copy
            })
            .collect();
        let effects = if !self.casting() && want_selected {
            vec![RouteEffect::StartCast { speaker_ids: next_selected.clone() }]
        } else if self.casting() && want_selected {
            vec![RouteEffect::AddSpeaker { id: speaker_id.to_string() }]
        } else if self.casting() && !want_selected {
            vec![RouteEffect::RemoveSpeaker { id: speaker_id.to_string() }]
        } else {
            Vec::new()
        };
        RouteTransition {
            state: Self {
                this_device_selected: false,
                selected_speaker_ids: next_selected,
                devices,
            },
            effects,
        }
    }

    pub fn merge_discovered(&self, discovered: &[Device]) -> Self {
        let mut this_dev = self
            .devices
            .iter()
            .find(|d| d.kind == DeviceKind::ThisDevice)
            .cloned()
            .unwrap_or_else(|| Device::this_device(self.this_device_selected));
        let mut others: Vec<Device> = discovered
            .iter()
            .filter(|d| d.kind != DeviceKind::ThisDevice)
            .map(|d| {
                let mut copy = d.clone();
                copy.selected =
                    d.is_cast_sink() && d.castable && self.selected_speaker_ids.iter().any(|id| id == &d.id);
                copy
            })
            .collect();
        let still_selected: Vec<String> = others
            .iter()
            .filter(|d| d.selected)
            .map(|d| d.id.clone())
            .collect();
        let this_selected = still_selected.is_empty();
        this_dev.selected = this_selected;
        this_dev.protocol = None;
        let mut devices = Vec::with_capacity(1 + others.len());
        devices.push(this_dev);
        devices.append(&mut others);
        Self {
            this_device_selected: this_selected,
            selected_speaker_ids: still_selected,
            devices,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RouteEffect {
    StopCastToThisDevice,
    StartCast { speaker_ids: Vec<String> },
    AddSpeaker { id: String },
    RemoveSpeaker { id: String },
    RejectNonCastable { id: String, reason: String },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RouteTransition {
    pub state: RouteState,
    pub effects: Vec<RouteEffect>,
}

// ---------------------------------------------------------------------------
// 零外部依赖 JSON 编解码（供 Android JNI 与 iOS C ABI 共享结构化契约）
// ---------------------------------------------------------------------------

pub(crate) fn json_escape(s: &str) -> String {
    let mut o = String::with_capacity(s.len() + 2);
    o.push('"');
    for ch in s.chars() {
        match ch {
            '"' => o.push_str("\\\""),
            '\\' => o.push_str("\\\\"),
            '\n' => o.push_str("\\n"),
            '\r' => o.push_str("\\r"),
            '\t' => o.push_str("\\t"),
            c if (c as u32) < 0x20 => o.push_str(&format!("\\u{:04x}", c as u32)),
            c => o.push(c),
        }
    }
    o.push('"');
    o
}

pub fn device_to_json(d: &Device) -> String {
    let proto = d
        .protocol
        .map(|p| json_escape(p.wire_name()))
        .unwrap_or_else(|| "null".into());
    format!(
        "{{\"id\":{},\"name\":{},\"kind\":{},\"castable\":{},\"selected\":{},\"detail\":{},\"protocol\":{}}}",
        json_escape(&d.id),
        json_escape(&d.name),
        json_escape(d.kind.as_str()),
        d.castable,
        d.selected,
        json_escape(&d.detail),
        proto,
    )
}

pub fn devices_to_json(devices: &[Device]) -> String {
    let items: Vec<String> = devices.iter().map(device_to_json).collect();
    format!("[{}]", items.join(","))
}

pub fn discovered_result_to_json(
    fresh: &[DiscoveredRow],
    merged: &[Device],
    cacheable: &[Device],
) -> String {
    let castable_count = fresh.iter().filter(|r| r.device.castable).count();
    let hidden_count = fresh.len().saturating_sub(castable_count);
    let rows_json: Vec<String> = fresh
        .iter()
        .map(|r| {
            format!(
                "{{\"device\":{},\"advertises_miplay\":{},\"service\":{},\"apps\":{},\"visible_by_default\":{},\"log_line\":{}}}",
                device_to_json(&r.device),
                r.advertises_miplay,
                json_escape(&r.service),
                json_escape(&r.apps),
                r.visible_by_default,
                json_escape(&r.log_line),
            )
        })
        .collect();
    format!(
        "{{\"fresh_count\":{},\"castable_count\":{},\"hidden_count\":{},\"rows\":[{}],\"merged\":{},\"cacheable\":{}}}",
        fresh.len(),
        castable_count,
        hidden_count,
        rows_json.join(","),
        devices_to_json(merged),
        devices_to_json(cacheable),
    )
}

pub fn route_state_to_json(state: &RouteState) -> String {
    let sel: Vec<String> = state.selected_speaker_ids.iter().map(|s| json_escape(s)).collect();
    format!(
        "{{\"this_device_selected\":{},\"casting\":{},\"selected_speaker_ids\":[{}],\"devices\":{}}}",
        state.this_device_selected,
        state.casting(),
        sel.join(","),
        devices_to_json(&state.devices),
    )
}

pub fn route_transition_to_json(t: &RouteTransition) -> String {
    let effects: Vec<String> = t
        .effects
        .iter()
        .map(|e| match e {
            RouteEffect::StopCastToThisDevice => "{\"type\":\"stop_cast_to_this_device\"}".into(),
            RouteEffect::StartCast { speaker_ids } => {
                let ids: Vec<String> = speaker_ids.iter().map(|s| json_escape(s)).collect();
                format!("{{\"type\":\"start_cast\",\"speaker_ids\":[{}]}}", ids.join(","))
            }
            RouteEffect::AddSpeaker { id } => {
                format!("{{\"type\":\"add_speaker\",\"id\":{}}}", json_escape(id))
            }
            RouteEffect::RemoveSpeaker { id } => {
                format!("{{\"type\":\"remove_speaker\",\"id\":{}}}", json_escape(id))
            }
            RouteEffect::RejectNonCastable { id, reason } => format!(
                "{{\"type\":\"reject_non_castable\",\"id\":{},\"reason\":{}}}",
                json_escape(id),
                json_escape(reason),
            ),
        })
        .collect();
    format!(
        "{{\"state\":{},\"effects\":[{}]}}",
        route_state_to_json(&t.state),
        effects.join(","),
    )
}

/// 从缓存 JSON 数组解析 `Vec<Device>`（兼容 Android `PrefsDiscoveredDeviceStore` 与未来 iOS `UserDefaults` 格式）。
/// 解析容错：损坏条目跳过，不 panic。
pub fn parse_devices_json(raw: &str) -> Vec<Device> {
    let mut out = Vec::new();
    let mut seen = HashSet::new();
    for obj in split_top_level_json_objects(raw) {
        let id = json_field_str(&obj, "id").unwrap_or_default().trim().to_string();
        if id.is_empty() || !seen.insert(id.clone()) {
            continue;
        }
        let name = json_field_str(&obj, "name")
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| id.clone());
        let kind = json_field_str(&obj, "kind")
            .map(|k| DeviceKind::from_str(&k))
            .unwrap_or(DeviceKind::Speaker);
        let castable = json_field_bool(&obj, "castable").unwrap_or(true);
        let selected = json_field_bool(&obj, "selected").unwrap_or(false);
        let detail = json_field_str(&obj, "detail").unwrap_or_default();
        let protocol = if kind == DeviceKind::ThisDevice {
            None
        } else {
            match json_field_str(&obj, "protocol") {
                Some(p) if !p.is_empty() => CastProtocol::from_str(&p),
                Some(_) => Some(CastProtocol::MiPlay),
                None => {
                    // 兼容旧版缓存：若字段显式为 null 则 None，缺字段时对可投设备默认 MiPlay
                    if obj.contains("\"protocol\":null") || obj.contains("\"protocol\": null") {
                        None
                    } else if castable {
                        Some(CastProtocol::MiPlay)
                    } else {
                        None
                    }
                }
            }
        };
        out.push(Device {
            id,
            name,
            kind,
            castable,
            selected,
            detail,
            protocol,
        });
    }
    out
}

/// 跨端 JSON 路由状态机入口：
/// `devices_json`: 当前设备列表 JSON 数组（含/不含 `this` 均可，含 `selected` 状态）
/// `action`: `"select_this"` | `"check"` | `"uncheck"` | `"merge_discovered"`
/// `target`: `check`/`uncheck` 时为设备 id；`merge_discovered` 时为新发现设备 JSON 数组
pub fn route_apply_json(devices_json: &str, action: &str, target: &str) -> String {
    let parsed = parse_devices_json(devices_json);
    let mut state = RouteState::initial(parsed.clone());
    // 保留调用方传入的已选状态
    let selected_ids: Vec<String> = parsed
        .iter()
        .filter(|d| d.is_cast_sink() && d.castable && d.selected)
        .map(|d| d.id.clone())
        .collect();
    if !selected_ids.is_empty() {
        state.this_device_selected = false;
        state.selected_speaker_ids = selected_ids.clone();
        for d in &mut state.devices {
            if d.kind == DeviceKind::ThisDevice {
                d.selected = false;
            } else {
                d.selected = selected_ids.iter().any(|id| id == &d.id);
            }
        }
    }
    let transition = match action.trim().to_ascii_lowercase().as_str() {
        "select_this" => state.select_this_device(),
        "check" => state.set_speaker_checked(target.trim(), true),
        "uncheck" => state.set_speaker_checked(target.trim(), false),
        "merge_discovered" => {
            let discovered = parse_devices_json(target);
            RouteTransition {
                state: state.merge_discovered(&discovered),
                effects: Vec::new(),
            }
        }
        _ => RouteTransition { state, effects: Vec::new() },
    };
    route_transition_to_json(&transition)
}

fn split_top_level_json_objects(raw: &str) -> Vec<String> {
    let mut objects = Vec::new();
    let mut depth = 0usize;
    let mut in_str = false;
    let mut escape = false;
    let mut start: Option<usize> = None;
    for (idx, ch) in raw.char_indices() {
        if in_str {
            if escape {
                escape = false;
            } else if ch == '\\' {
                escape = true;
            } else if ch == '"' {
                in_str = false;
            }
            continue;
        }
        match ch {
            '"' => in_str = true,
            '{' => {
                if depth == 0 {
                    start = Some(idx);
                }
                depth += 1;
            }
            '}' => {
                if depth > 0 {
                    depth -= 1;
                    if depth == 0 {
                        if let Some(s) = start.take() {
                            objects.push(raw[s..=idx].to_string());
                        }
                    }
                }
            }
            _ => {}
        }
    }
    objects
}

fn find_json_field_value_start(obj: &str, key: &str) -> Option<usize> {
    let needle = format!("\"{key}\"");
    let mut search_from = 0usize;
    while let Some(rel) = obj[search_from..].find(&needle) {
        let after_key = search_from + rel + needle.len();
        let rest = &obj[after_key..];
        let trimmed_len = rest.len() - rest.trim_start().len();
        let colon_pos = after_key + trimmed_len;
        if obj.as_bytes().get(colon_pos) == Some(&b':') {
            let val_rest = &obj[colon_pos + 1..];
            let ws = val_rest.len() - val_rest.trim_start().len();
            return Some(colon_pos + 1 + ws);
        }
        search_from = after_key;
    }
    None
}

fn json_field_str(obj: &str, key: &str) -> Option<String> {
    let start = find_json_field_value_start(obj, key)?;
    let bytes = obj.as_bytes();
    if *bytes.get(start)? != b'"' {
        return None;
    }
    let mut out = String::new();
    let mut chars = obj[start + 1..].chars();
    while let Some(ch) = chars.next() {
        match ch {
            '"' => return Some(out),
            '\\' => {
                let esc = chars.next()?;
                match esc {
                    '"' => out.push('"'),
                    '\\' => out.push('\\'),
                    '/' => out.push('/'),
                    'n' => out.push('\n'),
                    'r' => out.push('\r'),
                    't' => out.push('\t'),
                    'u' => {
                        let hex: String = chars.by_ref().take(4).collect();
                        if let Ok(code) = u32::from_str_radix(&hex, 16) {
                            if let Some(c) = char::from_u32(code) {
                                out.push(c);
                            }
                        }
                    }
                    other => out.push(other),
                }
            }
            c => out.push(c),
        }
    }
    None
}

fn json_field_bool(obj: &str, key: &str) -> Option<bool> {
    let start = find_json_field_value_start(obj, key)?;
    let rest = &obj[start..];
    if rest.starts_with("true") {
        Some(true)
    } else if rest.starts_with("false") {
        Some(false)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn seed() -> RouteState {
        RouteState::initial(vec![
            Device::this_device(true),
            Device {
                id: "s1".into(),
                name: "Sound Pro".into(),
                kind: DeviceKind::Speaker,
                castable: true,
                selected: false,
                detail: String::new(),
                protocol: Some(CastProtocol::MiPlay),
            },
            Device {
                id: "s2".into(),
                name: "Bedroom Speaker".into(),
                kind: DeviceKind::Speaker,
                castable: true,
                selected: false,
                detail: String::new(),
                protocol: Some(CastProtocol::MiPlay),
            },
            Device {
                id: "tv".into(),
                name: "Living Room REDMI G Pro 27U".into(),
                kind: DeviceKind::Tv,
                castable: true,
                selected: false,
                detail: String::new(),
                protocol: Some(CastProtocol::MiPlay),
            },
        ])
    }

    #[test]
    fn auth_mode_parse_and_coerce() {
        assert_eq!(parse_auth_mode("auto"), Some(0));
        assert_eq!(parse_auth_mode("LEGACY"), Some(1));
        assert_eq!(parse_auth_mode(" safety "), Some(2));
        assert_eq!(parse_auth_mode("unknown"), None);
        assert_eq!(coerce_auth_mode(-1), 0);
        assert_eq!(coerce_auth_mode(99), 2);
        assert_eq!(auth_mode_label(0), "auto");
        assert_eq!(auth_mode_label(1), "legacy");
        assert_eq!(auth_mode_label(2), "safety");
    }

    #[test]
    fn route_multi_select_and_fallback_to_this_device() {
        let s0 = seed();
        assert!(s0.this_device_selected);
        let t1 = s0.set_speaker_checked("s1", true);
        assert!(!t1.state.this_device_selected);
        assert_eq!(t1.state.selected_speaker_ids, vec!["s1"]);
        assert_eq!(t1.effects, vec![RouteEffect::StartCast { speaker_ids: vec!["s1".into()] }]);

        let t2 = t1.state.set_speaker_checked("tv", true);
        assert_eq!(t2.state.selected_speaker_ids, vec!["s1", "tv"]);
        assert_eq!(t2.effects, vec![RouteEffect::AddSpeaker { id: "tv".into() }]);

        let t3 = t2.state.set_speaker_checked("s1", false);
        assert_eq!(t3.state.selected_speaker_ids, vec!["tv"]);
        assert_eq!(t3.effects, vec![RouteEffect::RemoveSpeaker { id: "s1".into() }]);

        let t4 = t3.state.set_speaker_checked("tv", false);
        assert!(t4.state.this_device_selected);
        assert!(t4.state.selected_speaker_ids.is_empty());
        assert_eq!(t4.effects, vec![RouteEffect::StopCastToThisDevice]);
    }

    #[test]
    fn non_miplay_lyra_hidden_by_default_and_rejected() {
        let row = parse_discovered_line("192.0.2.182\tB5A83339\tB5A83339\t0\t_lyra-mdns._udp.local\t")
            .expect("row");
        assert!(!row.device.castable);
        assert_eq!(row.device.protocol, None);
        assert!(!row.visible_by_default);

        let st = RouteState::initial(vec![Device::this_device(true), row.device.clone()]);
        assert_eq!(filter_visible_devices(&st.devices, false).len(), 1);
        assert_eq!(filter_visible_devices(&st.devices, true).len(), 2);

        let rej = st.set_speaker_checked("192.0.2.182", true);
        assert!(matches!(rej.effects.as_slice(), [RouteEffect::RejectNonCastable { .. }]));
    }

    #[test]
    fn legitimate_tv_and_hex_miplay_speaker_visible_and_castable() {
        let tv = parse_discovered_line("192.0.2.50\tLiving Room TV\tMiTV\t0\t_mi-connect._udp.local\t")
            .expect("tv");
        assert_eq!(tv.device.kind, DeviceKind::Tv);
        assert!(tv.device.castable);
        assert_eq!(tv.device.protocol, Some(CastProtocol::MiPlay));

        let spk = parse_discovered_line("192.0.2.34\tA1B2C3D4\tL17A\t1\t_mi-connect._udp.local\t[5]")
            .expect("spk");
        assert!(spk.device.castable);
        assert_eq!(spk.device.name, "A1B2C3D4 · L17A");
    }

    #[test]
    fn cache_merge_and_json_roundtrip() {
        let cached = vec![
            Device {
                id: "192.0.2.34".into(),
                name: "Speaker A · L17A".into(),
                kind: DeviceKind::Speaker,
                castable: true,
                selected: false,
                detail: String::new(),
                protocol: Some(CastProtocol::MiPlay),
            },
            Device {
                id: "192.0.2.35".into(),
                name: "Speaker B · LX06".into(),
                kind: DeviceKind::Speaker,
                castable: true,
                selected: false,
                detail: String::new(),
                protocol: Some(CastProtocol::MiPlay),
            },
        ];
        let json = devices_to_json(&cached);
        let parsed = parse_devices_json(&json);
        assert_eq!(parsed, cached);

        let fresh = vec![cached[0].clone()];
        let merged = merge_cached_and_discovered(&parsed, &fresh);
        assert_eq!(merged.len(), 2);
        assert_eq!(merged[0].id, "192.0.2.34");
        assert_eq!(merged[1].id, "192.0.2.35");
    }

    #[test]
    fn route_apply_json_drives_check_and_uncheck() {
        let initial_json = devices_to_json(&seed().devices);
        let after_check = route_apply_json(&initial_json, "check", "s1");
        assert!(after_check.contains("\"this_device_selected\":false"));
        assert!(after_check.contains("\"type\":\"start_cast\""));
        assert!(after_check.contains("\"speaker_ids\":[\"s1\"]"));
    }
}
