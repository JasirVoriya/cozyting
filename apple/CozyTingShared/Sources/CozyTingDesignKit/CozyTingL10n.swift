import CozyTingCoreKit
import Foundation

/// Strongly-typed trilingual (`zh-CN` / `en` / `ja`) localization catalog shared across Apple clients (`M8`).
///
/// Supports instant in-app language switching (`AppLanguage.system`, `.zhCN`, `.en`, `.ja`) without
/// requiring an app restart, while strictly preserving brand/protocol decoupling (`窝爱听`/`CozyTing` vs `MiPlay`).
public enum L10nKey: String, CaseIterable, Sendable {
    // Common
    case commonClose
    case commonCancel
    case commonDone
    case commonApply
    case commonAdd
    case commonRefresh
    case commonManage
    case commonOpen
    case commonAuto
    case commonEnabled
    case commonDisabled
    case commonMacSpeakers

    // Theme & Language
    case themeSystem
    case themeLight
    case themeDark
    case langSystem

    // Status & Menu Bar
    case statusIdle
    case statusCastingCount
    case statusPausedCount
    case statusConnecting
    case statusReconnecting
    case statusError
    case menuPauseCast
    case menuResumeCast
    case menuSwitchBackToMac
    case menuOpenApp
    case menuSettings
    case menuQuitApp

    // Latency Tiers
    case latencyStandard
    case latencyBalanced
    case latencyLow
    case latencyUltra
    case latencyCustom
    case latencyGuideTitle
    case latencyGuideSubtitle
    case latencyGuideButton
    case latencyGoSettingsCustom
    case latencyStandardDesc
    case latencyBalancedDesc
    case latencyLowDesc
    case latencyUltraDesc

    // Now Playing & Volume Guard
    case nowPlayingSelectSource
    case nowPlayingCastingPrefix
    case nowPlayingLocalMuted
    case nowPlayingLocalKept
    case nowPlayingReadyLan
    case nowPlayingPlaying
    case nowPlayingPaused
    case themeCycleTooltip
    case volumeGuardTitle
    case volumeGuardMsgMaster
    case volumeGuardMsgSpeaker
    case volumeGuardKeep
    case volumeGuardConfirm

    // Audio Source Strip & Picker
    case sourceSectionTitle
    case sourceAutoBadge
    case sourceChooseApp
    case pickerTitle
    case pickerSummary
    case pickerSearchHint
    case pickerFavoritesHeader
    case pickerDragHint
    case pickerFavoritesEmpty
    case pickerOtherAppsHeader
    case pickerAddMoreApps
    case pickerAutoDetectOn
    case pickerAutoDetectOff
    case pickerRemove
    case pickerAddFav

    // Output Devices
    case outputDevicesTitle
    case restoreLast
    case restoreLastMulti
    case thisDeviceName

    // Settings (4 Cards)
    case settingsTitle
    case settingsSectionAppearance
    case settingsThemeTitle
    case settingsLanguageTitle
    case settingsShowAllTitle
    case settingsShowAllSub
    case settingsRescanBtn

    case settingsSectionAudio
    case settingsAudioPermTitle
    case settingsAudioPermSub
    case settingsOpenSystemSettings
    case settingsLocalMuteTitle
    case settingsLocalMuteSub
    case settingsVolumeGuardTitle
    case settingsVolumeGuardSub
    case settingsVolumeLimitTitle
    case settingsAutoDetectTitle
    case settingsAutoDetectSub
    case settingsHwVolKeysTitle
    case settingsHwVolKeysSub
    case settingsAccessibilityGranted
    case settingsAccessibilityNeeded

    case settingsSectionDebug
    case settingsLogsTitle
    case settingsLogsSub
    case settingsAdvancedHeader
    case settingsAdvancedSub
    case settingsManualIPLabel
    case settingsManualIPHint
    case settingsManualIPInvalid
    case settingsManualIPAdded
    case settingsSourceModeLabel
    case settingsSourceModeApp
    case settingsSourceModeSilent
    case settingsCustomDelayLabel
    case settingsCustomDelayInvalid
    case settingsRefModeLabel
    case settingsRefAuto
    case settingsRefSingle
    case settingsRefMulti
    case settingsRefStereo
    case settingsAuthModeLabel
    case settingsAuthAuto
    case settingsAuthLegacy
    case settingsAuthSafety

    // About & Sponsor
    case settingsSectionAbout
    case aboutTitle
    case aboutCheckUpdates
    case aboutChecking
    case aboutUpToDate
    case aboutNewVersionAvailable
    case aboutCheckFailed
    case aboutBetaUpdatesTitle
    case aboutBetaUpdatesSub
    case aboutReleasesTitle
    case aboutReleasesSub
    case aboutSponsorTitle
    case aboutSponsorSub
    case sponsorDialogTitle
    case sponsorDialogDesc
    case sponsorWeChatLabel
    case sponsorAlipayLabel
    case sponsorSaveQR
    case sponsorSavedToast
    case aboutFooterNote

    // Log Viewer
    case logViewerTitle
    case logViewerSummary
    case logViewerEmpty
    case logViewerEmptyFiltered
    case logViewerFilterAll
    case logViewerFilterWarnErr
    case logViewerFilterCore
    case logViewerFilterOps
    case logViewerSearchHint
    case logViewerModeStructured
    case logViewerModeRaw
    case logViewerJumpLatest
    case logViewerExport
    case logViewerCopy
    case logViewerCopied
    case logViewerClear
    case logViewerClearTitle
    case logViewerClearMsg
    case logViewerClearConfirm
    case logViewerWarnBadge
    case logViewerErrBadge
    case logViewerJsonItems

    // Updates (M9)
    case updateBannerFound
    case updateBannerView
    case updateDialogTitle
    case updateDialogPreviewBadge
    case updateDialogDownloadDMG
    case updateDialogOpenRelease
    case updateDialogRemindLater
    case updateDialogSkipVersion
    case updateDialogVerifying
    case updateDialogVerifiedOpen
    case updateDialogUnsupportedOS
    case updateSecAdded
    case updateSecChanged
    case updateSecFixed
    case updateSecRemoved
    case updateSecSecurity

    // Live Error Notices, Standby & macOS Firewall Guidance
    case errAppNotRunning
    case errLaunchAndCast
    case errAudioPermission
    case errLocalNetwork
    case errFirewallBlocked
    case errOpenFirewallSettings
    case errOpenLocalNetworkSettings
    case errRetry
    case errCastFailed
    case errNoAudioSource
    case sourceNotRunningHint
    case sourceWaitingAudioHint
    case settingsFirewallTitle
    case settingsFirewallSub
    case settingsOpenFirewall
    case errSpeakerSeized
}

public enum CozyTingL10n {
    public enum ResolvedLocale: String, CaseIterable, Sendable {
        case zhCN = "zh-CN"
        case en = "en"
        case ja = "ja"
    }

    public static func resolve(_ language: AppLanguage, systemLocale: Locale = .autoupdatingCurrent) -> ResolvedLocale {
        switch language {
        case .zhCN: return .zhCN
        case .en: return .en
        case .ja: return .ja
        case .system:
            let code = systemLocale.language.languageCode?.identifier.lowercased()
                ?? systemLocale.identifier.lowercased()
            if code.hasPrefix("zh") { return .zhCN }
            if code.hasPrefix("ja") { return .ja }
            return .en
        }
    }

    public static func text(
        _ key: L10nKey,
        language: AppLanguage,
        systemLocale: Locale = .autoupdatingCurrent
    ) -> String {
        let loc = resolve(language, systemLocale: systemLocale)
        switch loc {
        case .zhCN: return zhCN[key] ?? en[key] ?? key.rawValue
        case .en: return en[key] ?? key.rawValue
        case .ja: return ja[key] ?? en[key] ?? key.rawValue
        }
    }

    public static func format(
        _ key: L10nKey,
        language: AppLanguage,
        systemLocale: Locale = .autoupdatingCurrent,
        _ args: CVarArg...
    ) -> String {
        let template = text(key, language: language, systemLocale: systemLocale)
        return String(format: template, locale: systemLocale, arguments: args)
    }

    // MARK: - Complete Trilingual Dictionaries

    public static let zhCN: [L10nKey: String] = [
        .commonClose: "关闭",
        .commonCancel: "取消",
        .commonDone: "完成",
        .commonApply: "生效",
        .commonAdd: "加入",
        .commonRefresh: "刷新",
        .commonManage: "管理",
        .commonOpen: "打开",
        .commonAuto: "自动",
        .commonEnabled: "已开启",
        .commonDisabled: "未开启",
        .commonMacSpeakers: "Mac 扬声器",

        .themeSystem: "跟随系统",
        .themeLight: "浅色",
        .themeDark: "深色",
        .langSystem: "跟随系统",

        .statusIdle: "本机出声",
        .statusCastingCount: "投送中 · %d 台",
        .statusPausedCount: "已暂停 · %d 台",
        .statusConnecting: "连接中…",
        .statusReconnecting: "重连中…",
        .statusError: "连接异常",
        .menuPauseCast: "暂停投送",
        .menuResumeCast: "恢复投送",
        .menuSwitchBackToMac: "切回本机播放",
        .menuOpenApp: "打开 %@",
        .menuSettings: "设置…",
        .menuQuitApp: "退出 %@",

        .latencyStandard: "标准",
        .latencyBalanced: "均衡",
        .latencyLow: "低延迟",
        .latencyUltra: "极速",
        .latencyCustom: "自定义",
        .latencyGuideTitle: "延迟档位说明",
        .latencyGuideSubtitle: "投送中切换档位通过 TIME_OFFSET 实时热重锚生效，无需断开重连。",
        .latencyGuideButton: "档位说明",
        .latencyGoSettingsCustom: "去设置自定义 ms",
        .latencyStandardDesc: "5GHz 800ms / 2.4GHz 或无频段回退 1000ms，穿墙与多箱最稳定。",
        .latencyBalancedDesc: "400ms 日常主力档，多音箱同步稳定且切歌响应更快。",
        .latencyLowDesc: "250ms 低延迟档，适合 5GHz 强信号或有线网跟手播控。",
        .latencyUltraDesc: "150ms 极速缓冲，仅建议同房间低抖动网络单音箱使用。",

        .nowPlayingSelectSource: "选择音频源 App",
        .nowPlayingCastingPrefix: "正在投送 · %@",
        .nowPlayingLocalMuted: "已静音本机该应用",
        .nowPlayingLocalKept: "保留本机出声",
        .nowPlayingReadyLan: "纯局域网直连就绪",
        .nowPlayingPlaying: "正在播放",
        .nowPlayingPaused: "已暂停",
        .themeCycleTooltip: "切换外观（当前：%@）",
        .volumeGuardTitle: "音量超过安全上限（%d%%）",
        .volumeGuardMsgMaster: "确定要将总音量从 %d%% 调至 %d%% 吗？",
        .volumeGuardMsgSpeaker: "确定要将「%@」的音量从 %d%% 调至 %d%% 吗？",
        .volumeGuardKeep: "保持 %d%%",
        .volumeGuardConfirm: "调至 %d%%",

        .sourceSectionTitle: "音源",
        .sourceAutoBadge: "自动识别",
        .sourceChooseApp: "选择音源 App…",
        .pickerTitle: "常用音源与选择",
        .pickerSummary: "常用音源 %d 个（自动识别 %d 个）· 点击行设为当前音源",
        .pickerSearchHint: "搜索应用名称或 Bundle ID…",
        .pickerFavoritesHeader: "常用音源 (%d)",
        .pickerDragHint: "拖动排序",
        .pickerFavoritesEmpty: "暂无常用音源 · 在下方点击「+ 常用」添加",
        .pickerOtherAppsHeader: "其他应用 (%d)",
        .pickerAddMoreApps: "添加更多应用 (%d)",
        .pickerAutoDetectOn: "自动识别：允许",
        .pickerAutoDetectOff: "自动识别：关闭",
        .pickerRemove: "移出常用",
        .pickerAddFav: "+ 常用",

        .outputDevicesTitle: "输出设备",
        .restoreLast: "恢复上次",
        .restoreLastMulti: "恢复上次 · %d台",
        .thisDeviceName: "本机播放",

        .settingsTitle: "设置",
        .settingsSectionAppearance: "外观与发现",
        .settingsThemeTitle: "主题模式",
        .settingsLanguageTitle: "显示语言",
        .settingsShowAllTitle: "显示全部发现设备",
        .settingsShowAllSub: "包含未声明 MiPlay 能力的局域网节点（兜底排查用）",
        .settingsRescanBtn: "立即重新扫描局域网设备",

        .settingsSectionAudio: "音频与系统权限",
        .settingsAudioPermTitle: "系统音频录制权限（Process Tap）",
        .settingsAudioPermSub: "仅捕获所选单个音乐 App 的播放流，不录制屏幕、不采集麦克风",
        .settingsOpenSystemSettings: "打开系统设置",
        .settingsLocalMuteTitle: "投送时单 App 本机静音",
        .settingsLocalMuteSub: "仅静音正在投送的音乐 App，Mac 其他应用照常出声",
        .settingsVolumeGuardTitle: "高音量保护",
        .settingsVolumeGuardSub: "从安全区调高音量跨过上限时弹窗确认，防止误触过吵",
        .settingsVolumeLimitTitle: "安全音量上限",
        .settingsAutoDetectTitle: "发声自动识别热切音源",
        .settingsAutoDetectSub: "仅从常用音源白名单内开启自动识别的 App 跟随，过滤系统提示音",
        .settingsHwVolKeysTitle: "接管硬件音量键（F11 / F12）",
        .settingsHwVolKeysSub: "可选高级项：需在「系统设置 → 辅助功能」授权，仅投送中生效（默认关闭）",
        .settingsAccessibilityGranted: "已授权辅助功能",
        .settingsAccessibilityNeeded: "需授予辅助功能权限",

        .settingsSectionDebug: "连接与调试",
        .settingsLogsTitle: "运行日志",
        .settingsLogsSub: "本地记录 · 当前占用 %@ · 点击浏览或导出",
        .settingsAdvancedHeader: "连接参数、手动 IP 与调试选项",
        .settingsAdvancedSub: "默认自动配置即可；排查网络或特殊音箱时再展开",
        .settingsManualIPLabel: "手动添加音箱 IP",
        .settingsManualIPHint: "例如 192.168.1.100",
        .settingsManualIPInvalid: "请输入合法 IPv4 地址",
        .settingsManualIPAdded: "已加入 %@，可在主界面勾选投送",
        .settingsSourceModeLabel: "捕获音源模式",
        .settingsSourceModeApp: "选定 App",
        .settingsSourceModeSilent: "测试静音",
        .settingsCustomDelayLabel: "自定义播放延迟 ms（0 为自动；范围 50–5000）",
        .settingsCustomDelayInvalid: "请输入 0 或 50–5000 之间的毫秒数",
        .settingsRefModeLabel: "组网参考模式（ref_function）",
        .settingsRefAuto: "自动",
        .settingsRefSingle: "单箱",
        .settingsRefMulti: "多箱",
        .settingsRefStereo: "立体声",
        .settingsAuthModeLabel: "8899 认证链路",
        .settingsAuthAuto: "自动",
        .settingsAuthLegacy: "明文 Legacy",
        .settingsAuthSafety: "Safety 加密",

        .settingsSectionAbout: "关于与更新",
        .aboutTitle: "关于窝爱听",
        .aboutCheckUpdates: "检查更新",
        .aboutChecking: "检测中…",
        .aboutUpToDate: "已是最新",
        .aboutNewVersionAvailable: "发现新版本 %@",
        .aboutCheckFailed: "检查失败",
        .aboutBetaUpdatesTitle: "接收测试版更新",
        .aboutBetaUpdatesSub: "开启后将优先获取先行预览版（RC）",
        .aboutReleasesTitle: "版本发布与更新日志",
        .aboutReleasesSub: "GitHub Releases · AGPL-3.0 开源",
        .aboutSponsorTitle: "赞赏支持",
        .aboutSponsorSub: "请作者喝杯热茶 · 开源免费、纯属自愿",
        .sponsorDialogTitle: "请作者喝杯热茶",
        .sponsorDialogDesc: "窝爱听所有功能永久免费开源、纯局域网零云端，绝无付费墙或隐藏特权。如果它帮你盘活了家里的小爱音箱，欢迎随心自愿支持。",
        .sponsorWeChatLabel: "微信赞赏码",
        .sponsorAlipayLabel: "支付宝收款码",
        .sponsorSaveQR: "保存二维码",
        .sponsorSavedToast: "已保存至下载目录",
        .aboutFooterNote: "纯局域网 · 零云端 · AGPL-3.0",

        .logViewerTitle: "运行日志",
        .logViewerSummary: "%d 行 · 本地占用 %@",
        .logViewerEmpty: "暂无日志记录",
        .logViewerEmptyFiltered: "当前筛选条件下暂无日志",
        .logViewerFilterAll: "全部",
        .logViewerFilterWarnErr: "告警/错误",
        .logViewerFilterCore: "核心协议",
        .logViewerFilterOps: "操作流水",
        .logViewerSearchHint: "搜索关键词、IP、设备名或标签…",
        .logViewerModeStructured: "结构化",
        .logViewerModeRaw: "原始",
        .logViewerJumpLatest: "最新",
        .logViewerExport: "导出 .txt",
        .logViewerCopy: "复制",
        .logViewerCopied: "已复制到剪贴板",
        .logViewerClear: "清空",
        .logViewerClearTitle: "清空本地日志？",
        .logViewerClearMsg: "将删除本机保存的运行日志、核心协议日志与操作流水记录，且无法恢复。",
        .logViewerClearConfirm: "确认清空",
        .logViewerWarnBadge: "%d 警告",
        .logViewerErrBadge: "%d 错误",
        .logViewerJsonItems: "%d 项 · %@",

        .updateBannerFound: "发现新版本 %@",
        .updateBannerView: "查看",
        .updateDialogTitle: "发现新版本",
        .updateDialogPreviewBadge: "预览版",
        .updateDialogDownloadDMG: "下载并校验 DMG",
        .updateDialogOpenRelease: "前往发布页查看",
        .updateDialogRemindLater: "稍后提醒",
        .updateDialogSkipVersion: "跳过此版本",
        .updateDialogVerifying: "正在校验 SHA-256…",
        .updateDialogVerifiedOpen: "校验通过 · 在 Finder 中打开",
        .updateDialogUnsupportedOS: "当前 macOS 版本低于该新版本最低要求。",
        .updateSecAdded: "新增",
        .updateSecChanged: "优化与变更",
        .updateSecFixed: "问题修复",
        .updateSecRemoved: "移除",
        .updateSecSecurity: "安全",

        .errAppNotRunning: "「%@」尚未启动，启动后即可投送其音频。",
        .errLaunchAndCast: "启动并投送",
        .errAudioPermission: "需要授予「系统音频录制」权限才能捕获所选应用声音。",
        .errLocalNetwork: "无法访问局域网，请在系统设置允许「本地网络」后重试。",
        .errFirewallBlocked: "音箱回连超时，可能被 macOS 防火墙拦截；请在防火墙允许 CozyTing 传入连接。",
        .errOpenFirewallSettings: "打开防火墙设置",
        .errOpenLocalNetworkSettings: "打开本地网络设置",
        .errRetry: "重试",
        .errCastFailed: "投送连接失败：%@",
        .errNoAudioSource: "请先选择一个音源应用后再投送。",
        .sourceNotRunningHint: "未运行 · 点击图标启动",
        .sourceWaitingAudioHint: "等待「%@」开始播放…",
        .settingsFirewallTitle: "macOS 防火墙与局域网接入",
        .settingsFirewallSub: "MiPlay 需要音箱反向连回本机；若开启了防火墙请允许 CozyTing 传入连接",
        .settingsOpenFirewall: "打开防火墙",
        .errSpeakerSeized: "检测到局域网内其他设备（如手机端窝爱听或小米播控）正在抢占「%@」，导致反复断开重连；请先停止另一台设备的投送。",
    ]

    public static let en: [L10nKey: String] = [
        .commonClose: "Close",
        .commonCancel: "Cancel",
        .commonDone: "Done",
        .commonApply: "Apply",
        .commonAdd: "Add",
        .commonRefresh: "Refresh",
        .commonManage: "Manage",
        .commonOpen: "Open",
        .commonAuto: "Auto",
        .commonEnabled: "Enabled",
        .commonDisabled: "Disabled",
        .commonMacSpeakers: "Mac Speakers",

        .themeSystem: "System",
        .themeLight: "Light",
        .themeDark: "Dark",
        .langSystem: "System",

        .statusIdle: "This Mac",
        .statusCastingCount: "Casting · %d",
        .statusPausedCount: "Paused · %d",
        .statusConnecting: "Connecting…",
        .statusReconnecting: "Reconnecting…",
        .statusError: "Connection error",
        .menuPauseCast: "Pause Cast",
        .menuResumeCast: "Resume Cast",
        .menuSwitchBackToMac: "Switch Back to Mac",
        .menuOpenApp: "Open %@",
        .menuSettings: "Settings…",
        .menuQuitApp: "Quit %@",

        .latencyStandard: "Standard",
        .latencyBalanced: "Balanced",
        .latencyLow: "Low",
        .latencyUltra: "Ultra",
        .latencyCustom: "Custom",
        .latencyGuideTitle: "Playback Latency Tiers",
        .latencyGuideSubtitle: "Switching tiers while casting live-reanchors TIME_OFFSET immediately without reconnecting.",
        .latencyGuideButton: "Latency Guide",
        .latencyGoSettingsCustom: "Custom ms in Settings",
        .latencyStandardDesc: "5 GHz 800ms / 2.4 GHz or fallback 1000ms. Best stability across rooms.",
        .latencyBalancedDesc: "400ms daily driver: stable multi-room sync with faster response.",
        .latencyLowDesc: "250ms snappy control on strong 5 GHz Wi-Fi or Ethernet.",
        .latencyUltraDesc: "150ms minimal buffer for a single speaker on low-jitter LAN.",

        .nowPlayingSelectSource: "Select Audio Source",
        .nowPlayingCastingPrefix: "Casting · %@",
        .nowPlayingLocalMuted: "Local app muted",
        .nowPlayingLocalKept: "Local audio kept",
        .nowPlayingReadyLan: "Pure LAN ready",
        .nowPlayingPlaying: "Playing",
        .nowPlayingPaused: "Paused",
        .themeCycleTooltip: "Appearance: %@",
        .volumeGuardTitle: "Volume Above Safe Limit (%d%%)",
        .volumeGuardMsgMaster: "Raise master volume from %d%% to %d%%?",
        .volumeGuardMsgSpeaker: "Raise \"%@\" volume from %d%% to %d%%?",
        .volumeGuardKeep: "Keep %d%%",
        .volumeGuardConfirm: "Set to %d%%",

        .sourceSectionTitle: "Source",
        .sourceAutoBadge: "Auto",
        .sourceChooseApp: "Choose Audio App…",
        .pickerTitle: "Favorite Audio Sources",
        .pickerSummary: "%d favorites (%d auto-detect) · Click row to select",
        .pickerSearchHint: "Search app name or bundle ID…",
        .pickerFavoritesHeader: "Favorites (%d)",
        .pickerDragHint: "Drag to reorder",
        .pickerFavoritesEmpty: "No favorite sources yet · Click \"+ Fav\" below to add apps",
        .pickerOtherAppsHeader: "Other Apps (%d)",
        .pickerAddMoreApps: "Add More Apps (%d)",
        .pickerAutoDetectOn: "Auto-detect: On",
        .pickerAutoDetectOff: "Auto-detect: Off",
        .pickerRemove: "Remove",
        .pickerAddFav: "+ Fav",

        .outputDevicesTitle: "Output Devices",
        .restoreLast: "Restore Last",
        .restoreLastMulti: "Last · %d",
        .thisDeviceName: "This Mac",

        .settingsTitle: "Settings",
        .settingsSectionAppearance: "Appearance & Discovery",
        .settingsThemeTitle: "Theme",
        .settingsLanguageTitle: "Language",
        .settingsShowAllTitle: "Show All Discovered Nodes",
        .settingsShowAllSub: "Include LAN nodes without MiPlay capability (troubleshooting)",
        .settingsRescanBtn: "Rescan LAN Devices Now",

        .settingsSectionAudio: "Audio & Permissions",
        .settingsAudioPermTitle: "System Audio Recording (Process Tap)",
        .settingsAudioPermSub: "Captures only the selected music app; never records screen or microphone",
        .settingsOpenSystemSettings: "Open System Settings",
        .settingsLocalMuteTitle: "Mute Target App Locally While Casting",
        .settingsLocalMuteSub: "Mutes only the cast music app while other Mac apps play normally",
        .settingsVolumeGuardTitle: "High-Volume Guard",
        .settingsVolumeGuardSub: "Ask confirmation when raising volume past the safe limit",
        .settingsVolumeLimitTitle: "Safe Volume Limit",
        .settingsAutoDetectTitle: "Auto-Detect Playing Source",
        .settingsAutoDetectSub: "Follows whitelisted Favorite apps outputting audio; filters short chimes",
        .settingsHwVolKeysTitle: "Hardware Volume Keys (F11 / F12)",
        .settingsHwVolKeysSub: "Optional: requires Accessibility permission, active only while casting (off by default)",
        .settingsAccessibilityGranted: "Accessibility Granted",
        .settingsAccessibilityNeeded: "Accessibility Needed",

        .settingsSectionDebug: "Connection & Debug",
        .settingsLogsTitle: "Runtime Logs",
        .settingsLogsSub: "Local only · %@ used · Browse or export",
        .settingsAdvancedHeader: "Connection Parameters, Manual IP & Debug",
        .settingsAdvancedSub: "Defaults work automatically; expand for troubleshooting",
        .settingsManualIPLabel: "Add Speaker IP Manually",
        .settingsManualIPHint: "e.g. 192.168.1.100",
        .settingsManualIPInvalid: "Enter a valid IPv4 address",
        .settingsManualIPAdded: "Added %@. Select it from the main panel to cast.",
        .settingsSourceModeLabel: "Capture Source Mode",
        .settingsSourceModeApp: "Selected App",
        .settingsSourceModeSilent: "Silent Test",
        .settingsCustomDelayLabel: "Custom Playback Delay ms (0 = Auto; 50–5000)",
        .settingsCustomDelayInvalid: "Enter 0 or a value between 50 and 5000 ms",
        .settingsRefModeLabel: "Group Reference Mode (ref_function)",
        .settingsRefAuto: "Auto",
        .settingsRefSingle: "Single",
        .settingsRefMulti: "Multi",
        .settingsRefStereo: "Stereo",
        .settingsAuthModeLabel: "Port 8899 Auth Mode",
        .settingsAuthAuto: "Auto",
        .settingsAuthLegacy: "Legacy",
        .settingsAuthSafety: "Safety",

        .settingsSectionAbout: "About & Updates",
        .aboutTitle: "About CozyTing",
        .aboutCheckUpdates: "Check for Updates",
        .aboutChecking: "Checking…",
        .aboutUpToDate: "Up to date",
        .aboutNewVersionAvailable: "Update %@ available",
        .aboutCheckFailed: "Check failed",
        .aboutBetaUpdatesTitle: "Receive Beta Updates",
        .aboutBetaUpdatesSub: "Get early access to preview (RC) builds before stable release",
        .aboutReleasesTitle: "Releases & Changelog",
        .aboutReleasesSub: "GitHub Releases · AGPL-3.0",
        .aboutSponsorTitle: "Support CozyTing",
        .aboutSponsorSub: "Buy the author a warm drink · 100% free & optional",
        .sponsorDialogTitle: "Buy the Author a Warm Drink",
        .sponsorDialogDesc: "CozyTing is 100% free, open-source, and pure-LAN with no paywalls or locked features. A voluntary tip helps keep development going.",
        .sponsorWeChatLabel: "WeChat Pay Tip",
        .sponsorAlipayLabel: "Alipay QR",
        .sponsorSaveQR: "Save QR",
        .sponsorSavedToast: "Saved to Downloads",
        .aboutFooterNote: "Pure LAN · Zero Cloud · AGPL-3.0",

        .logViewerTitle: "Runtime Logs",
        .logViewerSummary: "%d lines · %@ local",
        .logViewerEmpty: "No log entries yet",
        .logViewerEmptyFiltered: "No logs match the current filter",
        .logViewerFilterAll: "All",
        .logViewerFilterWarnErr: "Warn/Err",
        .logViewerFilterCore: "Core",
        .logViewerFilterOps: "Ops",
        .logViewerSearchHint: "Filter keyword, IP, speaker, tag…",
        .logViewerModeStructured: "Structured",
        .logViewerModeRaw: "Raw",
        .logViewerJumpLatest: "Latest",
        .logViewerExport: "Export .txt",
        .logViewerCopy: "Copy",
        .logViewerCopied: "Copied to clipboard",
        .logViewerClear: "Clear",
        .logViewerClearTitle: "Clear local logs?",
        .logViewerClearMsg: "Permanently deletes saved runtime, core protocol, and operation logs on this Mac.",
        .logViewerClearConfirm: "Clear",
        .logViewerWarnBadge: "%d Warn",
        .logViewerErrBadge: "%d Err",
        .logViewerJsonItems: "%d items · %@",

        .updateBannerFound: "Update available %@",
        .updateBannerView: "View",
        .updateDialogTitle: "New Version",
        .updateDialogPreviewBadge: "Preview",
        .updateDialogDownloadDMG: "Download & Verify DMG",
        .updateDialogOpenRelease: "View Release Page",
        .updateDialogRemindLater: "Later",
        .updateDialogSkipVersion: "Skip This Version",
        .updateDialogVerifying: "Verifying SHA-256…",
        .updateDialogVerifiedOpen: "Verified · Open in Finder",
        .updateDialogUnsupportedOS: "Your macOS version is below the minimum required by this release.",
        .updateSecAdded: "Added",
        .updateSecChanged: "Changed",
        .updateSecFixed: "Fixed",
        .updateSecRemoved: "Removed",
        .updateSecSecurity: "Security",

        .errAppNotRunning: "\"%@\" is not running yet. Launch it to cast its audio.",
        .errLaunchAndCast: "Launch & Cast",
        .errAudioPermission: "System Audio Recording permission is required to capture app audio.",
        .errLocalNetwork: "Local Network access denied or unreachable. Allow it in System Settings and retry.",
        .errFirewallBlocked: "Speaker couldn't connect back (likely blocked by macOS Firewall). Allow incoming connections for CozyTing.",
        .errOpenFirewallSettings: "Firewall Settings",
        .errOpenLocalNetworkSettings: "Local Network Settings",
        .errRetry: "Retry",
        .errCastFailed: "Cast connection failed: %@",
        .errNoAudioSource: "Choose an audio source app before casting.",
        .sourceNotRunningHint: "Not running · Click icon to launch",
        .sourceWaitingAudioHint: "Waiting for %@ to play audio…",
        .settingsFirewallTitle: "macOS Firewall & Local Network",
        .settingsFirewallSub: "MiPlay speakers connect back to your Mac; allow CozyTing if Firewall is enabled",
        .settingsOpenFirewall: "Firewall Settings",
        .errSpeakerSeized: "Another device on your LAN (such as CozyTing on a phone) is seizing \"%@\", causing repeated disconnects. Stop casting on the other device first.",
    ]

    public static let ja: [L10nKey: String] = [
        .commonClose: "閉じる",
        .commonCancel: "キャンセル",
        .commonDone: "完了",
        .commonApply: "適用",
        .commonAdd: "追加",
        .commonRefresh: "更新",
        .commonManage: "管理",
        .commonOpen: "開く",
        .commonAuto: "自動",
        .commonEnabled: "有効",
        .commonDisabled: "無効",
        .commonMacSpeakers: "Mac スピーカー",

        .themeSystem: "システム",
        .themeLight: "ライト",
        .themeDark: "ダーク",
        .langSystem: "自動",

        .statusIdle: "本機再生",
        .statusCastingCount: "キャスト中 · %d 台",
        .statusPausedCount: "一時停止 · %d 台",
        .statusConnecting: "接続中…",
        .statusReconnecting: "再接続中…",
        .statusError: "接続エラー",
        .menuPauseCast: "キャストを一時停止",
        .menuResumeCast: "キャストを再開",
        .menuSwitchBackToMac: "Mac 本機再生に戻す",
        .menuOpenApp: "%@ を開く",
        .menuSettings: "設定…",
        .menuQuitApp: "%@ を終了",

        .latencyStandard: "標準",
        .latencyBalanced: "バランス",
        .latencyLow: "低遅延",
        .latencyUltra: "超低遅延",
        .latencyCustom: "カスタム",
        .latencyGuideTitle: "再生遅延モードの説明",
        .latencyGuideSubtitle: "キャスト中に切り替えると TIME_OFFSET を再基準化し、再接続なしで即座に反映されます。",
        .latencyGuideButton: "遅延モードの説明",
        .latencyGoSettingsCustom: "設定で ms 指定",
        .latencyStandardDesc: "5GHz 800ms / 2.4GHz またはフォールバック 1000ms。複数台で最も安定します。",
        .latencyBalancedDesc: "400ms 普段使いに最適：同期を安定させつつ応答を高速化します。",
        .latencyLowDesc: "250ms 安定した 5GHz や有線 LAN での軽快な操作向け。",
        .latencyUltraDesc: "150ms 極小バッファ：同室の低ジッター環境・単体スピーカー専用。",

        .nowPlayingSelectSource: "音源アプリを選択",
        .nowPlayingCastingPrefix: "キャスト中 · %@",
        .nowPlayingLocalMuted: "対象アプリのみ消音中",
        .nowPlayingLocalKept: "Mac 本機出力を維持",
        .nowPlayingReadyLan: "完全 LAN 送信待機中",
        .nowPlayingPlaying: "再生中",
        .nowPlayingPaused: "一時停止",
        .themeCycleTooltip: "外観を切替（現在：%@）",
        .volumeGuardTitle: "安全音量の上限（%d%%）を超過",
        .volumeGuardMsgMaster: "マスター音量を %d%% から %d%% に上げますか？",
        .volumeGuardMsgSpeaker: "「%@」の音量を %d%% から %d%% に上げますか？",
        .volumeGuardKeep: "%d%% を維持",
        .volumeGuardConfirm: "%d%% に変更",

        .sourceSectionTitle: "音源",
        .sourceAutoBadge: "自動検出",
        .sourceChooseApp: "音源アプリを選択…",
        .pickerTitle: "よく使う音源と選択",
        .pickerSummary: "よく使う音源 %d 件（自動検出 %d 件）· クリックで選択",
        .pickerSearchHint: "アプリ名または Bundle ID を検索…",
        .pickerFavoritesHeader: "よく使う音源 (%d)",
        .pickerDragHint: "ドラッグで並替",
        .pickerFavoritesEmpty: "よく使う音源が未登録です · 下の「+ 登録」から追加してください",
        .pickerOtherAppsHeader: "その他のアプリ (%d)",
        .pickerAddMoreApps: "アプリを追加 (%d)",
        .pickerAutoDetectOn: "自動検出：許可",
        .pickerAutoDetectOff: "自動検出：オフ",
        .pickerRemove: "登録解除",
        .pickerAddFav: "+ 登録",

        .outputDevicesTitle: "出力デバイス",
        .restoreLast: "前回を復元",
        .restoreLastMulti: "前回 · %d台",
        .thisDeviceName: "本機再生",

        .settingsTitle: "設定",
        .settingsSectionAppearance: "外観と検出",
        .settingsThemeTitle: "テーマ",
        .settingsLanguageTitle: "表示言語",
        .settingsShowAllTitle: "すべての検出ノードを表示",
        .settingsShowAllSub: "MiPlay 非対応の LAN 機器も表示します（調査用）",
        .settingsRescanBtn: "LAN 機器を今すぐ再スキャン",

        .settingsSectionAudio: "音声とシステム権限",
        .settingsAudioPermTitle: "システムオーディオ録音権限（Process Tap）",
        .settingsAudioPermSub: "選択した音楽アプリのみを収集し、画面録画やマイク録音は行いません",
        .settingsOpenSystemSettings: "システム設定を開く",
        .settingsLocalMuteTitle: "キャスト中に対象アプリを消音",
        .settingsLocalMuteSub: "キャスト中の音楽アプリのみ消音し、他の Mac アプリは通常再生",
        .settingsVolumeGuardTitle: "大音量ガード",
        .settingsVolumeGuardSub: "安全上限を超えて音量を上げる際に確認ダイアログを表示します",
        .settingsVolumeLimitTitle: "安全音量の上限",
        .settingsAutoDetectTitle: "再生中の音源を自動検出",
        .settingsAutoDetectSub: "よく使う音源のホワイトリストのみ追従し、通知音を除外します",
        .settingsHwVolKeysTitle: "ハードウェア音量キー（F11 / F12）",
        .settingsHwVolKeysSub: "任意：アクセシビリティ権限が必要、キャスト中のみ有効（初期値オフ）",
        .settingsAccessibilityGranted: "アクセシビリティ許可済",
        .settingsAccessibilityNeeded: "アクセシビリティ権限が必要",

        .settingsSectionDebug: "接続とデバッグ",
        .settingsLogsTitle: "実行ログ",
        .settingsLogsSub: "ローカル保存 · 使用量 %@ · 閲覧またはエクスポート",
        .settingsAdvancedHeader: "接続パラメータ・手動 IP・デバッグ",
        .settingsAdvancedSub: "通常は自動設定で動作します。問題調査時のみ開いてください",
        .settingsManualIPLabel: "スピーカー IP を手動追加",
        .settingsManualIPHint: "例：192.168.1.100",
        .settingsManualIPInvalid: "有効な IPv4 アドレスを入力してください",
        .settingsManualIPAdded: "%@ を追加しました。メイン画面から選択できます。",
        .settingsSourceModeLabel: "キャプチャ音源モード",
        .settingsSourceModeApp: "選択アプリ",
        .settingsSourceModeSilent: "無音テスト",
        .settingsCustomDelayLabel: "カスタム再生遅延 ms（0=自動；50–5000）",
        .settingsCustomDelayInvalid: "0 または 50–5000 ms の数値を入力してください",
        .settingsRefModeLabel: "グループ参照モード（ref_function）",
        .settingsRefAuto: "自動",
        .settingsRefSingle: "単体",
        .settingsRefMulti: "マルチ",
        .settingsRefStereo: "ステレオ",
        .settingsAuthModeLabel: "8899 認証モード",
        .settingsAuthAuto: "自動",
        .settingsAuthLegacy: "Legacy",
        .settingsAuthSafety: "Safety",

        .settingsSectionAbout: "アプリ情報とアップデート",
        .aboutTitle: "CozyTing について",
        .aboutCheckUpdates: "アップデートを確認",
        .aboutChecking: "確認中…",
        .aboutUpToDate: "最新",
        .aboutNewVersionAvailable: "新バージョン %@",
        .aboutCheckFailed: "確認失敗",
        .aboutBetaUpdatesTitle: "ベータ版アップデートを受け取る",
        .aboutBetaUpdatesSub: "正式版に先駆けてプレリリース（RC）版を受け取ります",
        .aboutReleasesTitle: "リリースと更新履歴",
        .aboutReleasesSub: "GitHub Releases · AGPL-3.0",
        .aboutSponsorTitle: "CozyTing を応援",
        .aboutSponsorSub: "あたたかい一杯を贈る · 完全無料・任意",
        .sponsorDialogTitle: "開発を応援する",
        .sponsorDialogDesc: "CozyTing の全機能は完全無料・オープンソース・完全 LAN 動作です。任意の応援で継続開発を支えていただけると嬉しいです。",
        .sponsorWeChatLabel: "WeChat Pay",
        .sponsorAlipayLabel: "Alipay QR",
        .sponsorSaveQR: "画像を保存",
        .sponsorSavedToast: "ダウンロードフォルダに保存しました",
        .aboutFooterNote: "LAN 専用 · クラウド不使用 · AGPL-3.0",

        .logViewerTitle: "実行ログ",
        .logViewerSummary: "%d 行 · ローカル %@",
        .logViewerEmpty: "ログはまだありません",
        .logViewerEmptyFiltered: "該当するログはありません",
        .logViewerFilterAll: "すべて",
        .logViewerFilterWarnErr: "警告/エラー",
        .logViewerFilterCore: "コア",
        .logViewerFilterOps: "操作履歴",
        .logViewerSearchHint: "キーワード・IP・端末名で絞り込み…",
        .logViewerModeStructured: "構造化",
        .logViewerModeRaw: "生テキスト",
        .logViewerJumpLatest: "最新へ",
        .logViewerExport: ".txt 出力",
        .logViewerCopy: "コピー",
        .logViewerCopied: "クリップボードにコピーしました",
        .logViewerClear: "消去",
        .logViewerClearTitle: "ローカルログを消去しますか？",
        .logViewerClearMsg: "保存された実行ログ・コアログ・操作履歴を完全に削除します。",
        .logViewerClearConfirm: "消去する",
        .logViewerWarnBadge: "警告 %d",
        .logViewerErrBadge: "エラー %d",
        .logViewerJsonItems: "%d 件 · %@",

        .updateBannerFound: "新しいバージョン %@",
        .updateBannerView: "詳細",
        .updateDialogTitle: "新しいバージョン",
        .updateDialogPreviewBadge: "プレビュー",
        .updateDialogDownloadDMG: "DMG をダウンロード＆検証",
        .updateDialogOpenRelease: "リリースページを開く",
        .updateDialogRemindLater: "あとで",
        .updateDialogSkipVersion: "このバージョンをスキップ",
        .updateDialogVerifying: "SHA-256 を検証中…",
        .updateDialogVerifiedOpen: "検証成功 · Finder で開く",
        .updateDialogUnsupportedOS: "お使いの macOS バージョンは最小要件を満たしていません。",
        .updateSecAdded: "追加",
        .updateSecChanged: "変更・改善",
        .updateSecFixed: "修正",
        .updateSecRemoved: "削除",
        .updateSecSecurity: "セキュリティ",

        .errAppNotRunning: "「%@」が起動していません。起動してからキャストしてください。",
        .errLaunchAndCast: "起動してキャスト",
        .errAudioPermission: "アプリの音声を取得するには「システムオーディオ録音」権限が必要です。",
        .errLocalNetwork: "ローカルネットワークにアクセスできません。システム設定で許可して再試行してください。",
        .errFirewallBlocked: "スピーカーからの逆接続がタイムアウトしました（macOS ファイアウォールで CozyTing の受信接続を許可してください）。",
        .errOpenFirewallSettings: "ファイアウォール設定",
        .errOpenLocalNetworkSettings: "ローカルネットワーク設定",
        .errRetry: "再試行",
        .errCastFailed: "キャスト接続に失敗しました：%@",
        .errNoAudioSource: "キャスト前に音源アプリを選択してください。",
        .sourceNotRunningHint: "未起動 · アイコンをクリックして起動",
        .sourceWaitingAudioHint: "「%@」の再生開始を待機中…",
        .settingsFirewallTitle: "macOS ファイアウォールとローカルネットワーク",
        .settingsFirewallSub: "MiPlay はスピーカーから本機へ逆接続します。ファイアウォール有効時は CozyTing を許可してください",
        .settingsOpenFirewall: "ファイアウォール",
        .errSpeakerSeized: "LAN 内の別端末（スマホ版 CozyTing など）が「%@」を占有しており再接続が繰り返されています。別端末のキャストを先に停止してください。",
    ]
}
