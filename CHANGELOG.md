# 更新日志（Changelog）

本文件面向用户与开发者记录主要版本变更，遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 约定。

---

## [Unreleased]

---

## [0.9.9-rc.1] - 2026-11-24

> 预发版（Pre-release）：新增应用内本地运行日志落盘、浏览与一键导出分享，并修复无线调试已配对设备默认名称。

### Added
- **应用内本地运行日志落盘与浏览/导出（L1 Rust + Android + iOS 规范同步）**：
  - L1 Rust 核心新增异步轮转日志转发模块（`core/src/diag_log.rs`），在保留原有 `CozyTingCore` Logcat 输出的同时异步落盘至应用私有目录 `core.log`（256 KB × 2 轮转，封顶 512 KB），并同步暴露 Android JNI 与 iOS C ABI（`cozyting_init_log_file` / `cozyting_recent_logs` / `cozyting_clear_logs`）。
  - Android 侧新增统一日志入口 `AppLog` / `AppLogStore`（异步轮转写入 `runtime.log`，封顶 512 KB，并兼容合并 `cast_ops.log`），纯本地沙箱存储、不含音频数据、零自动上传。
  - 设置页「连接与调试」卡片新增直达「**运行日志**」入口及独立浏览页（支持按「全部 / 告警与错误 / 核心协议 / 操作流水」筛选、刷新、复制、清空及附带诊断头的一键系统分享导出），同步补齐中/英/日三语资源与 `design/UI_SPEC.md`。

### Fixed
- **无线调试默认配对名称同步品牌名**：将 `AdbCrypto.encodeAndroidPublicKeyLine` 默认 `userHost` 从 `miplay@android` 更正为 `cozyting@android`（新配对后在系统「无线调试 → 已配对设备」中正确显示）。

---

## [0.9.8] - 2026-11-24

> 正式版（Stable Release）：**窝爱听（CozyTing）**全新品牌名与全新「深夜亮灯小屋 · 垂尾听歌小猫」桌面图标正式发布，全链路同步三语官网、设计规范与 60 秒宣传片。纯局域网运行，零云端、零账号、零遥测。

### Added
- **全新「深夜亮灯小屋与垂尾听歌小猫」App 图标（浅/深双版 + Android 13+ 单色版）**：采用纯扁平绘本几何色块，提供暖燕麦奶油浅色底（`#FDF4E6`）、深夜暖炭深色底（`#1B1F2B`）与 Android 13+ `evenOdd` 真镂空单色主题图标，严格落入 `66dp` 圆形安全区并同步沉淀 iOS `1024px` 浅/深/Tinted 跨端规范至 `design/UI_SPEC.md` 与 `design/tokens.json`。
- **三语 60 秒产品宣传片重构升级**：同步更新中/英/日三语宣传片（极简痛点开场 + 品牌实机定格 + 全新小屋 Logo 动态构建），并发布压缩版 Web 视频与三语封面图至官网。

### Changed
- **全链路品牌正式更名为「窝爱听 / CozyTing」**：规避既有重名风险，中文名统一为「窝爱听」，英文与日文统一为「CozyTing」；Android 包名升级为 `com.cozyting.app`，Rust 核心与命令行工具统一为 `cozyting-core` / `cozyting-cli`，公开分发仓与更新清单迁至 `JasirVoriya/cozyting-releases`（仅底层局域网协议保留 `MiPlay` 标识；从 `0.9.7` 旧包名 `com.miaocast.app` 升级的用户需单独安装新包）。
- **全品牌主色统一为暖珊瑚 `#E56D48`**：同步统一 Android 图标、README 徽章、三语官网、社交预览图（`social-preview.png`）与宣传片视觉主色。

---

## [0.9.7] - 2026-11-24

> 正式版（Stable Release）：**妙投（MiaoCast）**首个完整品牌正式版。支持任意 Android 10+ 手机在纯家庭局域网内将指定单个音乐 App 的声音毫秒级同频投送至最多 63 台小米 / 小爱 Wi-Fi 音箱（AGPL-3.0-only，零云端、零小米账号、零遥测）。

### Added
- **全链路三语原生支持（简体中文 / English / 日本語）**：Android App 全量文案三语对齐并在设置页提供零 AppCompat 依赖的四段式应用内语言切换（跟随系统 / 简体中文 / English / 日本語，Android 13+ 同步系统 `LocaleManager`）；L1 Rust 核心发射结构化更新错误码供各端本地化展示；官网同步上线 `/`、`/en/`、`/ja/` 静态多语言页面、架构浏览器与三语宣传视频。
- **无 Root 无线调试无感采集与双区音频焦点隔离（Android 11+/13+）**：内置 5 步无线调试配对向导，采用无障碍悬浮数字键盘自动识别 6 位配对码（无需悬浮窗权限、无需电脑或 Shizuku，同步保留通知栏长按输入兜底）；Android 13+ 无感模式下自动启用双区音频焦点隔离，手机刷视频不打断音箱播歌（单 App 静音实际表现以真机 ROM 为准）。
- **顶栏 4 档延迟模式快切与运行时热生效**：新增标准 / 均衡（400ms）/ 低延迟（250ms）/ 极速（150ms）与自定义 ms 快捷切换，投送中直接热重锚 `TIME_OFFSET` 即时生效，免断连重连。
- **常用音源长按拖动排序与投送中不断流热切换**：常用音源卡片与快捷胶囊支持长按拖动排序；投送中打开音源管理页不再中断播放，选中新音源后走单 UID + 签名 fail-closed 不断线热切换。
- **高音量保护与跨线二次确认（L1 Rust + Android + iOS 规范同步）**：总音量条与单音箱独立音量条接入 L1 跨线保护策略（默认开启、安全上限默认 `60%`，支持 `30%–90%` 步进 `5%` 调节），仅在从安全区向上跨过上限时弹出 Style B 确认框防误触过吵。
- **应用内只读更新检查与安全安装**：默认每 24 小时向公开仓直链 `raw.githubusercontent.com/JasirVoriya/miaocast-releases/main/update.json` 发起一次只读 HTTPS 检查（可一键关闭实现 100% 零外网请求），支持稳定版 / 预览版通道、跨版本区间更新日志以及强制 SHA-256 + 签名证书双重校验安装。
- **设备房间名同行展示与前台智能刷新**：设备列表支持单行轻量展示「设备名 · 房间名」；切回前台自动触发 30 秒防抖后台扫描（列表无变化时不重建闪烁），并全量升级为明暗双主题 Style B 圆角卡片弹窗。

### Changed
- **品牌与包名正式统一为「妙投 / MiaoCast」**：Android 包名统一为 `com.miaocast.app`（`arm64-v8a` 16KB 页对齐），发布产物与命令行工具统一命名为 `miaocast` / `miaocast-cli`，仅底层局域网协议保留 `MiPlay` 标识（从 `0.9.6` 及更早旧包名升级的用户请单独安装新包）。
- **精简采集引擎、静音说明与常驻通知文案**：收口低频证书重置入口至向导页底部，去除主卡片底部冗余提示，常驻通知正文按单台/多台定长呈现。

### Fixed
- **更新弹窗长日志不再顶掉下载按钮**：`UpdateDialogController` 改用自定义 `MaxHeightScrollView` 在 `onMeasure()` 首轮硬性钳制日志区最大高度（`≤ 230dp` 且 `≤ 屏高 36%`），确保长更新日志下底部下载与操作按钮始终完整可见。
- **设置页交互与状态同步稳定性**：修复设置页/向导页操作后滚动回顶、自定义开关圆点错位以及 `CastService` 与 `MainActivity` 投送路由状态偶发不同步问题；修复无感采集辅助进程非 PTY 启动与回环连接稳定性。

---

## [0.9.7-rc.10] - 2026-11-24

> 预发布版本（Pre-release）：修复长更新日志导致更新弹窗底部下载按钮被顶出屏幕的布局问题，并将应用内更新清单主通道与 GitHub Pages 彻底解耦，纯局域网运行，不含云端账号或遥测上报。

### Fixed
- **更新弹窗长日志不再顶掉下载按钮**：将 `UpdateDialogController` 的日志滚动区由 `onLayoutChange` 延迟改高改为自定义 `MaxHeightScrollView` 在 `onMeasure()` 首轮直接钳制高度（`≤ 230dp` 且 `≤ 屏高 36%`，常显垂直滚动条），确保更新日志较长时「立即下载并安装」「前往浏览器下载」与底部操作行始终完整可见，并补充 Robolectric 布局回归单测。
- **应用内更新清单主通道与 GitHub Pages 彻底解耦**：`UpdateConfig.MANIFEST_PRIMARY_URL` 切换为公开分发仓 `main` 根目录直链 `raw.githubusercontent.com/JasirVoriya/miaocast-releases/main/update.json`（Fastly CDN、`ETag/304`、零 API 限流），发版流水线在镜像 Release 产物完成后串行直推更新清单，彻底消除 GitHub Pages 同 SHA 静默去重与 `on: release` 触发风暴。

---

## [0.9.7-rc.9] - 2026-11-24

> 预发布版本（Pre-release）：面向预览通道用户验证简体中文（`zh-CN`）、英文（`en`）、日文（`ja`）全链路三语支持（Android App 应用内语言切换、L1 结构化更新错误码、三语宣传视频与 `/` / `/en/` / `/ja/` 静态多语言官网），纯局域网运行，不含云端账号或遥测上报。

### Added
- **Android App 三语本地化与应用内语言切换（零 AppCompat 依赖）**：全量用户可见文案抽离至 `values` / `values-en` / `values-ja`（457 键对齐，覆盖主界面、常用音源管理、设置页、5 步无线调试引导、悬浮配对键盘、前台通知与更新弹窗）；设置页新增「显示语言」4 段切换（跟随系统 / 简体中文 / English / 日本語），Android 13+ 同步系统 `LocaleManager`（`locales_config.xml`），API 29–32 通过 `createConfigurationContext` + `LocaleList` 零依赖生效。
- **L1 Rust 结构化多语言更新错误码与跨端规范同步**：`core/src/update.rs` 与 `core/include/miaocast_core.h` 新增向后兼容的结构化 `error_code` / `error_detail` / `notes_is_fallback` 字段供各端按当前语言本地化展示；同步更新 `design/UI_SPEC.md` 三语规范与 iOS `Localizable.xcstrings` 边界；`scripts/ui-preview.sh` 扩展 `--locale zh|en|ja|all --export-docs` 支持三语暗/亮实机视图截图导出。
- **三语宣传视频与静态多路径官网（`/` · `/en/` · `/ja/`）**：`promo/` Remotion 工程实现文案字典化与中/英/日独立字体回退并产出三语 720p MP4 与封面；GitHub Pages 新增 `/en/` 与 `/ja/` 独立静态官网及交互式架构浏览器，补齐双向 `hreflang`、本地化 JSON-LD、`sitemap.xml`、`llms.txt` 与 `README_JA.md`。

---

## [0.9.7-rc.8] - 2026-11-23

> 预发布版本（Pre-release）：面向预览通道用户验证「常用音源管理页不断投热切」与设置页采集引擎文案精简，纯局域网运行，不含云端账号或遥测上报。

### Fixed
- **进入「常用音源与选择」页不再中断当前投送**：移除打开 `AppPickerActivity` 前强制停止投送并切回本机播放的旧逻辑。投送中进入该页增删/排序常用音源、开关「自动识别」或点击当前正在投送的同一 App 均全程不断流；点选新音源返回后改走单 UID + 签名 fail-closed 不断线热切换（热切成功后再更新持久化选中项，若系统限制热切失败回滚原音源则保持界面展示与实际捕获一致并弹出轻提示）。

### Changed
- **设置页采集引擎与静音说明精简**：去除设置页中重复的无线调试「测试连接 / 清除证书」大按钮，保留单行配对状态直达 5 步无线调试配置向导，并将低频「清除本机配对证书」统一收口至向导页底部（附 Style B 二次确认防误触）；同步精简「投送时手机静音」副标题与实时状态文案（无感单 App 静音实际效果仍以真机 ROM 为准）。

---

## [0.9.7-rc.7] - 2026-11-23

> 预发布版本（Pre-release）：面向预览通道用户验证新能力与界面修复，不含云端账号或遥测上报。

### Added
- **高音量保护与跨线二次确认（L1 Rust + Android + iOS 规范同步）**：总音量条与单音箱独立音量条接入 L1 跨线保护策略（默认开启、安全上限默认 `60%`，设置页支持开关与 `30%–90%` 步进 `5%` 调节）；仅在从安全区向上跨过上限时弹出 Style B 确认框防止误触过吵，进入高音量区时轨道与滑块切换为琥珀警告色，安全区内保持纯净细胶囊轨道。
- **应用内只读更新检查与跨版本更新日志**：新增「关于与更新」设置卡与非阻塞首页更新轻提示条，默认每 24 小时向公开静态清单 `update.json` 发起一次只读 HTTPS 检查（可一键关闭实现 100% 零外网请求），支持「稳定版 / 预览版 (RC)」通道切换、跨版本区间更新日志展示及 SHA-256 + 签名证书双重校验安装。
- **无障碍自动无线调试配对与通知栏长按兜底（Android 11+）**：移除悬浮窗权限需求，在 5 步无感采集配置向导中新增无障碍浮动数字键盘与 6 位配对码自动识别，并同步保留通知栏长按输入兜底；全应用弹窗统一升级为明暗双主题 Style B 圆角卡片弹窗。
- **设备房间名同行展示与前台智能刷新**：设备列表标题支持同单行轻量展示「设备名 · 房间名」；切回前台时自动触发 30 秒防抖后台扫描（列表无变化时不重建闪烁），并精简主卡片底部冗余提示。

### Changed
- **品牌与包名统一为「妙投 / MiaoCast」**：Android 包名统一为 `com.miaocast.app`（`arm64-v8a` 16KB 页对齐），发布包与 CLI 工具统一命名为 `miaocast` / `miaocast-cli`，仅底层局域网音频协议保留 `MiPlay` 标识（从旧包名升级的用户需单独安装新包）。

### Fixed
- **设置页与配对向导滚动回顶问题**：复用 `ScrollView` 并在首帧绘制前钳制恢复 `scrollY`，修复下滑后点击高音量保护、采集引擎、捕获范围、更新通道、测试连接或切回前台时自动滚回顶部的问题。
- **设置页自定义开关圆点错位问题**：修复 `switchRow` 与 `compactSwitch` 仅修改 `marginStart` 未触发布局方向重解析导致底色切换而白色圆点卡在原位的问题。
- **投送路由状态同步**：修复 `CastService` 与 `MainActivity` 之间活跃投送路由状态偶发不同步的问题。

---

## [0.9.6] - 2026-11-21

### Added
- **局域网只读 `miName` 解析与三级缓存兜底（L1 Rust + Android + iOS C ABI 同步）**：新增极简只读 TCP 8899 `GET_DEVICE_INFO`（`0x001e` → `0x001f`）`miName` 查询（先明文 legacy、失败回退 SafetyAuth，全程不发 `OPEN`/`CLOSE`/`SET_*`，结束仅 `tcp_shutdown_only`）；`core/src/engine.rs` 新增 `discover_json_with_options` 并同步暴露 JNI `discoverJsonEx` 与 C ABI `miplay_discover_json_ex`，按需并发查名（单台超时 `800ms`），音箱与电视/显示器一视同仁、投送中设备自动跳过。
- **Android 免投屏感音频采集设计方案**：新增 `docs/ANDROID_SHIZUKU_CAPTURE_DESIGN.md`（Shizuku 方案设计，待实现）并更新 README 文档索引。

### Changed
- **输出设备纯名展示与防回退缓存合并**：设备列表标题改为单行纯设备名（移除 ` · {model}` 冗余拼接与房间名后缀，修复电视/显示器等设备名称重复拼接截断问题）；本地设备缓存新增 `name_from_device_info` 来源标记，冷启动直接沿用已缓存 `miName`（仅首次发现/旧缓存或手动刷新时触发查名），单次查询超时自动保留缓存 `miName` 或兜底 mDNS 老名字，升级后首次扫描自动洗净老格式缓存。

---

## [0.9.5] - 2026-11-21

### Added
- **常用音源自由增删与单 App 自动识别策略**：重构「常用音源与选择」页，支持用户自由增删「常用音源」名单（首次默认预填本机已安装的内置音乐/播客 App），并为每个常用 App 提供独立的「自动识别」开关；待机时仅从常用名单中开启自动识别的 App 内匹配正在播放的 `MediaSession`，命中后仍经 `CaptureTargetResolver` 执行严格单 UID + 签名 fail-closed 校验；设置页新增全局总开关（投送中绝不打断，手动切源防旧会话拉回）。
- **Android JVM 本地 UI 截图预览工作流**：新增基于 Robolectric Native Graphics（Skia）的 `UiPreviewRenderTest` 与一键脚本 `scripts/ui-preview.sh`，无需编译 APK 或连接真机即可在十余秒内渲染主界面、常用音源管理页与设置页深/浅双版 PNG。
- **L1 Rust 核心下沉与跨端 FFI 解耦（#7）**：新增 `core/src/engine.rs`（句柄表与多箱并发协调器）与 `core/src/device_catalog.rs`（设备发现分类、默认过滤、历史缓存合并、路由互斥状态机与认证模式解析）；新增面向 iOS Swift 的 C ABI (`core/src/ffi_c.rs`、`core/include/miplay_core.h`、`core/include/module.modulemap`) 与 `scripts/build-ios-xcframework.sh`。
- **原创品牌视觉资产与跨端设计文档**：新增扁平同心音箱原创 Logo（浅/深双色）、Android Adaptive Icon / Android 13+ 单色主题图标 / 通知栏小图标、README 横幅、零依赖 [交互式架构浏览器](docs/architecture.html) 与 iOS 客户端/局域网 AirPlay 2 桥接设计方案。
- **GitHub 开源工程化与双端治理规范**：新增结构化 YAML Issue 表单、PR 模板、CI 自动测试与 16KB 对齐校验、Tag 自动发布 APK + 多平台 `miaocast-cli` 流水线、GitHub Pages 自动部署、Dependabot、`AGENTS.md`（个人 `.local/` 隔离与 Android/iOS 双端同步红线）、`CONTRIBUTING.md`、`SECURITY.md`、`docs/COMPATIBILITY.md` 与 `README_EN.md`。

### Changed
- **正在播放区与顶栏视觉全面打磨**：左侧 `96dp` 稳定圆角封面（`Canvas.clipPath` + `clipToOutline` 双重裁剪）+ 右下角 `26dp` 音源 App 圆形角标（点击直达对应音乐 App）；右侧歌名/歌手/宽间距播控三钮统一沿中轴线与 `96dp` 高度双居中，卡片左/中/右等距 `16dp`；顶栏移除主题快切钮并将设备刷新钮下沉至「输出设备」标题栏右侧，设置图标重绘为标准 6 齿齿轮；锁定 `SeekBar` 细轨道为 `4dp` 垂直居中防止系统拉伸。
- Android `CastService` 移除约 218 行手写 `routeLock` / reconcile 状态机并委托给 L1 Rust `Engine`；`LatencyDisplay` 优先消费核心归一化的 `ui_state` 与 `display_latency_ms`。

---

## [0.9.4] - 2026-11-20

### Added
- **正式开源发布基线**：全仓以 `AGPL-3.0-only` 许可证发布，提供正式签名构建脚本 `scripts/build-release.sh` 与 16KB 对齐验收脚本 `scripts/verify-acceptance-apk.sh`。
- **设备行内延迟展示（#4）**：移除顶部汇总与 IP 尾号，在每台活跃音箱行内原位显示整数 `ms`（连接/采样缺值显示转圈，停止/失败/取消选择时隐藏）。
- **无名非 MiPlay 节点智能过滤（#1）**：mDNS 同 IP 合并优先保留 `_mi-connect` (`apps=[5]`) 节点，默认隐藏未声明 MiPlay 能力的非电视节点，并提供设置内「显示全部发现设备」兜底开关。
- **显式本机静音策略（#2）**：新增 `GLOBAL_STREAM_MUTE`（默认）与 `KEEP_LOCAL_OUTPUT`（保留本机出声）双策略开关，诚实说明 Android 普通权限边界。
- **独立操作日志**：`CastService` 新增 64KB 轮转 `files/cast_ops.log`，避免关键操作事件被 1Hz 统计日志冲刷。

### Fixed
- **切歌间隙短暂双出声（#3）**：`LocalOutputMute` 引入 6 拍有声初始验证锁定（`probeLocked`），锁定后切歌静音不再误判并解除本机静音；外部按键取消静音后不强行循环重静音。
- **多音箱并发投送与停止死锁**：修复 `NativeBridge_stop` 与 `media-clock` 的通道死锁（先释放 `feed` 再停会话并向音箱下发 `CLOSE`）、消除首台建连窗口期丢点击、新增中途加入 `connecting` 占位与断线 `pending` 退避统计连续性。
- **冷启动权限与构建安全（#5）**：修复 API 29–32 冷启动误弹权限框问题；移除构建脚本中的硬编码签名口令并加入防回归契约单测。

---

## [0.9.3] - 2026-11-17

### Added
- **Android 10（API 29）完整兼容层**：新增 `BlurCompat`（API 31+ `RenderEffect` / API 29–30 压暗降级）、`WindowInsetsApplyCompat`、`NotificationPermissionCompat`、`PackageSigningCompat`，并将 `NewApi` Lint 设为 `fatal` 门禁。
- **严格单 App 捕获隔离与验收变体**：`AudioPlaybackCapture` 强制单 UID + 白名单 + 签名校验（fail-closed）；新增独立包名 `acceptance` 变体与 `:testtones` 三 UID 声道隔离验收套件。

---

## [0.9.2] - 2026-11-16

### Fixed
- 修复 `CastService` 构造期过早初始化 `localMute` 导致的 `getApplicationContext()` NPE 崩溃。
