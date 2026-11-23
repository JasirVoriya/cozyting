# 妙投（MiaoCast）架构与全仓源码深度手册

> 本文档是 [README.md](../README.md) 的深度工程配套手册，完整收录了 **L1–L3 跨端三层架构细节、4 条正交数据流向、全仓模块与文件职责字典（3.1–3.8）、19 个 JVM 契约测试清单、单进程实时线程与有界队列模型、6 条局域网网络通路及 6 阶段生命周期不变量**。
>
> 💡 **交互式图形探索**：如需在浏览器中点击任意模块高亮追踪其全部 **↑ 上游来源 / ↓ 下游消费者**、按阶段演练线程切换或实时搜索文件职责，可访问 **[在线交互式架构浏览器](https://jasirvoriya.github.io/miaocast-releases/architecture.html)** 或直接打开本地零依赖页面 [`docs/architecture.html`](architecture.html)。

---

## 一、跨端三层架构总览（L3 表现层 · L2 系统层 · FFI 桥接 · L1 Rust 核心）

![妙投（MiaoCast）跨端三层架构](assets/cross-platform-architecture.svg)

| 分层 | 归属 | Android（已实现） | iOS（C ABI 就绪 · UI 规划中） | 说明 |
| --- | --- | --- | --- | --- |
| **L3 · 原生表现与共享设计层** | 双端共用 `design/` + 各自原生 UI | Kotlin 原生 UI（`MainActivity` / `AppPickerActivity` / `SettingsActivity` / `ui/*`） | SwiftUI 原生 UI（`CastMainView` / `SourceSlot` / `SettingsView`） | 共用仓库 `design/`（`tokens.json` 明暗双主题、`icons/*.svg`、`UI_SPEC.md`）保证双端视觉统一 |
| **L2 · 平台系统接口层** | 各自原生实现 | `AudioPlaybackCapture`（单 UID）+ `MediaCodec` AAC + `CastService` FGS + `cast/*` + `compat/*` | `ReplayKit` 广播扩展 + `AudioToolbox` AAC + `AVAudioSession` | 强依赖 OS 媒体、权限与后台能力；对外统一向 L1 喂入 48kHz 裸 AAC 帧 |
| **FFI · 跨语言桥接插头** | 薄类型与句柄转发 | `NativeBridge.kt` ⇄ `core/src/ffi.rs`（JNI，16KB 对齐 `.so`） | `core/include/miplay_core.h` ⇄ `core/src/ffi_c.rs`（C ABI，`.xcframework`） | 无业务逻辑，统一委托给 `core/src/engine.rs` 与 `core/src/device_catalog.rs` |
| **L1 · 公共核心层** | **全平台 100% 共享** | 共用 `core/`（编译为 `cdylib`） | 共用 `core/`（编译为 `staticlib`） | 纯 Rust 零平台依赖：mDNS 发现、8899 控制与双认证、WFD RTSP、UDP 时钟主、多箱同步、流媒体打包、多箱并发协调器（`engine`）与路由/延迟状态归一化（`device_catalog` / `latency`） |

---

## 二、整体数据流向（4 条正交链路）

![妙投（MiaoCast）整体架构与数据流向](assets/architecture-overview.svg)

| 数据流 | 颜色 / 标识 | 端到端上下游路径 | 协议与关键特征 |
| --- | --- | --- | --- |
| **音频媒体主链路** | 青色实线 | 目标音频 App → L2 `PlaybackCaptureFactory` (`AudioRecord` 48kHz s16le) → `AacEncoder` (`MediaCodec` 256kbps 裸 AU) → FFI `pushAac` / `sync_channel(96)` → L1 `session::media-clock` (`SharedTimeline` PTS/PCR) → Per-Receiver `media/*` (`Packetizer`) → 音箱反向 Audio TCP | 48kHz 立体声，21.333ms/AU；`7B ADTS → PES(0xC0) → 188B MPEG-TS → RTP(PT=33) → $+24bit` 线帧 |
| **控制与认证流** | 琥珀虚线 | L3 `MainActivity` / `miplay-cli` / `DevMain` → L2 `CastService` → FFI → L1 `engine`（串行 reconcile 协调器）→ `control` + `auth/*` (`Legacy` / `Safety`) → 音箱正向 TCP `8899` | 支持旧式明文（MD5+HMAC-SHA1）与现代 Safety（OPack + 双向 HMAC + 双向链式 AES-128-CBC）；5s 心跳 / 15s 看门狗 |
| **时钟与组同步流** | 翡翠点虚线 | L1 `EPOCH` (`CLOCK_MONOTONIC`) + `TimerServer` (UDP 40B) + `SourceRtsp` (反向 WFD TCP) ⇄ 局域网音箱集群 → 汇合 `OffsetBarrier` | 建连期先 `flush()` 积压再预读 14 AU 定共享 `TIME_OFFSET`；Timer / `TIME_OFFSET` / PCR 严格同源 |
| **发现与遥测反馈流** | 紫色短虚线 | mDNS (`224.0.0.251:5353`) + 8899 `NOTIFY` + L1 `device_catalog` / `stats_json_with_overlay` + L2 `PcmProbe` / `NowPlayingTracker` → `CastService` (1Hz 轮询) → L3 `MainActivity` 原位刷新 | 合并 `_mi-connect` (`apps=[5]` 优先) 与 `_lyra-mdns`；按设备原位更新归一化 `ui_state`、整数延迟 `ms`、静音锁定态与播放信息 |

---

## 三、全仓分层模块与文件职责字典（按 L3 → L2 → FFI → L1 → 工程支撑展开）

### 3.1 L3 · 跨端共享设计规范与 Android 原生表现层（`design/` 与 Android UI）

| 文件路径 | 核心职责与关键机制 |
| --- | --- |
| `design/tokens.json` | 跨端共享设计令牌（明暗双主题语义色板、圆角 `24/16/12/999`、字号层级与间距规范），供 Android 与 iOS 共用 |
| `design/UI_SPEC.md` | 跨端 UI 结构与交互规范（顶栏、正在播放、平台音源槽位、输出设备列表 4 段式布局） |
| `design/icons/*.svg` | 20 枚 `24×24` 统一线宽跨端矢量图标源文件（播控、音箱/手机/电视、明暗主题、搜索、导航等） |
| `android/app/src/main/java/com/miplay/sender/MainActivity.kt` | Android 主界面面板：顶栏状态胶囊与明暗快切、NowPlaying 进度平滑走字、音源槽位、1Hz 原位增量刷新设备行内延迟与转圈、音量百分比及 MediaProjection 授权发起 |
| `android/app/src/main/java/com/miplay/sender/AppPickerActivity.kt` | 音源 App 选择器：最近使用与推荐音乐分组、带清空按钮的搜索栏、`LruCache` 异步图标加载，校验保存目标包名与签名摘要 |
| `android/app/src/main/java/com/miplay/sender/SettingsActivity.kt` | 独立设置页：外观主题三段切换（跟随系统/浅色/深色）、权限实时状态与本机静音策略开关、折叠式连接与调试参数 |
| `android/app/src/main/java/com/miplay/sender/ui/ThemeMode.kt` | 主题模式枚举（`SYSTEM` / `LIGHT` / `DARK`）、持久化读写与当前夜间模式判定 |
| `android/app/src/main/java/com/miplay/sender/ui/UiPalette.kt` | 对齐 `design/tokens.json` 的 Android 明暗双主题语义调色板 |
| `android/app/src/main/java/com/miplay/sender/ui/UiComponents.kt` | 纯代码通用 UI 组件库：圆角卡片、按压 Ripple、细轨道定制滑块、分段选择器与自适应输入框 |
| `android/app/src/main/java/com/miplay/sender/NowPlayingNotificationListener.kt` | 声明 `NotificationListenerService` 以合法获取 `MediaSessionManager` 活跃会话（仅用于读取正在播放信息与播控，不读取上传通知正文） |
| `android/app/src/main/AndroidManifest.xml` | 主应用清单：权限最小化（不申请 `QUERY_ALL_PACKAGES`）、`<queries>` 声明 `MAIN/LAUNCHER`、FGS 类型 `mediaProjection\|mediaPlayback` |
| `android/app/src/acceptance/AndroidManifest.xml` | `acceptance` 变体叠加清单：仅在独立验收包中注册 `LocalFilterProbeService` |
| `android/app/src/main/res/values(-night)/themes.xml` | DayNight 无 ActionBar 基础主题与状态栏/导航栏底色配置 |
| `android/app/src/main/res/drawable/ic_ui_*.xml` | 由 `design/icons/*.svg` 转译的 20 枚 Android 页面内 VectorDrawable 图标 |
| `android/app/src/main/res/drawable/ic_protocol_*.xml` | 4 枚投送协议矢量徽标（`miplay` / `dlna` / `airplay` / `gcast`） |
| `android/app/src/main/res/mipmap-anydpi-v{26,33}/ic_launcher*.xml` | 原创扁平同心音箱 Adaptive Icon（含浅/深色 `drawable(-night)` 资源、Android 13+ 单色主题图标与 `ic_stat_miplay.xml` 通知小图标） |

### 3.2 L2 · 平台前台服务、路由、静音与媒体状态层（`CastService` 与 `cast/`）

| 文件路径 | 核心职责与关键机制 |
| --- | --- |
| `android/app/src/main/java/com/miplay/sender/CastService.kt` | 核心前台服务：管理 FGS 与 `WakeLock`/`WifiLock`、运行单 UID 捕获编码循环、委托 L1 Rust `Engine` 串行协调目标与活跃音箱、1Hz 统计轮询及 64KB 轮转 `cast_ops.log` |
| `android/app/src/main/java/com/miplay/sender/AacEncoder.kt` | 封装 Android `MediaCodec` 硬件 AAC-LC 编码（48kHz 立体声 256kbps，产出不含 ADTS 头的裸 AU） |
| `android/app/src/main/java/com/miplay/sender/LatencyDisplay.kt` | 优先消费 L1 Rust 归一化的 `ui_state` / `display_latency_ms`（保留旧格式回退），驱动每台设备行内指示器（`Value` 整数 ms / `Loading` 转圈 / `Hidden`） |
| `android/app/src/main/java/com/miplay/sender/AuthModeResolve.kt` | 将 UI 选项与调试 Intent 映射为认证模式整数（`0=auto` / `1=legacy` / `2=safety`，底层逻辑已同步下沉至 `device_catalog.rs`） |
| `android/app/src/main/java/com/miplay/sender/cast/CastRouteSelection.kt` | 路由与设备发现 Kotlin 模型封装（与 L1 `device_catalog.rs` 对齐）：处理 `This device` 与多选音箱/电视互斥、6 列 mDNS TSV 解析、默认过滤与缓存合并 |
| `android/app/src/main/java/com/miplay/sender/cast/CastProtocol.kt` | 协议枚举定义与 `castImplemented` 门禁，阻止未实现协议（DLNA/AirPlay/Cast）假装投送成功 |
| `android/app/src/main/java/com/miplay/sender/cast/ProtocolBadge.kt` | 将协议枚举映射为矢量图标、主题自适应配色与无障碍标签 |
| `android/app/src/main/java/com/miplay/sender/cast/LocalOutputMute.kt` | 本机 `STREAM_MUSIC` 静音与探针状态机：支持 `GLOBAL_STREAM_MUTE` / `KEEP_LOCAL_OUTPUT` 双策略、6 拍有声验证锁定（修复切歌短暂双出声）与外部解除检测 |
| `android/app/src/main/java/com/miplay/sender/cast/AndroidStreamMusicVolume.kt` | `AudioManager` 读写与 mute 适配层（要求通过已附加 Context 延迟构造，防止 Service 构造期 NPE） |
| `android/app/src/main/java/com/miplay/sender/cast/NowPlayingTracker.kt` | 订阅选中源 App 的 `MediaController` 回调，提取曲目/艺人/专辑图/播放进度并转发播控命令 |
| `android/app/src/main/java/com/miplay/sender/cast/NowPlayingInfo.kt` | 正在播放不可变数据模型与诚实占位格式化（无元数据时不伪造曲目） |
| `android/app/src/main/java/com/miplay/sender/cast/PrefsDiscoveredDeviceStore.kt` | `SharedPreferences` 持久化最近发现的合法 MiPlay 设备（最多 16 台），防止单次 mDNS 丢包引起列表闪烁 |

### 3.3 L2 · 严格单 App 捕获与数字域探针层（`capture/`）

| 文件路径 | 核心职责与关键机制 |
| --- | --- |
| `android/app/src/main/java/com/miplay/sender/capture/CaptureModels.kt` | 目标应用身份快照、解析结果（`Ok` / `Fail` 原因枚举）与存储接口定义 |
| `android/app/src/main/java/com/miplay/sender/capture/CaptureTargetResolver.kt` | 启动前实时校验目标包卸载/不可见/跨用户/共享 UID/签名变更，任一不满足均 **fail-closed** 拒绝捕获 |
| `android/app/src/main/java/com/miplay/sender/capture/AndroidPackageIdentityLookup.kt` | 调用 `PackageManager` 实时查询目标包 UID、同用户边界、`sharedUserId` 与 SHA-256 签名摘要 |
| `android/app/src/main/java/com/miplay/sender/capture/CaptureConfigSpec.kt` | 捕获配置前置规格校验：强制单一合法非负 UID 与 `USAGE_MEDIA` / `USAGE_GAME` / `USAGE_UNKNOWN` 白名单 |
| `android/app/src/main/java/com/miplay/sender/capture/PlaybackCaptureFactory.kt` | 唯一合法 `AudioPlaybackCaptureConfiguration` 构建入口，强制绑定 `addMatchingUid(uid)`，杜绝静默退化为全系统捕获 |
| `android/app/src/main/java/com/miplay/sender/capture/ProjectionSessionPolicy.kt` | 约束 Android 14+ `MediaProjection` 每次会话重新授权、切换目标立即停旧会话与标准停止清理顺序 |
| `android/app/src/main/java/com/miplay/sender/capture/LaunchableAppCatalog.kt` | 可启动应用排序、主流音乐应用推荐标注与主面板快捷音源芯片生成 |
| `android/app/src/main/java/com/miplay/sender/capture/AndroidLaunchableApps.kt` | 通过 `ACTION_MAIN` / `CATEGORY_LAUNCHER` 枚举同用户可见应用并加载应用图标 |
| `android/app/src/main/java/com/miplay/sender/capture/PrefsTargetAppStore.kt` | 持久化目标包名、标签、签名摘要与最近使用列表（**绝不持久化易漂移的 UID**） |
| `android/app/src/main/java/com/miplay/sender/capture/InMemoryTargetAppStore.kt` | JVM 单元测试用内存实现 |
| `android/app/src/main/java/com/miplay/sender/capture/PcmProbe.kt` | 16-bit 立体声数字域探针：计算 RMS / 峰值（驱动静音锁定）及 Goertzel 1kHz/2kHz 目标频点能量（驱动本地隔离验收） |
| `android/app/src/main/java/com/miplay/sender/capture/LocalFilterAcceptanceGate.kt` | 验收本地过滤门禁：确保无音箱即可跑本地 UID 过滤探针且绝不触发任何 MiPlay 网络路径 |

### 3.4 L2 · Android API 29–36 兼容与变体隔离层（`compat/`）

| 文件路径 | 核心职责与关键机制 |
| --- | --- |
| `android/app/src/main/java/com/miplay/sender/compat/BlurCompat.kt` | 背景毛玻璃兼容调度：API 31+ 走 `RenderEffect`，API 29–30 自动降级为明暗主题自适应叠色 |
| `android/app/src/main/java/com/miplay/sender/compat/BlurEffectApi31.kt` | 隔离 API 31 `RenderEffect` 类引用，避免 Android 10 ART 类校验异常 |
| `android/app/src/main/java/com/miplay/sender/compat/WindowInsetsApplyCompat.kt` | 统一基于 AndroidX `WindowInsetsCompat` 与 `WindowInsetsControllerCompat` 适配 Edge-to-Edge 状态栏/导航栏与图标明暗反色 |
| `android/app/src/main/java/com/miplay/sender/compat/NotificationPermissionCompat.kt` | 隔离 API 33+ `POST_NOTIFICATIONS` 运行时请求，确保 API 29–32 冷启动零权限弹窗 |
| `android/app/src/main/java/com/miplay/sender/compat/PackageSigningCompat.kt` | 使用 API 28+ `GET_SIGNING_CERTIFICATES` 兼容读取 APK 签名证书链 |
| `android/app/src/main/java/com/miplay/sender/compat/CaptureSelectionLayers.kt` | 统一“App 选择器 UID 权威 vs 系统 MediaProjection 授权框”两层边界文案与日志规范 |
| `android/app/src/main/java/com/miplay/sender/compat/AcceptanceAudioDefaults.kt` | 区分正式包与 `acceptance` 变体的默认音箱初始音量（30% vs 0%）与停止后本机音量恢复策略 |

### 3.5 FFI · 跨语言原生桥接层（Android JNI + iOS C ABI）

| 文件路径 | 核心职责与关键机制 |
| --- | --- |
| `android/app/src/main/java/com/miplay/sender/NativeBridge.kt` | Kotlin 侧 `external` 接口声明与 `libmiaocast_core.so` 加载器（支持 `-Dmiplay.lib` 供 `app_process` 直接调试） |
| `core/src/ffi.rs` | Android JNI 薄导出层：仅做 JVM 字符串/字节数组转换，全部会话、协调与设备目录逻辑委托给 `core/src/engine.rs` 与 `device_catalog.rs` |
| `core/src/ffi_c.rs` | iOS / 跨平台 C ABI 薄导出层：导出 18 个 `miplay_*` C 函数与 `miplay_string_free` 内存释放入口，供 Swift / Objective-C 直接调用 |
| `core/include/miplay_core.h` / `module.modulemap` | 标准 C11 头文件与 Clang Module 映射定义（可直接打包进 `MiPlayCore.xcframework` 供 Xcode `import MiPlayCore`） |

### 3.6 L1 · Rust 协议、并发协调、媒体打包与多音箱同步核心层（`core/src/`）

| 文件路径 | 核心职责与关键机制 |
| --- | --- |
| `core/src/lib.rs` | `miaocast-core` 模块总入口与统一 `Error` / `Result` 错误类型 |
| `core/src/engine.rs` | 平台无关核心门面：全局句柄表、`FEED_QUEUE_AUS = 96`（≈2s）有界喂入队列、多箱并发协调器（吸收首台建连期并发点选与延迟音量、串行 reconcile、中途取消与重试、`connecting` 叠加输出）及先 `drop(feed)` 无死锁停止 |
| `core/src/device_catalog.rs` | 跨端共享设备目录与路由状态机：6 列发现解析、TV 启发式、默认非 MiPlay 节点过滤、历史缓存合并（上限 16 台）、本机↔多箱路由互斥切换、认证模式解析与零依赖 JSON 编解码 |
| `core/src/discovery.rs` | mDNS 组播（`224.0.0.251:5353`）PTR 构造与响应解析、`_mi-connect` (`apps=[5]` 优先) / `_lyra-mdns` 同 IP 合并与 6 列 TSV 输出 |
| `core/src/netinfo.rs` | Wi-Fi 频段探测与播放缓冲延迟策略（`≥4900MHz` 5GHz → 800ms，否则 1000ms） |
| `core/src/clock.rs` | 进程内统一单调微秒时钟 `EPOCH`（`CLOCK_MONOTONIC`），被 TimerServer / `TIME_OFFSET` / PCR 共同引用 |
| `core/src/clock_sync.rs` | UDP `TimerServer` 时钟主：响应 40B 小端时间戳报文，供接收端锁定发送端单调时钟偏移与频偏 |
| `core/src/commands.rs` | TCP 8899 控制命令号全集（`0x00xx` 基础控制/媒体命令与 `0x14xx` Safety 安全命名空间） |
| `core/src/frame.rs` | `$` 控制帧（9B 头）编解码、5B 标量、4B 音量、`NOTIFY` 与 `GetDeviceInfo` TLV 解析 |
| `core/src/auth/mod.rs` | 认证链路抽象枚举（`LegacyClear` vs `Safety`），两条认证链路严格隔离不可混用 |
| `core/src/auth/legacy.rs` | 旧式明文认证：响应 `0x0028` 数字挑战，使用 `MD5("0.0.0.0")` 与 HMAC-SHA1 生成 `0x0029` 应答 |
| `core/src/auth/safety.rs` | 现代 `SafetyAuth` / `SafetyData`：端点密钥派生、OPack 包装、双向 HMAC 互验与双向链式 AES-128-CBC 加解密 |
| `core/src/control.rs` | `ControlSession` 与 `RuntimeWriter`：8899 引导、Safety 握手、`preparePlayback`、`Open`、5s 心跳、音量 ACK 匹配、暂停与间隔 29ms 双 `Resume` |
| `core/src/rtsp.rs` | WFD 反向 RTSP 服务端（`SourceRtsp`）：处理音箱反连后的 M1–M5 能力协商、`SETUP`/`PLAY`、`TIME_OFFSET` 下发与运行期保活 |
| `core/src/timeline.rs` | `SharedTimeline`（90kHz PTS / 27MHz PCR 前向单调修正与暂停重锚）与 `SharedTimeOffset` 共享时间戳管理 |
| `core/src/latency.rs` | 发送端端到端延迟估计公式（`capture_to_play_est_ms` / `schedule_ahead_ms`）、UI 归一化状态 `ReceiverUiState` 与 `display_latency_ms` 有效域边界定义 |
| `core/src/media/mod.rs` | 每台接收端独立 `Packetizer` 调度器：维护各自 RTP 序号、MPEG-TS 连续计数及 PAT/PMT/PCR 周期插入 |
| `core/src/media/adts.rs` | 7 字节 MPEG-2/4 AAC-LC 48kHz 立体声 ADTS 帧头封装 |
| `core/src/media/mpegts.rs` | ISO/IEC 13818-1 单节目 MPEG-TS 复用器（PAT / PMT / PCR / PES `0xC0` / 188B 分片与标准 MPEG CRC32） |
| `core/src/media/rtp.rs` | RTP v2（`PT=33` MP2T，`SSRC=0xDEADBEEF`，每包 ≤7×188B）与 `$+24bit` 长度 TCP 线帧封装 |
| `core/src/session.rs` | `CastSession` 多箱运行引擎：并发建连、`OffsetBarrier` 冲刷积压 + 预读 14 AU、`media-clock` 广播、`supervisor` 15s 看门狗与退避重连、动态增删与 `stats_json_with_overlay` 状态合并 |
| `core/src/group.rs` | 支持左右声道分路由（`ChannelRoute`）与 47 AU 历史缓冲补发的 `Broadcaster` / `GroupSession` 抽象 |
| `core/src/inspect.rs` | 显式只查询诊断会话：强制命令白名单、过滤敏感字段、退出仅 `TCP shutdown` 绝不发送 `CLOSE(0x0002)` |

### 3.7 工程支撑 · 命令行工具、真机调试、本地验收与 JVM 测试层

| 文件路径 | 核心职责与关键机制 |
| --- | --- |
| `cli/src/main.rs` | 独立桌面/终端 CLI `miplay-cli`：提供 `discover` / `inspect`（只读不发 CLOSE）/ `probe` / `safety` / `mute` / `cast` 及故障注入调试子命令 |
| `android/app/src/main/java/com/miplay/sender/DevMain.kt` | 免安装 APK 的 `adb shell app_process` 真机直连调试入口（验证静音、编码、晚加入扩组与断网重连） |
| `android/app/src/main/java/com/miplay/sender/LocalFilterProbeService.kt` | 仅 `acceptance` 变体启用：纯本地 UID 捕获 + `PcmProbe` 量化验收服务，严禁触碰任何局域网音箱 |
| `android/testtones/src/main/java/com/miplay/testtone/ToneActivity.kt` | 受控测试音源控制界面（独立构建为 `A=1kHz`、`B=2kHz`、`deny=allowAudioPlaybackCapture=false` 三枚不同 UID APK） |
| `android/testtones/src/main/java/com/miplay/testtone/ToneService.kt` | 前台服务持续输出固定频率立体声正弦波，用于量化验证单 App 音频隔离与泄漏抑制 |

#### JVM 单元与契约测试完整清单（`android/app/src/test/java/com/miplay/sender/`，共 19 个测试文件）

| 测试文件 | 覆盖契约与回归点 |
| --- | --- |
| `AcceptanceVariantContractTest.kt` | 验证 `acceptance` 变体独立包名、音量默认 0% 与本地探针隔离边界 |
| `AuthModeResolveTest.kt` | 验证 UI 与 Intent 认证模式字符串到 `0/1/2` 的映射 |
| `CastServiceMuteInitTest.kt` | 回归验证 `LocalOutputMute` 延迟初始化，杜绝 Service 构造期 `getSystemService` NPE |
| `LatencyDisplayTest.kt` | 验证核心归一化 `ui_state`/`display_latency_ms` 与旧格式回退解析、畸形值/假 0 拦截、加载转圈与隐藏态转换 |
| `MiPlayFeatureRegressionTest.kt` | 核心功能回归：构建安全、路由/静音/协议等端到端不变量 |
| `capture/CaptureTargetResolverTest.kt` | 验证卸载、跨用户、共享 UID、签名变更全部 fail-closed |
| `capture/LaunchableAppCatalogTest.kt` | 验证可启动 App 排序、推荐音乐优先与快捷芯片筛选 |
| `capture/LocalFilterAcceptanceGateTest.kt` | 验证本地过滤门禁状态转换与无音箱验收条件 |
| `capture/LocalProbeNoMiPlayContractTest.kt` | 静态契约断言 `LocalFilterProbeService` 绝不调用任何 `NativeBridge` / MiPlay 网络方法 |
| `capture/PcmProbeTest.kt` | 验证 Goertzel 1kHz/2kHz 频点检测精度与 RMS 能量计算 |
| `capture/ProjectionSessionPolicyTest.kt` | 验证单次会话重新授权、切源停旧会话与资源释放顺序 |
| `capture/TargetAppStoreTest.kt` | 验证目标应用持久化绝不存储 UID，仅存包名与签名摘要 |
| `cast/CastRouteSelectionTest.kt` | 验证本机/多箱互斥、6 列发现解析、非 MiPlay 默认过滤与缓存合并 |
| `cast/LocalOutputMuteTest.kt` | 验证双策略切换、6 拍有声锁定防切歌双出声与外部解除不反复重静音 |
| `cast/LocalOutputMuteUnverifiedTest.kt` | 验证无探针通过时绝不虚假宣称本机已静音 |
| `cast/NowPlayingInfoTest.kt` | 验证正在播放模型字段兜底与诚实占位 |
| `cast/ProtocolBadgeTest.kt` | 验证协议枚举到徽标图标/颜色/文案映射一致性 |
| `compat/Api29CompatContractTest.kt` | 验证 API 29–36 兼容封装（`BlurCompat` / `WindowInsets` / 通知权限）无高版本 API 直调 |
| `ui/ThemeModeTest.kt` | 验证 `SYSTEM` / `LIGHT` / `DARK` 三态主题解析、循环切换与持久化回退契约 |

### 3.8 工程支撑 · 构建配置、签名与自动化校验脚本层

| 文件路径 | 核心职责与关键机制 |
| --- | --- |
| `Cargo.toml` / `core/Cargo.toml` / `cli/Cargo.toml` | Rust Workspace 定义、release LTO 编译参数、`jni` 可选特性开关与 `AGPL-3.0-only` 许可声明 |
| `android/build.gradle.kts` / `settings.gradle.kts` / `gradle.properties` | AGP 8.7.3 + Kotlin 2.0.21 根工程配置、`:app` 与 `:testtones` 模块注册与 AndroidX 配置 |
| `android/app/build.gradle.kts` / `android/app/proguard-rules.pro` | `standard`/`acceptance` 变体、环境变量/`keystore.properties` 签名注入、`NewApi` fatal Lint 与 JNI/`DevMain` R8 keep 规则 |
| `scripts/build-debug.sh` | 一键串联 `cargo test` + 16KB 页对齐 `cargo-ndk(--platform 29)` 编译 `.so` + Gradle debug 构建与单元测试 |
| `scripts/build-release.sh` | 正式 release 签名构建流水线（校验签名注入、编译 release `.so` 与 APK、执行 16KB 与证书校验） |
| `scripts/build-ios-xcframework.sh` | 编译 `aarch64-apple-ios` 与模拟器静态库并组装 `MiPlayCore.xcframework` |
| `scripts/verify-acceptance-apk.sh` | 校验 APK 的 `zipalign` 4KB/16KB 页对齐、`llvm-readelf` `0x4000` LOAD 对齐与 `apksigner` 签名证书指纹 |

---

## 四、软件运行时整体架构（进程 · 线程 · 有界队列 · 网络拓扑）

![妙投（MiaoCast）软件运行时整体架构](assets/runtime-architecture.svg)

### 4.1 进程、线程与有界队列模型

| 运行区 | 线程 / 资源实体 | 队列 / 周期 | 核心职责与隔离边界 |
| --- | --- | --- | --- |
| **L3 / L2 原生层** | `Main Thread (UI)` | `1Hz refreshLoop` | 仅原位增量刷新设备行内延迟 ms 与转圈（不重建整表打断音量滑块）；驱动路由选择、主题切换与投影授权 |
| **L2 系统捕获层** | `capture` (`MAX_PRIORITY`) | `AudioRecord` 4×4096B (≈85ms) | 严格单 UID 读取 48kHz PCM；每 12 块送 `PcmProbe` 推进静音锁定；硬编 AAC 经 FFI 推入 L1 |
| **L2 服务保活层** | `CastService` 与短生命周期工作线程 | `stats(1Hz)` / `discover` | 持有 `FGS(mediaProjection\|mediaPlayback)`、`WakeLock`、`WifiLock`、`MulticastLock` 及 64KB 轮转 `cast_ops.log` |
| **FFI & L1 引擎门面** | `core::engine` (`ENGINES` + `Coordinator` + `FEED_QUEUE_AUS`) | `sync_channel(96)` (≈2s) | 映射会话句柄到 `Arc<Engine>`；在 Rust 侧串行协调 `desired` 与活跃音箱集（吸收首台建连期并发点选与延迟音量）；`stop()` **必须先 `drop(feed)`** 立即唤醒 `media-clock` 防止停止死锁 |
| **L1 Rust 共享层** | `media-clock`（1 份/会话） | `poll_au(50ms)` · `21.333ms` 节拍 | 在 `OffsetBarrier` 先冲刷积压再预读 14 AU；单线程推进共享 90kHz PTS / 27MHz PCR 并广播 `Arc<SharedAu>`；满队接收端单端剔除不堵全组 |
| **L1 Rust 共享层** | `supervisor` + `EPOCH`（1 份/会话） | 1Hz 检查 · 15s 超时 | 监控失活接收端并迁入 `pending(reconnecting)`，按 `2 → 4 → 8 → 16 → 30s` 指数退避自动重连；提供全链路统一 `CLOCK_MONOTONIC` 微秒时钟 |
| **L1 Rust Per-Receiver** | 每台音箱独立 5 线程（×N，上限 63） | `media_rx: sync_channel(256)` | `media-<ip>`（独立 Packetizer 写 Audio TCP）、`rtsp-<ip>`（WFD 协商与 `TIME_OFFSET`）、`ctl-<ip>`（8899 读循环 15s 看门狗 + ACK 匹配）、`hb-<ip>`（每 5s 心跳）、`timer-<ip>`（UDP 40B 时钟主），外加 `Aux` 保活句柄与单次加箱 `Arc<AtomicBool>` 取消令牌 |

### 4.2 每台接收端的 6 条局域网网络通路

| 编号 | 传输协议与方向 | 端口 | 用途与关键载荷 |
| --- | --- | --- | --- |
| ① | `UDP` 组播（双向） | `224.0.0.251:5353` | mDNS 设备发现（`_mi-connect._udp.local` 优先 + `_lyra-mdns._udp.local`） |
| ② | `TCP` 发送端 → 音箱 | `8899` | 正向控制信道：Legacy/Safety 认证、`preparePlayback`、`Open`、音量、暂停/双 `Resume`、心跳、`NOTIFY` |
| ③ | `TCP` 音箱 → 发送端 | 随机监听端口 (#1) | 反向 WFD RTSP 协商（M1–M5、`SETUP`、`PLAY`、共享 `TIME_OFFSET` 下发与保活） |
| ④ | `TCP` 音箱 → 发送端 | 同监听端口 (#2) | 反向 Aux 辅助连接，建连后整个会话期间保持打开 |
| ⑤ | `TCP` 音箱 → 发送端 | 同监听端口 (#3) | 反向媒体流连接：`$+24bit` 长度头封装 `RTP(PT=33)` / `MPEG-TS` / `PES` / `ADTS` 音频流 |
| ⑥ | `UDP` 音箱 ⇄ 发送端 | 随机绑定端口 | `TimerServer` 40B 小端双向时间戳采样，使全部音箱把本地时钟精确锁定至发送端 `EPOCH` |

### 4.3 运行时 6 阶段生命周期与关键不变量

1. **冷启动与 mDNS 发现**：API 29–32 零运行时权限弹窗；持 `MulticastLock` 并发查询两类 mDNS 服务，经 L1 `device_catalog` 同 IP 优先保留 `apps=[5]` 节点并与本地缓存融合。
2. **选源与投影授权**：Android 端为每次投送会话重新申请 `MediaProjection`；先进入 `mediaProjection` FGS 再调用 `getMediaProjection`，实时校验目标包 UID/同用户/非共享 UID/签名一致（fail-closed）。
3. **8899 认证与同步建连**：L1 `Engine` 并发对全部勾选音箱执行 Legacy 或 Safety 引导并发送 `Open`；音箱反连 RTSP/Aux/Audio 三条连接，在 `OffsetBarrier` 汇合时**先 `flush()` 丢弃连接期积压 AU**，再预读 **14 AU** 定出共享 `TIME_OFFSET`。
4. **稳态多箱推流与静音锁定**：L2 `capture` 以 `MAX_PRIORITY` 捕获编码喂入 96 AU 队列；L1 `media-clock` 按 `21.333ms` 统一打共享 PTS/PCR 广播给各台独立 `Packetizer`；捕获每 12 块送 `PcmProbe`，达 **6 拍有声** 后锁定本机静音（切歌静音不再误解除，外部按键取消静音不强行重静音）。
5. **动态加箱 · 暂停 · 断线重连**：运行期加箱由 L1 `Engine` 即刻叠加 `connecting` 状态驱动 UI 显示转圈，串行对齐目标音箱集（自动升级 `ref_function=multi_room`、复用当前 `TIME_OFFSET`，并由独立 `Arc<AtomicBool>` 令牌隔离“取消后立即重勾”竞态）；暂停恢复时全员重锚并连发两次间隔 29ms 的 `Resume`；15s 失联自动迁入 `pending` 退避重连并透出 `reconnecting` 状态。
6. **无死锁停止与资源清理**：恢复本机音量、停止 `AudioRecord`/`MediaCodec`/`MediaProjection`；L1 `Engine::stop` 先释放 `feed` 发送端唤醒 `media-clock`、`join` 全部工作线程、向每台音箱下发 `CLOSE(0x0002)` 释放接收端会话，最后释放 `WakeLock`/`WifiLock` 与前台通知。
