<p>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/logo-banner-dark.svg">
    <source media="(prefers-color-scheme: light)" srcset="docs/assets/logo-banner-light.svg">
    <img alt="MiPlay Sender" src="docs/assets/logo-banner-light.svg" width="560">
  </picture>
</p>

# MiPlay 发送端（纯局域网）

Android 手机作为发送端，经局域网把音频投送到局域网智能音箱（支持 MiPlay 协议）。

## 许可与免责声明

- **开源许可证**：本项目采用 **[GNU Affero General Public License v3.0 (AGPL-3.0-only)](LICENSE)** 开源发布。
- **非官方与互操作性声明**：本项目为独立的第三方开源客户端实现，仅用于局域网设备互操作性与音频投送技术研究。本项目与小米集团（Xiaomi）及其关联公司没有任何隶属、授权、认可或合作关系。文中所提及的产品名称、协议名称或商标均归其各自所有者所有。
- **隐私与边界**：仅捕获用户授权的声音流，不采集屏幕图像或麦克风，不连接任何云端 API，仅在用户本地局域网内按选定目标投送音频。

## 功能

- 发现/多选分组、暂停恢复、加入移除、音量、延迟、Legacy/Safety 认证、断线重连、前台通知
- **主界面**：对齐小米「音乐投送」面板（本机 / 多选音箱互斥；MediaSession 正在播放；延迟一行小数）
- **本机静音**：投送时尝试 `STREAM_MUSIC` mute + 捕获能量探针回退，见 `docs/LOCAL_MUTE.md`（无探针通过不得宣称已静音）
- **延迟诊断**：投送中按接收端显示估计 E2E ms；定义见 `docs/LATENCY_BUDGET.md`（估计值≠协议 RTT；缺失显示 —）
- 晚加入扩组 / Safety / Open-poll 等假设见 `docs/LX06_V8_POSTMORTEM.md`（**不宣称 LX06 已修好**）
- 状态调查用 CLI：`miplay-cli inspect <IP> --auth legacy`（仅 GET_* + TCP shutdown；**旧 probe 会发协议 CLOSE，不是无副作用只读**）
- 音源：**测试静音** 或 **选定 App 音频**（`AudioPlaybackCapture` + 严格单 UID + usage 过滤）
- minSdk **29**（Android 10+）～ target/compileSdk **36**；arm64-v8a
- 毛玻璃在 API31+ 用 RenderEffect，API29–30 降级压暗（见 `BlurCompat`）
- 不申请 `QUERY_ALL_PACKAGES`；API30+ 可启动列表依赖 `<queries>` MAIN/LAUNCHER；API29 无包可见性限制
- 不采集麦克风；`RECORD_AUDIO` 仅用于播放捕获；FGS 类型为 `mediaProjection` / `mediaPlayback`
- MediaProjection **每次会话重新授权**；切换目标会停止旧会话

## 架构总览

本项目采用 **Android（Kotlin，API 29–36）⇄ JNI（`libmiplay_sender_core.so`）⇄ Rust 核心协议与同步引擎（`miplay-sender-core`）** 的双层单进程架构。Android 侧专注权限最小化、严格单 UID 音频捕获、硬件 AAC 编码、本机静音探针与路由编排；Rust 侧专注局域网 mDNS 发现、TCP 8899 控制/认证（Legacy 与 Safety）、WFD RTSP 协商、UDP 单调时钟对齐、MPEG-TS/RTP 媒体封装与最多 63 台接收端的多箱同步调度。

> **交互式架构浏览器**：如需在浏览器中点击任意模块追踪其全部 **↑ 上游来源 / ↓ 下游消费者**、按 6 个阶段演练运行时线程切换，或按关键词检索全仓文件职责，可直接打开零依赖本地页面 [`docs/architecture.html`](docs/architecture.html)。

### 1. 整体架构与数据流向

![MiPlay Sender 整体架构与数据流向](docs/assets/architecture-overview.svg)

全系统由 **4 条职责正交的数据流** 串联各层上下游：

| 数据流 | 颜色 / 标识 | 端到端上下游路径 | 协议与关键特征 |
| --- | --- | --- | --- |
| **音频媒体主链路** | 青色实线 | 目标音频 App → `PlaybackCaptureFactory` (`AudioRecord` 48kHz s16le) → `AacEncoder` (`MediaCodec` 256kbps 裸 AU) → `NativeBridge.pushAac` / `sync_channel(96)` → `session::media-clock` (`SharedTimeline` PTS/PCR) → Per-Receiver `media/*` (`Packetizer`) → 音箱反向 Audio TCP | 48kHz 立体声，21.333ms/AU；`7B ADTS → PES(0xC0) → 188B MPEG-TS → RTP(PT=33) → $+24bit` 线帧 |
| **控制与认证流** | 琥珀虚线 | `MainActivity` / `miplay-cli` / `DevMain` → `CastService` (`routeLock` 串行协调) → `JNI` → `control` + `auth/*` (`Legacy` / `Safety`) → 音箱正向 TCP `8899` | 支持旧式明文（MD5+HMAC-SHA1）与现代 Safety（OPack + 双向 HMAC + 双向链式 AES-128-CBC）；5s 心跳 / 15s 看门狗 |
| **时钟与组同步流** | 翡翠点虚线 | `EPOCH` (`CLOCK_MONOTONIC`) + `TimerServer` (UDP 40B) + `SourceRtsp` (反向 WFD TCP) ⇄ 局域网音箱集群 → 汇合 `OffsetBarrier` | 建连期先 `flush()` 积压再预读 14 AU 定共享 `TIME_OFFSET`；Timer / `TIME_OFFSET` / PCR 严格同源 |
| **发现与遥测反馈流** | 紫色短虚线 | mDNS (`224.0.0.251:5353`) + 8899 `NOTIFY` + `PcmProbe` + `NowPlayingTracker` → `CastService` (1Hz 轮询) → `MainActivity` 原位刷新 | 合并 `_mi-connect` (`apps=[5]` 优先) 与 `_lyra-mdns`；按设备原位更新延迟 ms、静音锁定态与播放信息 |

### 2. 项目分层架构与文件职责（L1–L8）

#### L1 · 表现与系统交互层（UI、清单与资源）

| 文件路径 | 核心职责与关键机制 |
| --- | --- |
| `android/app/src/main/java/com/miplay/sender/MainActivity.kt` | 主界面面板：本机 / 多选音箱互斥交互、NowPlaying 展示、1Hz 原位刷新设备行内延迟与转圈、音量控制、认证模式切换与 MediaProjection 授权发起 |
| `android/app/src/main/java/com/miplay/sender/AppPickerActivity.kt` | 音源 App 选择器：按最近使用与推荐音乐分组展示可启动 App、支持搜索过滤，并校验保存目标包名与签名摘要 |
| `android/app/src/main/java/com/miplay/sender/NowPlayingNotificationListener.kt` | 声明 `NotificationListenerService` 以合法获取 `MediaSessionManager` 活跃会话（仅用于读取正在播放信息与播控，不读取上传通知正文） |
| `android/app/src/main/AndroidManifest.xml` | 主应用清单：权限最小化（不申请 `QUERY_ALL_PACKAGES`）、`<queries>` 声明 `MAIN/LAUNCHER`、FGS 类型 `mediaProjection\|mediaPlayback` |
| `android/app/src/acceptance/AndroidManifest.xml` | `acceptance` 变体叠加清单：仅在独立验收包中注册 `LocalFilterProbeService` |
| `android/app/src/main/res/values/strings.xml` | 正式包应用名称资源（`acceptance` 变体另有独立 `android/app/src/acceptance/res/values/strings.xml`） |
| `android/app/src/main/res/drawable/ic_protocol_*.xml` | 4 枚投送协议矢量徽标（`miplay` / `dlna` / `airplay` / `gcast`） |
| `android/app/src/main/res/mipmap-anydpi-v{26,33}/ic_launcher*.xml` | 原创扁平同心音箱 Adaptive Icon（含浅/深色 `drawable(-night)` 资源、Android 13+ 单色主题图标与 `ic_stat_miplay.xml` 通知小图标） |

#### L2 · 前台服务、路由、静音与媒体状态层（`CastService` 与 `cast/`）

| 文件路径 | 核心职责与关键机制 |
| --- | --- |
| `android/app/src/main/java/com/miplay/sender/CastService.kt` | 核心前台服务：管理 FGS 与 `WakeLock`/`WifiLock`、运行单 UID 捕获编码循环、以 `routeLock` 串行协调器对齐目标与活跃音箱、1Hz 统计轮询及 64KB 轮转 `cast_ops.log` |
| `android/app/src/main/java/com/miplay/sender/AacEncoder.kt` | 封装 Android `MediaCodec` 硬件 AAC-LC 编码（48kHz 立体声 256kbps，产出不含 ADTS 头的裸 AU） |
| `android/app/src/main/java/com/miplay/sender/LatencyDisplay.kt` | 解析 Rust `statsJson` 并驱动每台设备行内指示器状态机（`Value` 整数 ms / `Loading` 转圈 / `Hidden`），拒绝 NaN/负值/畸形值与假 0 |
| `android/app/src/main/java/com/miplay/sender/AuthModeResolve.kt` | 将 UI 选项与调试 Intent 映射为 JNI 认证模式整数（`0=auto` / `1=legacy` / `2=safety`） |
| `android/app/src/main/java/com/miplay/sender/cast/CastRouteSelection.kt` | 纯函数路由状态机：处理 `This device` 与多选音箱/电视互斥、6 列 mDNS TSV 解析、非 MiPlay 节点默认过滤及缓存合并 |
| `android/app/src/main/java/com/miplay/sender/cast/CastProtocol.kt` | 协议枚举定义与 `castImplemented` 门禁，阻止未实现协议（DLNA/AirPlay/Cast）假装投送成功 |
| `android/app/src/main/java/com/miplay/sender/cast/ProtocolBadge.kt` | 将协议枚举映射为矢量图标、配色与无障碍标签 |
| `android/app/src/main/java/com/miplay/sender/cast/LocalOutputMute.kt` | 本机 `STREAM_MUSIC` 静音与探针状态机：支持 `GLOBAL_STREAM_MUTE` / `KEEP_LOCAL_OUTPUT` 双策略、6 拍有声验证锁定（修复切歌短暂双出声）与外部解除检测 |
| `android/app/src/main/java/com/miplay/sender/cast/AndroidStreamMusicVolume.kt` | `AudioManager` 读写与 mute 适配层（要求通过已附加 Context 延迟构造，防止 Service 构造期 NPE） |
| `android/app/src/main/java/com/miplay/sender/cast/NowPlayingTracker.kt` | 订阅选中源 App 的 `MediaController` 回调，提取曲目/艺人/专辑图/播放进度并转发播控命令 |
| `android/app/src/main/java/com/miplay/sender/cast/NowPlayingInfo.kt` | 正在播放不可变数据模型与诚实占位格式化（无元数据时不伪造曲目） |
| `android/app/src/main/java/com/miplay/sender/cast/PrefsDiscoveredDeviceStore.kt` | `SharedPreferences` 持久化最近发现的合法 MiPlay 设备（最多 16 台），防止单次 mDNS 丢包引起列表闪烁 |

#### L3 · 严格单 App 捕获与数字域探针层（`capture/`）

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

#### L4 · Android API 29–36 兼容与变体隔离层（`compat/`）

| 文件路径 | 核心职责与关键机制 |
| --- | --- |
| `android/app/src/main/java/com/miplay/sender/compat/BlurCompat.kt` | 背景毛玻璃兼容调度：API 31+ 走 `RenderEffect`，API 29–30 自动降级为压暗叠色 |
| `android/app/src/main/java/com/miplay/sender/compat/BlurEffectApi31.kt` | 隔离 API 31 `RenderEffect` 类引用，避免 Android 10 ART 类校验异常 |
| `android/app/src/main/java/com/miplay/sender/compat/WindowInsetsApplyCompat.kt` | 统一基于 AndroidX `WindowInsetsCompat` 适配状态栏/导航栏/刘海，避免直接依赖 API 30+ 系统 API |
| `android/app/src/main/java/com/miplay/sender/compat/NotificationPermissionCompat.kt` | 隔离 API 33+ `POST_NOTIFICATIONS` 运行时请求，确保 API 29–32 冷启动零权限弹窗 |
| `android/app/src/main/java/com/miplay/sender/compat/PackageSigningCompat.kt` | 使用 API 28+ `GET_SIGNING_CERTIFICATES` 兼容读取 APK 签名证书链 |
| `android/app/src/main/java/com/miplay/sender/compat/CaptureSelectionLayers.kt` | 统一“App 选择器 UID 权威 vs 系统 MediaProjection 授权框”两层边界文案与日志规范 |
| `android/app/src/main/java/com/miplay/sender/compat/AcceptanceAudioDefaults.kt` | 区分正式包与 `acceptance` 变体的默认音箱初始音量（30% vs 0%）与停止后本机音量恢复策略 |

#### L5 · JNI 跨语言桥接层（Kotlin ⇄ Rust）

| 文件路径 | 核心职责与关键机制 |
| --- | --- |
| `android/app/src/main/java/com/miplay/sender/NativeBridge.kt` | Kotlin 侧 `external` 接口声明与 `libmiplay_sender_core.so` 加载器（支持 `-Dmiplay.lib` 供 `app_process` 直接调试） |
| `core/src/ffi.rs` | Rust `jni` 导出实现：全局 `ENGINES` 句柄表、`FEED_QUEUE_AUS = 96`（≈2s）有界 AAC 喂入队列、会话增删/暂停/音量/统计查询及先 `drop(feed)` 防死锁停止 |

#### L6 · Rust 协议、媒体打包与多音箱同步核心层（`core/src/`）

| 文件路径 | 核心职责与关键机制 |
| --- | --- |
| `core/src/lib.rs` | `miplay-sender-core` 模块总入口与统一 `Error` / `Result` 错误类型 |
| `core/src/discovery.rs` | mDNS 组播（`224.0.0.251:5353`）PTR 构造与响应解析、`_mi-connect` (`apps=[5]` 优先) / `_lyra-mdns` 同 IP 合并与 6 列 TSV 输出 |
| `core/src/netinfo.rs` | Wi-Fi 频段探测与播放缓冲延迟策略（`≥4900MHz` 5GHz → 800ms，否则 1000ms） |
| `core/src/clock.rs` | 进程内统一单调微秒时钟 `EPOCH`（`CLOCK_MONOTONIC`），被 TimerServer / `TIME_OFFSET` / PCR 共同引用 |
| `core/src/clock_sync.rs` | UDP `TimerServer` 时钟主：响应 40B 小端时间戳报文，供接收端锁定手机单调时钟偏移与频偏 |
| `core/src/commands.rs` | TCP 8899 控制命令号全集（`0x00xx` 基础控制/媒体命令与 `0x14xx` Safety 安全命名空间） |
| `core/src/frame.rs` | `$` 控制帧（9B 头）编解码、5B 标量、4B 音量、`NOTIFY` 与 `GetDeviceInfo` TLV 解析 |
| `core/src/auth/mod.rs` | 认证链路抽象枚举（`LegacyClear` vs `Safety`），两条认证链路严格隔离不可混用 |
| `core/src/auth/legacy.rs` | 旧式明文认证：响应 `0x0028` 数字挑战，使用 `MD5("0.0.0.0")` 与 HMAC-SHA1 生成 `0x0029` 应答 |
| `core/src/auth/safety.rs` | 现代 `SafetyAuth` / `SafetyData`：端点密钥派生、OPack 包装、双向 HMAC 互验与双向链式 AES-128-CBC 加解密 |
| `core/src/control.rs` | `ControlSession` 与 `RuntimeWriter`：8899 引导、Safety 握手、`preparePlayback`、`Open`、5s 心跳、音量 ACK 匹配、暂停与间隔 29ms 双 `Resume` |
| `core/src/rtsp.rs` | WFD 反向 RTSP 服务端（`SourceRtsp`）：处理音箱反连后的 M1–M5 能力协商、`SETUP`/`PLAY`、`TIME_OFFSET` 下发与运行期保活 |
| `core/src/timeline.rs` | `SharedTimeline`（90kHz PTS / 27MHz PCR 前向单调修正与暂停重锚）与 `SharedTimeOffset` 共享时间戳管理 |
| `core/src/latency.rs` | 发送端端到端延迟估计公式（`capture_to_play_est_ms` / `schedule_ahead_ms`）与有效域边界定义 |
| `core/src/media/mod.rs` | 每台接收端独立 `Packetizer` 调度器：维护各自 RTP 序号、MPEG-TS 连续计数及 PAT/PMT/PCR 周期插入 |
| `core/src/media/adts.rs` | 7 字节 MPEG-2/4 AAC-LC 48kHz 立体声 ADTS 帧头封装 |
| `core/src/media/mpegts.rs` | ISO/IEC 13818-1 单节目 MPEG-TS 复用器（PAT / PMT / PCR / PES `0xC0` / 188B 分片与标准 MPEG CRC32） |
| `core/src/media/rtp.rs` | RTP v2（`PT=33` MP2T，`SSRC=0xDEADBEEF`，每包 ≤7×188B）与 `$+24bit` 长度 TCP 线帧封装 |
| `core/src/session.rs` | `CastSession` 多箱运行引擎：并发建连、`OffsetBarrier` 冲刷积压 + 预读 14 AU、`media-clock` 广播、`supervisor` 15s 看门狗与退避重连、动态增删与 `connecting`/`pending` 状态合并 |
| `core/src/group.rs` | 支持左右声道分路由（`ChannelRoute`）与 47 AU 历史缓冲补发的 `Broadcaster` / `GroupSession` 抽象 |
| `core/src/inspect.rs` | 显式只查询诊断会话：强制命令白名单、过滤敏感字段、退出仅 `TCP shutdown` 绝不发送 `CLOSE(0x0002)` |

#### L7 · 命令行工具、真机调试、本地验收与 JVM 测试层

| 文件路径 | 核心职责与关键机制 |
| --- | --- |
| `cli/src/main.rs` | 独立桌面/终端 CLI `miplay-cli`：提供 `discover` / `inspect`（只读不发 CLOSE）/ `probe` / `safety` / `mute` / `cast` 及故障注入调试子命令 |
| `android/app/src/main/java/com/miplay/sender/DevMain.kt` | 免安装 APK 的 `adb shell app_process` 真机直连调试入口（验证静音、编码、晚加入扩组与断网重连） |
| `android/app/src/main/java/com/miplay/sender/LocalFilterProbeService.kt` | 仅 `acceptance` 变体启用：纯本地 UID 捕获 + `PcmProbe` 量化验收服务，严禁触碰任何局域网音箱 |
| `android/testtones/src/main/java/com/miplay/testtone/ToneActivity.kt` | 受控测试音源控制界面（独立构建为 `A=1kHz`、`B=2kHz`、`deny=allowAudioPlaybackCapture=false` 三枚不同 UID APK） |
| `android/testtones/src/main/java/com/miplay/testtone/ToneService.kt` | 前台服务持续输出固定频率立体声正弦波，用于量化验证单 App 音频隔离与泄漏抑制 |

<details>
<summary>展开查看 JVM 单元与契约测试完整清单（<code>android/app/src/test/java/com/miplay/sender/</code>，共 18 个测试文件）</summary>

| 测试文件 | 覆盖契约与回归点 |
| --- | --- |
| `AcceptanceVariantContractTest.kt` | 验证 `acceptance` 变体独立包名、音量默认 0% 与本地探针隔离边界 |
| `AuthModeResolveTest.kt` | 验证 UI 与 Intent 认证模式字符串到 `0/1/2` 的映射 |
| `CastServiceMuteInitTest.kt` | 回归验证 `LocalOutputMute` 延迟初始化，杜绝 Service 构造期 `getSystemService` NPE |
| `LatencyDisplayTest.kt` | 验证延迟 JSON 解析、畸形值/假 0 拦截、加载转圈与隐藏态转换 |
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

</details>

#### L8 · 构建配置、签名与自动化校验脚本层

| 文件路径 | 核心职责与关键机制 |
| --- | --- |
| `Cargo.toml` / `core/Cargo.toml` / `cli/Cargo.toml` | Rust Workspace 定义、release LTO/strip 编译参数、`jni` 可选特性开关与 `AGPL-3.0-only` 许可声明 |
| `android/build.gradle.kts` / `settings.gradle.kts` / `gradle.properties` | AGP 8.7.3 + Kotlin 2.0.21 根工程配置、`:app` 与 `:testtones` 模块注册与 AndroidX 配置 |
| `android/app/build.gradle.kts` / `android/app/proguard-rules.pro` | `standard`/`acceptance` 变体、环境变量/`keystore.properties` 签名注入、`NewApi` fatal Lint 与 JNI/`DevMain` R8 keep 规则 |
| `scripts/build-debug.sh` | 一键串联 `cargo test` + 16KB 页对齐 `cargo-ndk(-p 29)` 编译 `.so` + Gradle debug 构建与单元测试 |
| `scripts/build-release.sh` | 正式 release 签名构建流水线（校验签名注入、编译 release `.so` 与 APK、输出 SHA-256 校验和） |
| `scripts/verify-acceptance-apk.sh` | 校验验收 APK 的 `zipalign` 4KB/16KB 页对齐、`readelf` 符号/对齐、`apksigner` 签名与清单隔离边界 |

### 3. 软件运行时整体架构

![MiPlay Sender 软件运行时整体架构](docs/assets/runtime-architecture.svg)

#### 进程、线程与有界队列模型

整个发送端运行在**单一 Android 进程（`com.miplay.sender`）**内，ART/Kotlin 托管层与 Rust 原生库共享同一地址空间，通过有界通道解耦实时音频采集与网络 I/O：

| 运行区 | 线程 / 资源实体 | 队列 / 周期 | 核心职责与隔离边界 |
| --- | --- | --- | --- |
| **ART / Kotlin** | `Main Thread (UI)` | `1Hz refreshLoop` | 仅原位刷新设备行内延迟 ms 与转圈（不重建控件打断音量条）；驱动路由状态机与投影授权 |
| **ART / Kotlin** | `capture` (`MAX_PRIORITY`) | `AudioRecord` 4×4096B (≈85ms) | 严格单 UID 读取 48kHz PCM；每 12 块送 `PcmProbe` 推进静音锁定；硬编 AAC 推入 JNI |
| **ART / Kotlin** | `cast-start` / `reconcile-receivers` | `routeLock` 串行协调 | 串行对齐 `desiredReceivers` 与活跃音箱集；吸收首台建连期并发点选与延迟音量请求 |
| **ART / Kotlin** | 短生命周期后台线程与系统资源 | `stats(1Hz)` / `discover` / `vol` | 持有 `FGS(mediaProjection\|mediaPlayback)`、`WakeLock`、`WifiLock`、`MulticastLock` 及 64KB 轮转 `cast_ops.log` |
| **JNI 边界** | `ENGINES` 注册表 + `FEED_QUEUE_AUS` | `sync_channel(96)` (≈2s) | 映射会话句柄到 `Arc<Engine>`；`stop()` **必须先 `drop(feed)`** 立即唤醒 `media-clock` 防止停止死锁 |
| **Rust 共享层** | `media-clock`（1 份/会话） | `poll_au(50ms)` · `21.333ms` 节拍 | 在 `OffsetBarrier` 先冲刷积压再预读 14 AU；单线程推进共享 90kHz PTS / 27MHz PCR 并广播 `Arc<SharedAu>`；满队接收端单端剔除不堵全组 |
| **Rust 共享层** | `supervisor` + `EPOCH`（1 份/会话） | 1Hz 检查 · 15s 超时 | 监控失活接收端并迁入 `pending(reconnecting)`，按 `2 → 4 → 8 → 16 → 30s` 指数退避自动重连；提供全链路统一 `CLOCK_MONOTONIC` 微秒时钟 |
| **Rust Per-Receiver** | 每台音箱独立 5 线程（×N，上限 63） | `media_rx: sync_channel(256)` | `media-<ip>`（独立 Packetizer 写 Audio TCP）、`rtsp-<ip>`（WFD 协商与 `TIME_OFFSET`）、`ctl-<ip>`（8899 读循环 15s 看门狗 + ACK 匹配）、`hb-<ip>`（每 5s 心跳）、`timer-<ip>`（UDP 40B 时钟主），外加 `Aux` 保活句柄与单次加箱 `Arc<AtomicBool>` 取消令牌 |

#### 每台接收端的 6 条局域网网络通路

| 编号 | 传输协议与方向 | 端口 | 用途与关键载荷 |
| --- | --- | --- | --- |
| ① | `UDP` 组播（双向） | `224.0.0.251:5353` | mDNS 设备发现（`_mi-connect._udp.local` 优先 + `_lyra-mdns._udp.local`） |
| ② | `TCP` 手机 → 音箱 | `8899` | 正向控制信道：Legacy/Safety 认证、`preparePlayback`、`Open`、音量、暂停/双 `Resume`、心跳、`NOTIFY` |
| ③ | `TCP` 音箱 → 手机 | 手机随机监听端口 (#1) | 反向 WFD RTSP 协商（M1–M5、`SETUP`、`PLAY`、共享 `TIME_OFFSET` 下发与保活） |
| ④ | `TCP` 音箱 → 手机 | 同监听端口 (#2) | 反向 Aux 辅助连接，建连后整个会话期间保持打开 |
| ⑤ | `TCP` 音箱 → 手机 | 同监听端口 (#3) | 反向媒体流连接：`$+24bit` 长度头封装 `RTP(PT=33)` / `MPEG-TS` / `PES` / `ADTS` 音频流 |
| ⑥ | `UDP` 音箱 ⇄ 手机 | 手机随机绑定端口 | `TimerServer` 40B 小端双向时间戳采样，使全部音箱把本地时钟精确锁定至手机 `EPOCH` |

#### 运行时 6 阶段生命周期与关键不变量

1. **冷启动与 mDNS 发现**：API 29–32 零运行时权限弹窗；持 `MulticastLock` 并发查询两类 mDNS 服务，同 IP 优先保留 `apps=[5]` 节点并与本地缓存融合。
2. **选源与投影授权**：为每次投送会话重新申请 `MediaProjection`；先进入 `mediaProjection` FGS 再调用 `getMediaProjection`，实时校验目标包 UID/同用户/非共享 UID/签名一致（fail-closed）。
3. **8899 认证与同步建连**：并发对全部勾选音箱执行 Legacy 或 Safety 引导并发送 `Open`；音箱反连 RTSP/Aux/Audio 三条连接，在 `OffsetBarrier` 汇合时**先 `flush()` 丢弃连接期积压 AU**，再预读 **14 AU** 定出共享 `TIME_OFFSET`。
4. **稳态多箱推流与静音锁定**：`capture` 以 `MAX_PRIORITY` 捕获编码喂入 96 AU 队列；`media-clock` 按 `21.333ms` 统一打共享 PTS/PCR 广播给各台独立 `Packetizer`；捕获每 12 块送 `PcmProbe`，达 **6 拍有声** 后锁定本机静音（切歌静音不再误解除，外部按键取消静音不强行重静音）。
5. **动态加箱 · 暂停 · 断线重连**：运行期加箱立刻在 UI 显示转圈，串行调用 `addReceiver`（自动升级 `ref_function=multi_room`、复用当前 `TIME_OFFSET`，并由独立 `Arc<AtomicBool>` 令牌隔离“取消后立即重勾”竞态）；暂停恢复时全员重锚并连发两次间隔 29ms 的 `Resume`；15s 失联自动迁入 `pending` 退避重连并透出 `reconnecting` 状态。
6. **无死锁停止与资源清理**：恢复本机音量、停止 `AudioRecord`/`MediaCodec`/`MediaProjection`；Rust `NativeBridge.stop` 先释放 `feed` 发送端唤醒 `media-clock`、`join` 全部工作线程、向每台音箱下发 `CLOSE(0x0002)` 释放接收端会话，最后释放 `WakeLock`/`WifiLock` 与前台通知。

## 修复记录与待办（文档）

- [修复日志](docs/修复日志.md) — 已合入修复的证据索引（提交 / 文件 / 测试范围）
- [待修复需求](docs/待修复需求.md) — 暂停中的待办与验收条件（功能开发未恢复）

## 明确不承诺

- 不能保证禁止捕获的第三方 App 可捕获
- 不能声称捕获会静音原 App 本地输出
- 电池优化入口 ≠ 全品牌后台保活；HyperOS Greeze 等冻结需真机验证
- 编译通过 ≠ 全机型兼容
- 用户确认某机可投送 ≠ 双箱同步 / 按 App 静音 / 延迟实测已全部通过

## 构建

依赖：JDK 17+、Android SDK platform/build-tools 36、NDK、Rust ≥1.75、`aarch64-linux-android`、`cargo-ndk`。

```sh
export ANDROID_HOME=...
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<version>
rustup target add aarch64-linux-android
cargo install cargo-ndk --version 3.5.4 --locked   # 或兼容当前 rustc 的版本

./scripts/build-debug.sh
# 正式 debug：android/app/build/outputs/apk/standard/debug/
# 正式 release（包名 com.miplay.sender，可通过 MIPLAY_RELEASE_* 或未跟踪的 keystore.properties 注入签名）：
./scripts/build-release.sh
# 独立验收（包名 com.miplay.sender.acceptance，独立签名）：
cd android && ./gradlew :app:assembleAcceptanceDebug \
  :testtones:assembleADebug :testtones:assembleBDebug :testtones:assembleDenyDebug
```

验收步骤见 `docs/ACCEPTANCE_AB_STEPS.md`。`android/local.properties` 与 `android/app/acceptance.keystore` 勿提交到对外镜像；正式包名/用户 release 密钥不变。

## 测试

```sh
# Rust 协议/媒体回归
cargo test --workspace

# App 选择 / UID fail-closed / MediaProjection 策略（JVM）
cd android && ./gradlew --no-daemon test
```

受控真机步骤需另行协调：虚拟屏、全静音、不锁屏、不强制整机 Doze、不改用户网络。
