//! 跨端音量过大保护策略（第 1 层 Rust 单一事实来源，Android 与 iOS 共享）。
//!
//! 规则（仅在「跨过安全上限线」时触发二次确认）：
//! - 保护关闭：任意 `0..=100` 直接放行；
//! - 保护开启（默认开启，默认安全上限 `60%`，合法配置范围 `30%..=90%`）：
//!   - 目标音量 `<= safe_limit`：直接放行（包含从高音量区降回安全区）；
//!   - 当前音量 `> safe_limit` 且目标音量 `> safe_limit`：已在高音量区内微调，直接放行；
//!   - 当前音量 `<= safe_limit` 且目标音量 `> safe_limit`：跨过安全上限线，要求用户二次确认。
//!     一旦降回安全区（`<= safe_limit`），下次再跨过上限线会重新触发二次确认。

use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};

pub const DEFAULT_SAFE_VOLUME_LIMIT: u8 = 60;
pub const MIN_SAFE_VOLUME_LIMIT: u8 = 30;
pub const MAX_SAFE_VOLUME_LIMIT: u8 = 90;
pub const SAFE_VOLUME_STEP: u8 = 5;

static GLOBAL_GUARD_ENABLED: AtomicBool = AtomicBool::new(true);
static GLOBAL_SAFE_LIMIT: AtomicU8 = AtomicU8::new(DEFAULT_SAFE_VOLUME_LIMIT);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct VolumeGuardPolicy {
    pub enabled: bool,
    pub safe_limit: u8,
}

impl Default for VolumeGuardPolicy {
    fn default() -> Self {
        Self {
            enabled: true,
            safe_limit: DEFAULT_SAFE_VOLUME_LIMIT,
        }
    }
}

impl VolumeGuardPolicy {
    pub fn new(enabled: bool, safe_limit: i32) -> Self {
        Self {
            enabled,
            safe_limit: normalize_safe_limit(safe_limit),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VolumeChangeDecision {
    Allow {
        volume: u8,
    },
    RequireConfirm {
        current_volume: u8,
        target_volume: u8,
        safe_limit: u8,
    },
}

/// 将用户配置的安全音量上限归一化至 `[MIN_SAFE_VOLUME_LIMIT, MAX_SAFE_VOLUME_LIMIT]`（30..=90）。
pub fn normalize_safe_limit(raw: i32) -> u8 {
    raw.clamp(MIN_SAFE_VOLUME_LIMIT as i32, MAX_SAFE_VOLUME_LIMIT as i32) as u8
}

/// 更新全局音量保护策略（供 Android/iOS 启动及设置变更时同步到 L1 引擎）。
pub fn set_global_policy(enabled: bool, safe_limit: i32) -> VolumeGuardPolicy {
    let policy = VolumeGuardPolicy::new(enabled, safe_limit);
    GLOBAL_GUARD_ENABLED.store(policy.enabled, Ordering::SeqCst);
    GLOBAL_SAFE_LIMIT.store(policy.safe_limit, Ordering::SeqCst);
    policy
}

/// 读取当前全局音量保护策略。
pub fn global_policy() -> VolumeGuardPolicy {
    VolumeGuardPolicy {
        enabled: GLOBAL_GUARD_ENABLED.load(Ordering::SeqCst),
        safe_limit: normalize_safe_limit(GLOBAL_SAFE_LIMIT.load(Ordering::SeqCst) as i32),
    }
}

/// 裁决单路音量变更是否需要二次确认。
pub fn evaluate_volume_change(
    policy: VolumeGuardPolicy,
    current_volume: i32,
    target_volume: i32,
) -> VolumeChangeDecision {
    let current = current_volume.clamp(0, 100) as u8;
    let target = target_volume.clamp(0, 100) as u8;
    let limit = normalize_safe_limit(policy.safe_limit as i32);
    if policy.enabled && target > limit && current <= limit {
        VolumeChangeDecision::RequireConfirm {
            current_volume: current,
            target_volume: target,
            safe_limit: limit,
        }
    } else {
        VolumeChangeDecision::Allow { volume: target }
    }
}

/// 裁决多音箱总音量变更：只要总音量或任意受影响音箱当前处于安全区（`<= safe_limit`）且目标 `> safe_limit`，
/// 即视为跨过安全上限线并要求二次确认。
pub fn evaluate_group_volume_change(
    policy: VolumeGuardPolicy,
    master_current: i32,
    speaker_currents: &[u8],
    target_volume: i32,
) -> VolumeChangeDecision {
    let master = master_current.clamp(0, 100) as u8;
    let target = target_volume.clamp(0, 100) as u8;
    let limit = normalize_safe_limit(policy.safe_limit as i32);
    let any_in_safe_zone = master <= limit || speaker_currents.iter().any(|&v| v <= limit);
    if policy.enabled && target > limit && any_in_safe_zone {
        VolumeChangeDecision::RequireConfirm {
            current_volume: master,
            target_volume: target,
            safe_limit: limit,
        }
    } else {
        VolumeChangeDecision::Allow { volume: target }
    }
}

/// L1 引擎层安全兜底：未获得用户确认的跨线音量强行钳制到 `safe_limit`，返回 `(实际下发音量, 是否发生钳制)`。
pub fn enforce_engine_volume(
    policy: VolumeGuardPolicy,
    current_volume: i32,
    target_volume: i32,
    confirmed: bool,
) -> (u8, bool) {
    let target = target_volume.clamp(0, 100) as u8;
    if confirmed {
        return (target, false);
    }
    match evaluate_volume_change(policy, current_volume, target as i32) {
        VolumeChangeDecision::Allow { volume } => (volume, false),
        VolumeChangeDecision::RequireConfirm { safe_limit, .. } => (safe_limit, true),
    }
}

/// 序列化单路裁决结果为跨端统一 JSON。
pub fn evaluate_volume_change_json(
    enabled: bool,
    safe_limit: i32,
    current_volume: i32,
    target_volume: i32,
) -> String {
    let policy = VolumeGuardPolicy::new(enabled, safe_limit);
    match evaluate_volume_change(policy, current_volume, target_volume) {
        VolumeChangeDecision::Allow { volume } => format!(
            "{{\"decision\":\"allow\",\"volume\":{volume},\"safe_limit\":{}}}",
            policy.safe_limit
        ),
        VolumeChangeDecision::RequireConfirm {
            current_volume,
            target_volume,
            safe_limit,
        } => format!(
            "{{\"decision\":\"require_confirm\",\"current_volume\":{current_volume},\"target_volume\":{target_volume},\"safe_limit\":{safe_limit}}}"
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalize_limit_clamps_to_valid_range() {
        assert_eq!(normalize_safe_limit(0), MIN_SAFE_VOLUME_LIMIT);
        assert_eq!(normalize_safe_limit(29), MIN_SAFE_VOLUME_LIMIT);
        assert_eq!(normalize_safe_limit(60), 60);
        assert_eq!(normalize_safe_limit(95), MAX_SAFE_VOLUME_LIMIT);
        assert_eq!(normalize_safe_limit(100), MAX_SAFE_VOLUME_LIMIT);
    }

    #[test]
    fn crossing_threshold_requires_confirm_and_in_high_zone_allows() {
        let p = VolumeGuardPolicy::default(); // enabled=true, safe_limit=60

        // 安全区内自由调节
        assert_eq!(
            evaluate_volume_change(p, 30, 60),
            VolumeChangeDecision::Allow { volume: 60 }
        );
        // 从安全区跨过上限线 -> 必须确认
        assert_eq!(
            evaluate_volume_change(p, 30, 61),
            VolumeChangeDecision::RequireConfirm {
                current_volume: 30,
                target_volume: 61,
                safe_limit: 60,
            }
        );
        assert_eq!(
            evaluate_volume_change(p, 60, 80),
            VolumeChangeDecision::RequireConfirm {
                current_volume: 60,
                target_volume: 80,
                safe_limit: 60,
            }
        );
        // 已经在高音量区内继续微调（升/降） -> 不重复弹窗
        assert_eq!(
            evaluate_volume_change(p, 70, 85),
            VolumeChangeDecision::Allow { volume: 85 }
        );
        assert_eq!(
            evaluate_volume_change(p, 85, 70),
            VolumeChangeDecision::Allow { volume: 70 }
        );
        // 从高音量区降回安全区 -> 直接放行
        assert_eq!(
            evaluate_volume_change(p, 80, 50),
            VolumeChangeDecision::Allow { volume: 50 }
        );
        // 降回安全区后再次跨过上限线 -> 重新触发确认
        assert_eq!(
            evaluate_volume_change(p, 50, 75),
            VolumeChangeDecision::RequireConfirm {
                current_volume: 50,
                target_volume: 75,
                safe_limit: 60,
            }
        );
    }

    #[test]
    fn disabled_policy_always_allows() {
        let p = VolumeGuardPolicy::new(false, 60);
        assert_eq!(
            evaluate_volume_change(p, 10, 100),
            VolumeChangeDecision::Allow { volume: 100 }
        );
        assert_eq!(enforce_engine_volume(p, 10, 100, false), (100, false));
    }

    #[test]
    fn group_volume_triggers_confirm_if_any_speaker_crosses_limit() {
        let p = VolumeGuardPolicy::default();
        // 音箱 A 已在 75%，音箱 B 在 30%，总音量拉到 80% -> 音箱 B 会跨线，需要确认
        assert_eq!(
            evaluate_group_volume_change(p, 75, &[75, 30], 80),
            VolumeChangeDecision::RequireConfirm {
                current_volume: 75,
                target_volume: 80,
                safe_limit: 60,
            }
        );
        // 所有受影响音箱与总音量均已在高音量区 -> 直接放行
        assert_eq!(
            evaluate_group_volume_change(p, 75, &[75, 70], 85),
            VolumeChangeDecision::Allow { volume: 85 }
        );
    }

    #[test]
    fn enforce_engine_volume_clamps_unconfirmed_crossing_and_allows_confirmed() {
        let p = VolumeGuardPolicy::new(true, 60);
        assert_eq!(enforce_engine_volume(p, 30, 90, false), (60, true));
        assert_eq!(enforce_engine_volume(p, 30, 90, true), (90, false));
        assert_eq!(enforce_engine_volume(p, 75, 90, false), (90, false));
    }

    #[test]
    fn evaluate_json_emits_expected_fields() {
        let a = evaluate_volume_change_json(true, 60, 30, 50);
        assert_eq!(a, r#"{"decision":"allow","volume":50,"safe_limit":60}"#);
        let c = evaluate_volume_change_json(true, 60, 30, 85);
        assert_eq!(
            c,
            r#"{"decision":"require_confirm","current_volume":30,"target_volume":85,"safe_limit":60}"#
        );
    }
}
