# 窝爱听（CozyTing）跨端 UI 设计规范（Android + macOS + 未来 iOS）

本目录是 **第 3 层（UI 表现层）** 的唯一视觉事实来源：Android（Kotlin 原生 View）与 Apple 端（`apple/CozyTingShared` `CozyTingDesignKit` + macOS / 未来 iOS 原生 SwiftUI 壳）分别按原生方式实现界面，但严格共用本目录的 `tokens.json`、`icons/*.svg` 与页面信息架构。

- **品牌名与协议名解耦约定**：C 端产品名称在简体中文 locale 为 **窝爱听**，在英文与日文等非中文 locale 统一为 **CozyTing**；底层协议与引擎标识统一保留 **MiPlay**（如设备行协议徽标 `MiPlay`、设置项中的「未声明 MiPlay 能力的节点」、L1 Rust 核心 `cozyting-core`）。
- **全链路多语言（i18n）基线**：双端原生支持至少 **简体中文（`zh-CN`）**、**英文（`en`）**、**日文（`ja`）** 三语且可扩展至更多语种。Android 端所有用户可见文案统一收敛至 `res/values/`、`res/values-en/`、`res/values-ja/` 的 `strings.xml`，并在 API 29–36 提供零外部依赖的应用内语言切换（API 33+ 经 `compat/LocaleCompat` 同步系统分应用语言 `LocaleManager`）；iOS 端同步维护 `Localizable.xcstrings`（支持在系统「设置 → CozyTing → 语言」及应用内入口切换），L1 `core/` 通过结构化 `error_code` / `notes_is_fallback` 与文案解耦，严禁硬编码单语种句子。

## 1. 视觉风格（第一版样式 B · 克制低饱和 + 高对比选中卡）

- **主题支持**：三态切换 `跟随系统（默认）` / `浅色` / `深色`，即时生效且同步系统状态栏与导航栏图标反色（Edge-to-Edge）。
- **深色模式（Dark）**：深炭蓝微透面板 `#151A23` + 轻描边 + 未选中微透深灰行；**已选中音箱行采用亮白浅卡 `#EAF0F7` 反显**（深墨字 `#12161E` + 主题蓝 `#2563EB` 滑块与勾选），一眼锁定当前投送设备。
- **浅色模式（Light）**：清爽冷浅灰底 `#F4F6FA` + 纯白面板/未选中卡；**已选中音箱行采用淡蓝底 `#EBF3FF` + `1.5dp` 主题蓝描边 `#2563EB`**（深墨字 `#0F172A` + 主题蓝滑块与勾选）。
- **专辑封面氛围底**：低饱和克制透色——API 31+ 使用 `RenderEffect` 模糊 + 明暗自适应遮罩；API 29–30 使用轻量缩略图软模糊；iOS 使用原生 `.ultraThinMaterial`；无封面时使用柔和双色渐变（严禁生硬像素色块）。

## 2. 四段式主界面结构（双端对齐）

1. **顶栏（Top Bar）**
   - 左侧：品牌标题（中文 `窝爱听` / 英文 `CozyTing`，双端统一）+ 运行状态胶囊（状态圆点 + `本机出声` / `投送中 · N 台` / `连接中`）。
   - 右侧：紧挨排列「**延迟模式快捷胶囊**」与「**设置**」圆形图标按钮（标准 6 齿齿轮图标；当存在未忽略的新版本时在设置按钮右上角叠放 `8dp/pt` 主题蓝圆点角标）；深色/浅色/跟随系统三态切换统一收敛在独立设置页「外观与发现」卡片中，设备刷新按钮下沉至「输出设备」标题栏。
   - **非阻塞版本更新轻提示条（顶栏下方、主面板上方）**：检测到未忽略新版本且该版本 `tag` 未被用户点击 `×` 关闭时展示单行紧凑提示条（`update.svg` + `发现新版本 vX.Y.Z` + `查看` 胶囊 + `×` 关闭），投送态下照常展示，绝不在冷启动强行弹出阻塞型弹窗打断听歌与选箱。点击 `×` 关闭后将当前版本号 `tag` 持久化到本地偏好（Android `SharedPreferences` / iOS `UserDefaults`），重启 App 后同版本不再反复弹出（设置按钮角标仍保留以便随时手动升级），后续发布更新版本时自动恢复提示；点击「跳过此版本」则同步隐藏提示条与角标。
   - **延迟模式快捷胶囊与极简下拉菜单（Android & iOS 双端对齐）**：
     - 单一事实来源：仅由持久化 `delayMs`（`0` = 自动，`> 0` = 具体毫秒）自动推导，绝不维护第二份模式枚举；
     - 4 个内置档位各配独立 `24×24` 几何线性图标：`标准`（`latency_standard.svg`，自动 5GHz 800ms / 2.4GHz 1000ms，14 AU，中性色）、`均衡`（`latency_balanced.svg`，400ms，8 AU，主题蓝）、`低延迟`（`latency_low.svg`，250ms，5 AU，主题蓝）、`极速`（`latency_ultra.svg`，150ms，3 AU，暖琥珀）；若用户在设置页手动填写了非预设值，胶囊自动显示 `latency_custom.svg` + `${delayMs}ms`（`<250ms` 琥珀 / `≥250ms` 蓝），下拉框 4 个预设均不勾选并在底部动态追加一行已勾选的 `自定义 · Nms`；
     - 下拉菜单保持极致精简（宽约 `196–200dp/pt`，单行仅展示「图标 + 档位名 + 右侧 ms + 勾选」，零长文说明），点击任意预设立即调用 L1 `cozyting_engine_set_playback_delay` / `NativeBridge.setPlaybackDelay` 热重锚 `TIME_OFFSET` 即时生效（免断连重连），且冷启动按 L1 `startup_runway_aus_for_delay_ms` 自动缩放预热帧数；
     - 下拉框底部经细分割线提供按需入口 `ⓘ 档位说明 ›`，点击后弹出详情弹窗展示 4 档完整参数、适用场景与「去设置自定义 ms」跳转（Android 使用 `PopupWindow` + Style B `Dialog`；iOS SwiftUI 使用 `Menu` + `.sheet` 对应实现）。
2. **正在播放区（Now Playing）**
   - **已获得活跃媒体会话**（双端统一左大封面 + 右文控结构）：
     - **左侧封面**：放大至 **`96dp/pt × 96dp/pt`**（`16dp/pt` 圆角，Android 通过 `Canvas.clipPath` + `clipToOutline` 双重稳定裁剪），与右侧文控区高度齐平；右下角叠放 **`26dp/pt` 圆形音源 App Logo 角标**（带细描边与微投影确保亮/暗封面均清晰可辨）。
     - **右侧列**：高度 `96dp/pt`，左距封面 `16dp/pt`（与主卡片左右 `16dp/pt` 内边距三处等距）；整体在 `96dp/pt` 高度内垂直居中，且歌名（`17.5sp/pt` Bold，单行省略）、歌手名（`12.5sp/pt` 次级色）与下方播控按钮组（`上一首 38dp/pt 柔和圆底` · `26dp/pt 间距` · `播放/暂停 42dp/pt 高对比主圆钮` · `26dp/pt 间距` · `下一首 38dp/pt 柔和圆底`）统一沿同一垂直中轴线居中对齐。
     - **点击封面跳转音源 App**：
       - **Android**：点击封面（带圆角水波纹反馈）优先通过 `nowPlaying.sourcePackage`（回退已选音源 `selectedPackage`）调用 `getLaunchIntentForPackage()` 拉起对应音乐 App。
       - **iOS（SwiftUI）**：同布局与右下角角标槽位；受 iOS 沙箱限制无法遍历任意第三方 App 图标或按 Bundle ID 强拉起，角标展示已知音源/系统音频图标，点击封面时对支持 URL Scheme 的音乐 App 跳转（如 Apple Music `music://`），未知音源则静默降级不报错。
     - **进度条与音量条（双端统一细轨道规范）**：播放进度条采用全宽 **`4dp/pt` 居中细胶囊轨道** + `14dp/pt` 圆形滑块，当前时间（左）与总时长（右）紧贴轨道下方两侧（播放中平滑走字，拖拽实时更新）；Android `SeekBar` 的 `LayerDrawable` 必须显式锁定图层高度为 `4dp` 并垂直居中，防止系统拉伸成粗条；下方紧接带实时百分比的总音量细轨道滑块。
     - **高音量保护与跨线二次确认（Android & iOS 双端共享 L1 策略）**：总音量条与单音箱行内独立音量条统一消费 L1 Rust `cozyting_volume_guard_evaluate_json` / `cozyting_engine_set_volume_checked`（默认开启，安全上限默认 `60%`，合法配置 `30%–90%` 步进 `5%`）。安全区内滑块保持原本纯净细胶囊轨道（不绘制额外刻度块），仅在进入高音量区（`> 上限`）时高亮轨道、滑块描边与百分比切为琥珀警告色（亮底选中音箱行采用深琥珀 `#D97706` 保证对比度）。交互仅在**从安全区（`≤ 上限`）向上跨过安全上限（`> 上限`）**时拦截下发、先回弹至原音量并弹出 2 按钮 Style B 确认框（`保持原音量（Y%）` / `确认调至 X%`；点弹窗外或返回同样保持原音量）；已在高音量区内微调或降回安全区直接生效，降回安全区后再次跨线会重新确认；多音箱总音量只要任一受影响音箱跨线即触发确认；iOS SwiftUI 使用同等纯净轨道与 Style B 确认弹窗 / `.alert` 实现。
     - **总音量与单音箱独立音量持久化（Android & iOS 双端对齐）**：用户确认提交的总音量与按设备 ID 维护的单音箱独立音量均实时持久化到本地偏好（Android `SharedPreferences` / iOS `UserDefaults`，单音箱映射保留最近 32 台）。冷启动恢复时遵循安全保护联动：开启「高音量保护」时，若上次保存值超过安全上限，恢复时自动钳制回安全上限（防止重启首次开投即爆音）；关闭保护时原样恢复。点击勾选音箱开投或扩组加入音箱时保留该音箱上次独立音量，不再被总音量覆盖（仅未记录过的音箱继承当前总音量）。
     - **物理音量键快捷调节投送总音量（Android 专属 · iOS 边界说明）**：在 Android 主界面（`MainActivity`）与悬浮控制卡（`QuickCastPanelActivity`）前台展示且已选音箱/投送中时，通过共享 `HardwareVolumeKeyController` 拦截物理音量 `+ / -` 按键（同时消费 `DOWN` / `UP`，不弹出系统音量条），单次步进 ±`5%`、连按即时预览并做 `100ms` 合并防抖提交，跨过安全上限时同样触发高音量二次确认框；处于「本机播放」未投送状态或退出界面后自动交还系统。iOS 端受系统与 App Store 审核规范（禁止劫持物理音量键用于非系统路由）限制，不拦截硬件音量键，仍通过面板滑块调节。
   - **未开启通知使用权 vs 已开启但暂未播放**：仅在系统尚未授予通知使用权（`!isNotificationListenerEnabled()`）时折叠为单行可点击权限引导条（Android 点击直达通知使用权设置；iOS 展示广播捕获/音频会话提示）+ 总音量条；一旦已授予通知使用权，从系统设置返回后立即隐藏该权限引导条并展开播控区（若此时尚未打开任何音乐 App 播放，则展示就绪待播态 `暂未播放 · <已选音源>`，点击封面或播放键可直接启动已选音源 App）。
3. **音源槽位（Platform Audio Source Slot）**
   - **Android**：
     - 当前选定 App 卡片（图标 + 名称 + 自动识别态显示 `自动识别` 胶囊 + `管理` 入口）+ 横向快捷切换胶囊（严格按用户自定义「常用音源」排序展示，亦支持长按胶囊左右拖动排序并实时持久化；若当前临时选定非常用 App 则置前提示）；未选 App 直接点音箱时拉起 `AppPickerActivity`。
     - **常用音源管理页（`AppPickerActivity`）**：支持用户自由增删与**长按拖动排序**「常用音源」名单（首次默认带入本机已安装的内置音乐/播客 App；卡片 ≥ 2 张且非搜索态时右侧显示 `drag_handle.svg` 拖拽把手与「长按拖动排序」提示，拖动浮起并平滑让位换位、松手持久化且不误触点击或开关）；每个常用音源卡片下方提供独立的 **`自动识别` 开关**与 **`移出常用`** 按钮，下方其他应用行提供 **`+ 常用`** 胶囊按钮。**进入该页本身绝不中断当前投送或切回本机播放**：用户仅管理名单/开关后返回或点选当前正在投送的同一 App 时全程不断流；投送中点选新音源返回后，经 `CastService` 在不断开音箱连接的前提下走单 UID + 签名 fail-closed 热切换（成功后再更新持久化选中项，失败回滚原音源时保持原音源展示一致并弹出轻提示）。
     - **自动识别与投送中热切换策略**：开启全局开关后，监听全部活跃 `MediaSession` 的播放状态上升沿，严格从「常用音源名单」中开启独立 `自动识别` 开关的 App 内匹配最新起播音源——待机时自动预选，投送中切换至其他常用音乐 App 播放时（即便旧软件未关闭）由前台服务 `CastService` 在不断开音箱的前提下经 `CaptureTargetResolver` 单 UID + 签名 fail-closed 校验后热重建 `AudioRecord` 切换捕获目标；若系统限制复用当前 `MediaProjection` 则 fail-closed 保持原音源或干净停止并提示重授，绝不退化为全系统捕获；手动切源后防止被后台仍在播放的旧会话立刻拉回。
   - **iOS（未来）**：同一视觉卡槽展示 `ReplayKit` 系统广播捕获入口（`RPSystemBroadcastPickerView`）或 App 内音源状态；受 iOS 沙箱限制无法读取第三方 App `MediaSession` 包名，也不按单 App UID 选源，因此常用音源增删、长按拖动排序与自动识别开关仅作用于 Android 端。
4. **输出设备区（Output Devices）**
   - 分节标题栏：左侧 `输出设备`，右侧展示已发现设备数 + 按需「**恢复上次**」快捷胶囊（仅在待机未投送且上次使用过的可投音箱组合至少有 1 台在线/已缓存时显示，多台显示 `恢复上次 · N台` / `Last · N` / `前回 · N台`，点击一键勾选并恢复投送；冷启动绝不自动抢投，投送中自动隐藏）+ **`28dp/pt` 圆形刷新扫描按钮**（仅刷新局域网输出设备，扫描中旋转动画；冷启动或从外部切回前台——Android `onResume()` / iOS `scenePhase == .active`——在满足「非内部子页面返回 + 距上次扫描完成 ≥ 30 秒或当前可投设备为 0 台」时自动触发一次后台扫描刷新，后台自动扫描会合并历史缓存以防瞬时丢包闪隐；**手动点击刷新按钮**则无视 30 秒冷却立即强制刷新，并以本次局域网真实扫描结果为事实来源，自动淘汰本次未扫到的已离线历史缓存设备，仅保留在线设备与当前正在投送中的设备，同时更新本地缓存；扫描结果与当前可见列表一致时不重建设备行 View，正在拖动音量滑块时延后至松手再刷新），避免放在顶栏造成全局刷新的语义误解。
   - **上次使用的音箱组合持久化与置顶排序（Android & iOS 双端对齐）**：每当用户勾选可投音箱（非空集合）时，有序持久化其设备 ID 列表（Android `SharedPreferences` / iOS `UserDefaults`；切回「本机播放」停投时不清空该记录）。冷启动与每次发现合并时，将上次使用过的可投设备按原序置顶到「本机播放」紧下方，其余设备保持相对顺序。
   - 首行：`本机播放`（与外部接收端互斥，标题字号与外部设备行统一为 `13sp/pt Bold`）。
   - 外部音箱/电视行：统一矢量图标（音箱/电视）+ **同行轻分隔标题「设备名 + 可选房间名」**（单行末尾省略，不拼接型号后缀；设备名采用 `13sp/pt Bold` 主色，获取到非空房间名时紧随 ` · <房间名>` 采用 `11.5sp/pt Regular` 次级色，无房间名或获取不到时彻底隐藏、不留分隔符；Android 通过 `SpannableStringBuilder` 实现，iOS SwiftUI 通过 `Text(name).font(.system(size: 13, weight: .bold)) + Text(" · \(room)").font(.system(size: 11.5)).foregroundStyle(subColor)` 等价实现；双端统一消费第 1 层 Rust `cozyting_discover_json_ex`，单次只读 TCP 8899 `GET_DEVICE_INFO` TLV 帧内同步提取 `miName` 与 `roomName`，名称优先级为「`miName` → 本地缓存 `miName` → mDNS 老名字 → IP」，音箱与电视一视同仁、投送中跳过查名；冷启动自动扫描沿用已含 `room_info_queried=true` 的本地缓存，首次发现/缺房间字段的旧版缓存或手动刷新时触发查名）+ 副标题（IP + 实时状态：`投送中` / `连接中…` / `重连中…` / 不可投送原因）+ 右侧延迟胶囊（直接消费第 1 层 Rust 下沉的 `ui_state` 与 `display_latency_ms`）+ 协议徽标 + 矢量勾选圆。
   - 选中后行内展开带实时百分比的独立音量滑块；拖动音量时仅原位更新，禁止重建设备列表。
   - **主卡片底边收口与按需提示横条**：平时（待机、选定音源、正常投送中）主卡片在最后一台输出设备行下方直接以 `16dp/pt` 对称内边距干净收底，不常驻任何冗余文字（如「已选择音源：xxx」「已切回本机播放」等均由卡片高亮与顶栏胶囊体现）；按语义分三类横条，且均支持**点击横条提前关闭**：
     - 轻提示横条（主题蓝圆点）：切换延迟档位、热切音源等反馈，**2.5 秒自动消失**；
     - 操作告警横条（琥珀色圆点）：切换音源被拒、点击不可投送设备、权限被拒等用户即时操作报错，**7 秒自动消失**，**禁止被 1 秒投送状态轮询或后台设备扫描误清**；
     - 状态告警横条（琥珀色圆点）：投送异常中断或未发现任何可投音箱，常驻展示直至恢复正常或用户手动点击关闭。
   - **Android 共享 UID 三层校验与显式授权弹窗（Android 专属；iOS 受沙箱模型限制由 `ReplayKit` 承载，不涉及按 UID 隔离）**：
     - 系统/特权共享 UID（`appId < 10000` 或 `android.uid.*`）：始终硬拒绝并以操作告警横条提示；
     - 仅 Manifest 历史声明 `sharedUserId` 但本机该 UID 实际仅安装单个 App（如单装 Apple Music）：等价独立 UID，直接放行；
     - 本机该 UID 实际安装 ≥2 个普通应用：默认 Fail-Closed 不静默捕获，在主界面快捷切换或音源选择页弹出 `UiComponents.showStyleBDialog` 确认框（列出同 UID 的具体其他应用名，说明仅共同捕获这些应用、不含微信或系统通知）；用户点击「允许投送」后按精确包集合快照持久化授权（绝不持久化 UID），未来若同 UID 新增未知应用则自动失效并重新确认。

## 3. 独立设置页（4 张卡片分组）

1. **外观与发现**：三段式主题选择 `[跟随系统 | 浅色 | 深色]`、四段式显示语言选择 `[跟随系统 | 简体中文 | English | 日本語]`（具体语种固定以母语名称展示防止误切后无法辨认；Android 通过 `AppLocaleManager` + `LocaleCompat` 即时生效并重建页面，iOS 通过 `Localizable.xcstrings` / 系统分应用语言同步生效）、显示全部发现设备开关、重新发现设备入口。
2. **投送与系统权限**：
   - **采集引擎（Android 专属；iOS 受沙箱模型限制由 `ReplayKit` 固定承载，不展示此分段）**：两段式切换 `[标准投屏模式 | 无感模式（无线调试）]` + 精简系统版本能力摘要（未手动选择时按系统版本自动决定默认项：**Android 13+ 默认无感模式**；Android 10–12 默认标准投屏模式以守住单 App Fail-Closed 红线）；选择无感模式且 Android 11+ 时依次展示 **捕获范围** 分段 `[单 App 隔离 | 全局媒体（显式）]`（Android 11–12 选单 App 时保留 Fail-Closed 提示）与单行 **无线调试配对** 状态入口（`已配对` / `未配对`，无需 Shizuku 等外部 App；点击直达 5 步配置向导页统一完成配对、自动/手动测试连接与低频证书重置，避免设置页入口重合与大按钮堆叠）。
   - **5 步交互式配置向导页（`WirelessDebugGuideActivity`，Android 专属）**：严格沿用 Style B 明暗主题与顶部进度胶囊，实时自动检测并打勾 `1. 允许通知权限` → `2. 开启开发者选项` → `3. 开启「无障碍自动配对」（推荐；支持跳过改走通知栏兜底）` → `4. 开启无线调试并完成 6 位码配对` → `5. 测试本机连通性并一键启用`；已配对时在底部常见问题折叠卡下方提供低频 `清除本机配对证书` 入口（点击弹出 Style B 二次确认弹窗防误触）。为避开 Android 12+ / HyperOS 系统设置页对普通 `TYPE_APPLICATION_OVERLAY` 悬浮窗的 `HIDE_NON_SYSTEM_OVERLAY_WINDOWS` 屏蔽且减少权限步骤，彻底移除 `SYSTEM_ALERT_WINDOW` 悬浮窗权限，改由 `WirelessPairingAccessibilityService` 提供：
    - **自动识别 + 无障碍悬浮键盘（主路径）**：使用 `TYPE_ACCESSIBILITY_OVERLAY` + `FLAG_NOT_FOCUSABLE` 挂载顶部可拖动 Style B 浮动数字键盘 `PairingFloatingOverlay`（无需悬浮窗权限、在系统 PIN 码页稳定同屏显示）；配对会话期间通过跨 ROM 跨语言的 `WirelessPairingScreenParser`（AOSP `*:id/pairing_code` / `*:id/ip_addr` 优先 + 独立 6 位数字与 `IPv4:Port` / mDNS 端口共现校验）自动提取 6 位配对码与端口，自动填入并触发 SPAKE2 + TLS 1.3 配对后跳回向导页（配对完成后服务立即静默休眠）；
    - **通知栏长按展开输入（保底兜底，始终同步开启）**：无论是否开启无障碍，均推送高优 `RemoteInput` 通知横幅，并在文案中醒目提示「若未看到输入按钮，请长按本条通知（或单指下滑）展开输入框」，同时向导页保留小窗/分屏手动填码入口；
    - 主界面、音源页与向导页中的所有模态提示/确认弹窗（首次使用无感模式配对引导、重启后无线调试恢复提示、清除配对证书确认、常用音源与自动识别说明）统一采用 `UiComponents.showStyleBDialog` 自定义圆角卡片弹窗（深色 `#151A23` / 浅色 `#FFFFFF` + 顶栏关闭圆钮 + 可选内嵌附注框 + 全宽 Tonal 按钮，严禁使用系统原生 `AlertDialog`；iOS SwiftUI 采用同等 Style B 卡片弹窗 / `.sheet` 呈现）。
   - **投送时手机静音开关**（去重精简为「单条清晰副标题 + 单行实时状态」：Android 13+ 无感单 App 模式下说明仅静音当前投送的音乐 App、其他 App 照常发声并注明以 ROM 为准，并在 `uid=2000` `AudioPolicy` 内自动启用双区音频焦点隔离，避免手机刷短视频暂停音箱音乐或恢复音乐导致视频短暂无声，停止/异常退出自动注销；标准模式下说明静音媒体音量且标准模式无法单 App 静音；iOS 受沙箱限制不支持 `AudioPolicy` 焦点接管）。
   - **高音量保护与安全音量上限（Android & iOS 双端对齐）**：提供「高音量保护」总开关（默认开启）与展开态「安全音量上限」步进滑块（默认 `60%`，范围 `30%–90%`，步进 `5%`），持久化至本地偏好并实时同步至 L1 Rust `cozyting_set_volume_guard_policy`。
   - **自动识别正在播放的音源总开关**（Android，默认开启，仅从常用音源中已授权自动识别的 App 选取）、通知使用权实时状态与直达入口（Android）、电池优化豁免实时状态与直达入口（Android）。
3. **连接与调试（默认折叠）**：手动添加音箱 IP、音源模式分段选择（选定 App / 测试静音）、播放延迟（ms）输入框与同行右侧「生效」主按钮（支持 50–5000ms 及留空/0 自动；投送中点击直接调用 L1 `cozyting_engine_set_playback_delay` / `NativeBridge.setPlaybackDelay` 清缓冲并热重锚广播 `TIME_OFFSET` 即时生效，无需断连重连，待机时保存并在下次连接生效；Android 与未来 iOS 共用同一交互与 L1 能力）、房间参考模式分段选择、认证模式分段选择、验收变体本地探针。
4. **关于与更新（Android & iOS 双端对齐 · 方案 C 单入口 + 独立二级页）**：
   - **设置首页单入口**：底部卡片仅保留一行清爽入口 `关于窝爱听 / About CozyTing`（副标题展示当前检查状态或新版本提示；有新版本时右侧亮主题蓝圆点 + `vX.Y.Z` 实心胶囊 + `›`，无新版本时右侧仅显示低调当前版本号 `vX.Y.Z` + `›`），与上方「高级与调试」列表风格完全统一。
   - **独立「关于」二级页（Android `AboutActivity` / iOS SwiftUI `NavigationLink` → `AboutView`）**：
     - 顶部独立居中品牌头：`64dp/pt` 圆角 App 图标 + 品牌名 + 版本号（自动剥离 `-release` 构建后缀，预览版/验收版附状态标注）。
     - 单张 Style B 无框分割线卡片平铺 4 行：
       1. `检查更新`（整行点击原地触发手动检查或弹出更新详情弹窗；右侧展示 `已是最新` / `vX.Y.Z` 胶囊 / `检测中…` + `›`）；冷启动自动检查更新默认常开（每 24h 向公开静态清单发起一次只读 HTTPS + ETag 304 请求、零遥测、绝不冷启动强弹窗），不提供冗余开关。
       2. `接收测试版更新`（平铺无框 Switch 开关：关闭 = 稳定版 `Stable`，开启 = 预览版 `Preview (RC)`，切换后立即基于本地缓存重算状态）。
       3. `版本发布与更新日志`（外链打开公开 GitHub Releases 页面）。
       4. `赞赏支持`（由构建开关 `ENABLE_SPONSOR_ENTRY` 控制，开源直装发行版默认开启；严禁引入第三方支付 SDK、统计埋点或弹窗推销）：点击弹出 Style B 纯静态赞赏弹窗，明确声明所有功能永久免费开源、纯属自愿，提供支付宝直达外链以及本地打包的微信赞赏码 / 支付宝收款码（Android 通过 API 29+ 原生 `MediaStore` 一键保存至相册、无需存储权限）；**iOS 端平台边界**：自签/开源 IPA 分发版在 `AboutView` 对等提供 `.sheet` 纯静态赞赏卡片（使用 `PHPhotoLibrary` / `ShareLink` 保存二维码），App Store 商店分发版受 App Store Review Guidelines 3.1.1 约束默认通过编译开关关闭外部赞赏入口。
     - 底部居中展示极轻开源与纯局域网声明（`纯局域网 · 零云端 · AGPL-3.0`）。
   - **Style B 更新详情弹窗（Android `Dialog` / iOS `.sheet`）**：消费 L1 Rust `cozyting_update_evaluate_json` 返回的 `(current_version, target_version]` **跨版本区间更新日志**——相邻版本直出条目，跨多版本升级按新到旧分节展示各版本变更（版本胶囊 + 预览徽标 + 右对齐发布日期；正式版自动过滤已被汇总覆盖的中间 RC）；**更新日志结构化渲染（双端同步）**：L1 Rust `clean_release_notes` 保留列表嵌套缩进，客户端严禁裸露原始 Markdown 符号，须将 `> ` 摘要渲染为左侧带语义色条的导语块、将 `### Added / Changed / Fixed / Removed / Security` 映射为三语语义色分组徽标（`新增 / 优化与变更 / 问题修复 / 移除 / 安全`）、将父级功能点加粗提亮（`textPrimary`）并以左侧细竖线缩进承接子级说明（`textSecondary`）、并将行内 `**加粗**` / `` `code` `` / `[文案](url)` 转为原生富文本（Android 由零依赖 `ChangelogMarkdownRenderer` + `SpannableStringBuilder` 渲染，iOS SwiftUI 使用对应块级 `VStack` + `AttributedString` 渲染）；中部更新日志滚动区必须硬性限高（`≤ 260dp/pt` 且 `≤ 屏高 40%`，并开启垂直边缘渐隐提示可滚动；Android 通过自定义 `MaxHeightScrollView` 在 `onMeasure()` 首轮即完成钳制，iOS SwiftUI 使用 `ScrollView { ... }.frame(maxHeight: 260)`），确保长日志绝不将底部操作按钮顶出屏幕；Android 支持应用内流式下载（`4dp` 胶囊进度条）+ 强制 SHA-256 与签名证书双重校验 + `FileProvider` 调起系统安装，并保留「前往浏览器下载」「稍后提醒」「跳过此版本」；iOS App Store 分发版走商店更新、自签/IPA 分发版跳转 Safari 打开 Release 页。

## 4. 图标与品牌标识规范

### 4.1 App 图标与品牌标识（F2 · 深夜亮灯小屋与垂尾听歌小猫）

- **核心意象与构图**：纯扁平绘本几何色块（严禁深蓝霓虹发光与 3D 模糊渐变）。圆润暖珊瑚屋顶与烟囱构成「温暖小窝」，拱形暖黄亮灯窗口内一只黑猫安睡枕爪并将尾巴自然垂出暖木窗台，左上方悬着饱满弯月，右上方烟囱飘出两枚错落倾斜的主视觉音符（主双音符 `♫` 向左微歪 `-10°`、伴随单音符 `♪` 向右斜飞 `+16°`），传达「疲惫归家、打开软件、满屋响起音乐」的治愈氛围。
- **双主题品牌色板（同步沉淀于 `design/tokens.json` `brand` 字段）**：
  - 浅色底板（暖燕麦奶油）：`#FDF4E6` · 屋墙 `#5E5665` · 弯月 `#F2B84B`
  - 深色底板（深夜暖炭）：`#1B1F2B` · 屋墙 `#474D5F` · 弯月 `#FFD269`
  - 共用核心色：全品牌主色暖珊瑚 `#E56D48`（屋顶、烟囱、音符及官网/文档/宣传片主强调色）、暖灯拱窗 `#FFD166`、暖木窗台 `#EE9B48`、小猫剪影 `#20232F`、小鼻点缀 `#EF8B65`。
- **Android 端落地（API 29–36 Adaptive Icon + Android 13+ Monochrome）**：
  - `res/drawable/ic_launcher_background.xml` & `ic_launcher_foreground.xml`（浅色）与 `res/drawable-night/`（深色），统一 `108dp × 108dp` 画布，将 `512 × 512` 基准矢量经 `translate(18, 18) scale(0.140625)` 映射至中央 `72dp` 可视区，全元素严格落入 `66dp` 圆形安全区内（确保圆形/圆角矩形/水滴等 OEM 裁切均不切边）。
  - `res/drawable/ic_launcher_monochrome.xml`（Android 13+ 主题单色图标）：采用纯 `#FFFFFF` 单色与原生 `android:fillType="evenOdd"` 真透明镂空（拱窗与右下垂尾通道、猫体合并轮廓与双闭眼均为透明负形），严禁用背景色盖白边，确保系统单色染色（Material You）下双闭眼与垂尾清晰可辨。
- **iOS 端同步规范（SwiftUI / Asset Catalog `AppIcon.appiconset`）**：
  - 同源导出 `1024 × 1024` 无透明通道圆角前平铺母版：**浅色版（Default）** 使用 `#FDF4E6` 暖燕麦底与 `#5E5665` 屋墙；**深色版（Dark，iOS 18+）** 使用 `#1B1F2B` 深夜暖炭底与 `#474D5F` 屋墙；**着色版（Tinted，iOS 18+）** 复用与 Android `ic_launcher_monochrome.xml` 同源的 `evenOdd` 真镂空单色剪影。
  - 所有视觉元素落在中央 `86%` 安全区内，兼顾 iOS 连续曲率圆角（Squircle）与 `24pt` / `29pt` 设置页小图标清晰度。

### 4.2 界面线性图标（`design/icons/`）

- 统一画布 `24×24`，描边图标统一 `stroke-width="1.85"`、`stroke-linecap="round"`、`stroke-linejoin="round"`。
- Android 转为 `res/drawable/ic_ui_*.xml`（通过 `tint` 跟随 `UiPalette` 着色）；iOS 导入 Asset Catalog 并设为 `Template Image`。

## 5. 系统常驻通知文案规范（Android 前台服务 · 方案 2）

- **标题**：直指运行状态与音源名，避免暴露「无感/选定 App/配置延迟/E2E估」等工程术语：
  - 正常投送：`正在投送 · <音源名>`（全局媒体为 `正在投送 · 全局媒体`，静音测试为 `静音测试中`）
  - 连接中：`正在连接 · <音源名>`
  - 已暂停：`已暂停投送 · <音源名>`
- **收起态正文（严格单行定长，不随设备数增长）**：
  - **1 台设备**：直接显示真实设备名并附带点击提示（如 `客厅音箱 · 点击打开控制卡`；异常时先显状态如 `客厅音箱 · 重连中 · 点击打开控制卡`；无缓存名时回退完整 IP）。
  - **≥ 2 台设备**：统一简洁纯计数并附带点击提示（正常：`N 台设备同步播放 · 点击打开控制卡`；全部连接中：`正在连接 N 台设备 · 点击打开控制卡`；已暂停：`N 台设备 · 点击打开控制卡`；存在多项并发异常如重连+断开时优先保留完整异常计数避免截断）。
- **下拉展开态（`BigTextStyle`）**：≥ 2 台设备时列出具体音箱明细（`• 设备名 · 状态`），异常设备置顶排序，最多展示前 5 台，超出末尾折叠为 `…等共 N 台设备`；操作按钮保留 `设备与音量`（直达 `QuickCastPanelActivity` 悬浮控制卡）、`暂停/恢复` 与 `停止`，且点击通知正文同样拉起悬浮控制卡。通知渠道名称统一为 `窝爱听 · 投送状态`。
- **iOS 边界说明**：iOS 无 Android 前台服务常驻通知，播控与连接状态由 App 内顶栏胶囊及系统「控制中心 / 正在播放」（或后续 Live Activities）承载，文案沿用同等去工程化口径。

## 6. 本地运行日志浏览与导出（设置页 · 连接与调试）

- **入口位置**：设置页「连接与调试」卡片顶部直出「**运行日志**」入口行（无需展开折叠调试区），副标题展示当前本地日志总占用体积，右侧为 `ic_ui_chevron_right`，点击进入独立日志浏览页。
- **日志采集与容量边界（L1 + L2 协同）**：
  - L1 Rust 核心（`core/src/diag_log.rs`）通过 `cozyting_init_log_file` / `NativeBridge.initLogFile` 异步轮转写入本地 `core.log`（256 KB × 2 = 512 KB 封顶）并维护内存环形缓冲（`cozyting_recent_logs`），同时保留原有系统日志输出（Android `CozyTingCore` Logcat）。
  - L2 客户端异步轮转写入 `runtime.log`（256 KB × 2 = 512 KB 封顶），并兼容保留 `cast_ops.log`（64 KB × 2 = 128 KB 封顶）；全量日志仅存应用私有沙箱，零自动上传，不含音频数据。
- **独立日志浏览页（Android `LogViewerActivity` / iOS SwiftUI `LogViewerView`）**：
  - 顶栏：返回按钮 + 标题与摘要（行数 · 本地占用 + 可点击的告警/错误计数胶囊，点选直达「告警/错误」筛选）+ 刷新按钮（`ic_ui_refresh`）。
  - 分段筛选：`[全部 | 告警/错误 | 核心协议 | 操作流水]`，「告警/错误」存在条目时附数量角标。
  - 搜索与视图切换行：轻量关键词搜索框（支持清除按钮与命中高亮）+ 右侧「原始 / 结构化」视图一键切换。
  - 正文区（Style B 面板卡片）：
    - **结构化时间线模式（默认）**：同日日志以居中日期分割线收拢；每条日志渲染为左侧带 3dp 级别/来源色条的圆角卡片，依次展示 `HH:mm:ss.SSS`、级别徽章（`I`/`W`/`E`）、来源标签（`CozyTing` / `CozyTingCore` / `CastOps`）、单条快速复制按钮、事件主干与流式排布的 `key=value` 参数胶囊；超长嵌入 JSON（≥ 48 字符）默认折叠为 1 行摘要胶囊（项数 · 体积），点击按需缩进格式化并语法高亮展开；向上滚动时右下角浮现「↓ 最新」回底胶囊。
    - **原始文本模式**：保留等宽可自由选中文本视图，便于整段框选对照。
    - 打开/刷新默认定位至最新日志（底部）。
  - 底部操作栏：`导出分享`（主按钮，生成含诊断头的临时 `.txt` 并调起系统分享）、`复制`（复制当前筛选结果）、`清空`（柔和危险色按钮，二次确认弹窗后调用 `clearAll` + `cozyting_clear_logs`）。
- **iOS SwiftUI 对齐实现**：使用 `NavigationLink` 进入 `LogViewerView`，采用 `ScrollViewReader` + `LazyVStack` 实现同款日期分割线、左侧色条卡片、`FlowLayout` 参数胶囊与 `DisclosureGroup` JSON 格式化展开（支持 `.searchable` 与原始/结构化切换）；通过 C ABI `cozyting_init_log_file` 将 Rust 核心日志落盘到 App Group / `Library/Caches` 或 `Application Support` 私有目录，导出采用 SwiftUI 原生 `ShareLink(item: url)` 分享临时诊断快照 `.txt`。

## 7. 系统控制中心快捷磁贴与悬浮控制卡（Android & iOS 双端对齐）

- **Android 端入口与生命周期（API 29–36 全兼容）**：
  - **控制中心快捷磁贴（`CastTileService`）**：复用与新版品牌图标（F2 深夜亮灯小屋 + 垂尾小猫 + 音符）同源的 `24dp` 纯白 `evenOdd` 真镂空单色矢量 `ic_stat_miplay`（同步用于状态栏常驻通知小图标与悬浮控制卡跳转圆钮）；空闲为 `STATE_INACTIVE`（副标题 `点击选择音箱`），投送中为 `STATE_ACTIVE`（副标题 `%d 台 · %d%%` 或 `%d 台 · 已暂停`；小米 HyperOS 等 1×1 圆形磁贴以图标亮/暗反显状态）。点击通过 `compat/QuickSettingsTileCompat.startActivityAndCollapse`（API 34+ 自动走 `PendingIntent` 重载，锁屏经 `unlockAndRun` 解锁后触发）收起系统栏并弹出透明悬浮控制卡，不拉起主界面；`MainActivity` 注册 `android.service.quicksettings.action.QS_TILE_PREFERENCES`，**长按磁贴直达 App 首页**（不再兜底跳转系统应用信息页）。
  - **通知栏直达入口（`CastService`）**：投送中常驻通知正文与首枚按钮 `设备与音量` 均直达同一悬浮控制卡。
  - **状态感知与首页/设置页双入口引导**：`CastTileService` 在 `onTileAdded` / `onStartListening` / `onTileRemoved` 与 `requestAddTileService` 回调中自动同步 `qsTileAdded` 状态；首页 `MainActivity` 输出设备卡片底部在「未添加且未手动关闭」时展示一条紧凑可关闭的 Style B 轻引导条（API 33+ 一键调起系统添加确认，API 29–32 弹出 3 步引导并支持试弹悬浮卡），添加成功或点击 `×` 后自动隐藏不再打扰；设置页「控制中心快捷磁贴」同步展示 `已添加` / `一键添加` 状态徽标。
- **悬浮控制卡视觉与复用结构（`QuickCastPanelActivity`）**：
  - 独立透明任务栈（`Theme.CozyTing.QuickPanel`、`taskAffinity=""`、`excludeFromRecents="true"`），左右外边距保持单侧 **`24dp`**（`screenW - 48dp` 且最大宽 `360dp`、小屏保底 `280dp`），确保在各尺寸手机屏幕上保持舒展的居中悬浮呼吸感；关闭主题自带黑幕（`backgroundDimEnabled=false`）避免双重压暗：Android 12+（API 31+）启用 `FLAG_BLUR_BEHIND` 窗口背景毛玻璃模糊配合轻透遮罩与磨砂玻璃卡面（深色 ~58% 透黑 / 浅色 ~70% 透白 + 细玻璃描边），API 29–30 自动提高不透明度回退以保障无模糊时的文字对比度；点击空白遮罩或返回键直接关闭并留在原音乐 App。
  - **柔和进出场动效**：禁用生硬的系统默认 Dialog 窗口切换动画，改由遮罩与卡片协同缓动——入场 `280ms`（强调减速曲线 `cubic-bezier(0.22, 1, 0.36, 1)`，`scale 0.92→1.0` + `translateY 18dp→0` + `200ms` 渐显），退场 `180ms`（强调加速曲线 `cubic-bezier(0.4, 0, 1, 1)`，`scale 1.0→0.94` + `translateY 0→14dp` + 渐隐）。
  - **样式 B 极致紧凑结构与 1:1 共享核心 UI**：与主界面共用 `NowPlayingCardController`（悬浮卡启用紧凑模式：`70dp` 封面 + `20dp` 音源角标、右侧信息与播控列 `marginStart=8dp / marginEnd=34dp` 贴近封面并为卡片右上角**新版窝爱听单色小标 `ic_stat_miplay`（16dp）圆钮**留出独立安全列，确保歌名/歌手与播控居中对齐且绝不与圆钮重叠、避免与下方刷新按钮误触；全宽 `4dp` 进度条，未授权通知使用权时折为引导条）、同款总音量细轨道滑块（含高音量保护与「全部音箱联动」调节）、以及「输出设备」标题栏（右侧依次排布数量文案、按需 `恢复上次` 胶囊与刷新圆钮）+ `本机播放` + 样式 B 多选音箱行与行内独立音量滑块（限高滚动防溢出）；无额外顶/底栏，底部直接以最后一张设备卡干净收口。
- **iOS 端对齐与平台边界（SwiftUI / WidgetKit）**：
  - iOS 18+ 在系统控制中心通过 `ControlWidget`（WidgetKit）提供对等「CozyTing / 窝爱听」快捷控制项，支持点击 Deep Link 呼出 App 内轻量 `.sheet` / `.presentationDetents([.medium, .large])` 悬浮控制卡（复用主界面的正在播放卡、总音量条与样式 B 音箱列表）；主界面底部在用户未关闭引导时提供对等的可关闭轻量添加指引条（受 iOS 限制无法一键弹窗添加控制中心项，点击展示手动编辑控制中心步骤）。
  - 受 iOS 沙箱与 `ControlWidget` 交互限制，iOS 无法在第三方 App 画面上方直接叠透底层页面级浮窗，也无法通过 `ControlWidget` 跳过系统 `RPSystemBroadcastPickerView` 首次录屏授权弹窗；需统一由 Deep Link 唤起 App 轻量控制面板承载。

## 8. macOS 端视觉与交互映射（Menu Bar 优先 · `apple/` 方案 A）

> 完整产品雏形、架构与 M1–M9 任务清单见 [`docs/MACOS_CLIENT_DESIGN.md`](../docs/MACOS_CLIENT_DESIGN.md)，工程红线见 [`apple/AGENTS.md`](../apple/AGENTS.md)。

- **主入口形态（对应移动端控制中心磁贴 + 悬浮卡 + 常驻通知）**：
  - 顶部菜单栏常驻新版 F2「深夜亮灯小屋 + 垂尾小猫」单色模板图标（Template Image），待机常规单色、投送中呈现活跃态；鼠标悬停图标支持滚轮按 ±`5%` 快速调节投送总音量（跨安全上限仍触发 Style B 高音量二次确认）。
  - 左键点击在任意桌面或全屏应用上方直接弹出毛玻璃悬浮控制卡（宽约 `352pt`，复用 `CozyTingDesignKit` 共享组件，承载四段式核心高频操作：顶栏与 4 档延迟胶囊 / 正在播放与总音量 / 单 App 常用音源快捷胶囊 / 输出设备多选与行内独立音量）；右键菜单提供快捷暂停/恢复、切回本机、打开完整主窗口、设置与退出。
- **视觉主题与品牌一致性**：
  - 严格消费 `design/tokens.json` 的 Style B 深/浅色语义色板与 `design/icons/*.svg`（设为 Template Image），支持 `跟随系统 / 浅色 / 深色` 三态切换与 `zh-CN / en / ja` 三语（集中收口在 `CozyTingDesignKit` 的 `Localizable.xcstrings`）。
  - 悬浮卡与可选主窗口采用原生 `.ultraThinMaterial` 毛玻璃与专辑封面柔和氛围底；设备行选中反显、高音量琥珀告警轨道、跨版本结构化更新日志弹窗与结构化时间线日志页（`LogViewerView`）与 Android 保持 1:1 语义一致。
- **平台能力与诚实降级边界（macOS 14.2+）**：
  - **单 App 捕获与本机静音**：直接通过原生 Core Audio Process Tap + `CATapDescription(.mutedWhenTapped)` 实现真·单 App 隔离与真·单 App 本机静音，无需无线调试配对向导，设置页仅保留「系统音频录制」权限状态引导与静音开关。
  - **常用音源管理**：通过 `NSWorkspace` 完整支持应用枚举、高清原图标、拖动排序、点击唤起与不断箱热切，配合 `kAudioProcessPropertyIsRunningOutput` 实现发声自动识别热切。
  - **正在播放区**：受 macOS 15.4+ `mediaremoted` 私有权限限制，对支持 AppleScript 的播放器（Apple Music、Spotify 等）展示完整封面/歌名/进度与播控；对其余客户端诚实降级展示「正在投送 · \<音源 App 图标与名称\>」及系统媒体键控制。
  - **音量键接管**：默认以菜单栏滚轮与面板快捷键调音量（零额外权限）；全局拦截 F11/F12 硬件音量键仅作为设置页可选高级开关并显式说明需要辅助功能权限。
