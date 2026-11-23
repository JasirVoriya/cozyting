# iOS 原生客户端设计方案（`ReplayKit` 系统广播扩展 + L1 Rust 核心）

> **状态：待实现（Proposed / Not Implemented）**
> 本文档为 **妙投（MiaoCast）** iOS 原生客户端的架构与工程实施设计方案（iOS Bundle ID 对齐 Android `com.miaocast.app`：主 App 为 `com.miaocast.app`，Broadcast Upload Extension 为 `com.miaocast.app.broadcast`）。文中涉及的现有 Rust L1 C ABI 与打包脚本均已在仓库就绪；所有 iOS 真机运行行为（DRM 兼容性、音量键静音、50MB 内存红线、锁屏保活）均须按第七节验收清单在真机实测确认后，方可写入 [COMPATIBILITY.md](COMPATIBILITY.md)。

---

## 一、背景与方案选型

本项目采用「**L1 共享 Rust 核心 + L2/L3 Android & iOS 双端原生**」架构（见 [ARCHITECTURE.md](ARCHITECTURE.md)）。Android 端基于 `MediaProjection` + `AudioPlaybackCaptureConfiguration.addMatchingUid(uid)` 实现了单 App 捕获；而在 iOS 沙箱环境下，需要对 3 条本机候选路线与 1 条局域网备选路线作出明确取舍：

| 候选路线 | 定位与结论 | 核心原因 |
| --- | --- | --- |
| **`ReplayKit` 系统广播扩展（本方案）** | ✅ **当前主力落地路线** | iOS 唯一官方合规、可在纯单机无额外硬件条件下捕获第三方 App 音频的方案；可直接复用现成 `MiaoCastCore.xcframework`，支持 App Store 合规分发。 |
| **局域网 AirPlay 2 → MiPlay 网关** | 📌 **长期保留方案（暂不启动）** | 详见 [AIRPLAY_BRIDGE_DESIGN.md](AIRPLAY_BRIDGE_DESIGN.md)。依赖局域网常驻主机，但可提供 iOS 零 App、控制中心多选与 Apple Music 投送体验。 |
| **同机模拟 AirPlay 接收端回环** | ❌ **已排除** | 受限于未越狱 iOS 沙箱禁止绑定 AirPlay 2 PTP 特权端口（UDP `319`/`320`）、系统同机自发现过滤、后台套接字挂起/`AVAudioSession` 冲突及 App Store 拒审，工程不可行。 |
| **纯 App 内置播放器（`AVAudioEngine`）** | ❌ **不作为主方案** | 无法捕获 QQ 音乐 / 网易云音乐等第三方 App，且 `MusicKit` 受 FairPlay DRM 限制无法提取 PCM；仅适合本地/自建曲库，或作为开发调试期播放测试音轨的辅助手段。 |

---

## 二、跨端分层与双进程架构

iOS 主 App 进入后台或锁屏几秒后会被系统挂起，网络连接与定时器均会中断；而 **Broadcast Upload Extension（系统广播上传扩展）** 是独立于主 App 的系统级扩展进程，在用户切换至第三方音乐 App 甚至**锁屏后仍可持续存活**。

因此，iOS 端**必须将 L1 推流核心 `MiaoCastCore.xcframework` 运行在 Broadcast Upload Extension 进程中**，主 App 仅负责前台设备发现、UI 呈现、授权拉起与低频控制指令下发：

```text
第三方音乐 App（QQ 音乐 / 网易云 / 播客 / B 站等）
   │ 系统整机非 DRM 音频混音
   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ Broadcast Upload Extension 独立进程（iOS 硬性 50MB Jetsam 内存红线）     │
│                                                                         │
│  1. RPBroadcastSampleHandler.processSampleBuffer(_:with:)               │
│     ├─ .video / .audioMic ──► 立即丢弃（零视频处理、不碰麦克风）         │
│     └─ .audioApp (PCM)    ──► AudioToolbox (AudioConverter)             │
│                               动态格式适配 → 48kHz 立体声 → 裸 AAC-LC   │
│  2. MiaoCastCore.xcframework（纯 Rust L1 公共核心，staticlib）             │
│     ├─ miaocast_engine_create / connect / push_aac / stop                 │
│     ├─ 8899 Legacy & Safety 双认证、WFD 反向 RTSP、UDP TimerServer      │
│     └─ EPOCH + OffsetBarrier 多箱毫秒同频、15s 看门狗与退避重连         │
│  3. 轻量控制服务端（ExtensionControlServer）                             │
│     └─ 接收主 App 加箱 / 删箱 / 调音量 / 暂停恢复指令，返回 stats JSON   │
└──────────────────────────────────▲──────────────────────────────────────┘
                                   │ 跨进程控制面通信（低频 JSON，不传音频）
┌──────────────────────────────────▼──────────────────────────────────────┐
│ 主 App 进程（MiaoCast · SwiftUI L3 表现层 + 发现/控制入口）           │
│                                                                         │
│  • 局域网发现：原生 NWBrowser 扫描 _mi-connect._udp / _lyra-mdns._udp    │
│  • 路由状态机：调用 miaocast_route_apply_json 复用 L1 过滤与缓存合并       │
│  • 音源槽位：嵌入 RPSystemBroadcastPickerView 唤起系统广播开关           │
│  • 1Hz 原位刷新：拉取 stats JSON 刷新设备行内延迟 ms、连接态与独立音量   │
└─────────────────────────────────────────────────────────────────────────┘
```

### 双进程职责边界
- **Extension 进程唯一持有 `Engine` 句柄**：避免跨进程传递高频 PCM/AAC 音频帧，消除 IPC 序列化开销与主 App 后台挂起断流风险。
- **主 App 进程无状态化**：主 App 随时可被系统回收重建；再次打开前台时，通过控制面拉取 Extension 当前 `stats_json`，即可无损恢复当前投送设备、实时延迟与音量状态。

---

## 三、跨进程控制面通信策略（兼容付费与免费签名）

主 App 与 Broadcast Upload Extension 仅交换低频控制命令与 1Hz 状态快照，不传输任何音频流。针对不同签名账号，通信层抽象为统一的 `ExtensionControlTransport` 协议：

| 签名环境 | 传输实现 | 特点与边界 |
| --- | --- | --- |
| **付费 Apple Developer 账号（推荐 / 上架形态）** | **`App Groups` 共享容器** + Unix Domain Socket（位于 `containerURL(forSecurityApplicationGroupIdentifier:)`）+ Darwin Notification | Apple 官方标准跨进程方案，支持上架 App Store / TestFlight；主 App 可通过共享容器可靠读写初始目标音箱列表与运行状态。 |
| **免费个人 Apple ID（Personal Team · 7 天自签）** | **移除 `App Groups` 能力**，Extension 内部监听 `127.0.0.1` 本地控制口（`NWListener` / BSD loopback socket） | 免费 Personal Team 无法签发 `App Groups` 权限，但可正常签发并部署 Broadcast Upload Extension；本地环回控制通道仅用于开发期与自签验证，需按目标 iOS 版本真机实测确认。 |

### 控制面最小协议（JSON 行协议）
- `connect`：`{ "cmd": "connect", "ips": ["192.0.2.10", "192.0.2.11"], "delay_ms": 800, "auth_mode": 0, "ref_function": "multi_room", "source_name": "MiPlay iOS" }`
- `add_receiver` / `remove_receiver`：`{ "cmd": "add_receiver", "ip": "192.0.2.11" }`
- `set_volume`：`{ "cmd": "set_volume", "ip": "192.0.2.10", "volume": 35 }`（`ip` 为空字符串表示全组音量）
- `pause` / `resume` / `stop`
- `stats`：原样透传 `miaocast_engine_stats_json(handle)` 返回的归一化 JSON（含 `ui_state`、`display_latency_ms`、`phase`、实时音量等字段）。

---

## 四、音频采集、重采样与编码全链路（L2 → FFI → L1）

```text
CMSampleBuffer (.audioApp)
   │ 1. 解析 CMAudioFormatDescription（采样率 / 声道 / Float32 or Int16 / 交错态）
   ▼
AudioConverter #1（格式归一化与重采样，仅在源格式 != 48kHz s16le 2ch 时激活）
   │ 48000Hz · 2ch · Linear PCM（1024 samples / 21.333ms 凑帧环形缓冲）
   ▼
AudioConverter #2（硬件 AAC-LC 编码：kAudioFormatMPEG4AAC · 256kbps）
   │ 裸 AAC Access Unit（不含 7 字节 ADTS 头）
   ▼
miaocast_engine_push_aac(handle, ptr, len)
   │ L1 sync_channel(96) → media-clock → ADTS/PES/MPEG-TS/RTP → 多音箱同步发送
```

### 关键实现约束
1. **严格零视频处理**：`processSampleBuffer(_:with:)` 收到 `.video` 或 `.audioMic` 时立即直接返回，禁止调用 `CMSampleBufferGetImageBuffer`、`CIImage` 或任何视频编码器，防止触碰 50MB Jetsam 内存上限。
2. **动态输入格式切换容错**：切换不同音频 App 或播放源时，`ReplayKit` 送入的 `CMAudioFormatDescription`（如 44.1kHz ↔ 48kHz、单声道 ↔ 立体声）可能在中途变化。`PcmToAacConverter` 需比对 `AudioStreamBasicDescription`，变化时平滑重建重采样器并清空残余未凑满 1024 samples 的零头缓冲。
3. **背压与可观测性**：`miaocast_engine_push_aac` 返回 `-4` 表示 L1 `FEED_QUEUE_AUS(96)`（约 2 秒）已满，应计数上报并丢弃溢出帧，严禁在 Swift 侧无限堆积缓冲导致 Extension OOM。
4. **无死锁停止顺序**：在 `broadcastFinished()` 或控制面 `stop` 中，严格按以下顺序清理：
   1. 标记停止，拒绝后续 `processSampleBuffer` 回调；
   2. 停止并释放 `AudioConverter`；
   3. 调用 `miaocast_engine_stop(handle)`（L1 内部先 `drop(feed)` 唤醒 `media-clock`，再 `join` 全部线程并向所有音箱发 `CLOSE(0x0002)`）；
   4. 关闭控制面监听并释放全部 C 字符串与句柄。

---

## 五、设备发现与 iOS 权限策略（避开组播特权陷阱）

### 1. 为什么 iOS 发现层使用 `NWBrowser` 而不是直接调 Rust 裸 UDP 组播？
- Rust L1 的 [core/src/discovery.rs](../core/src/discovery.rs) 通过裸 UDP 组播（`224.0.0.251:5353`）实现 mDNS 发现。
- 在 **iOS 14+ 真机**上，App 直接发送/接收裸 UDP 组播需要向 Apple 申请受限权限 **`com.apple.developer.networking.multicast`**（免费 Personal Team 无法申请，付费账号也需人工审批）。
- **iOS 推荐策略**：
  - 在 `Info.plist` 中声明：
    - `NSLocalNetworkUsageDescription`：明确说明仅用于发现并向本地局域网小米音箱投送音频；
    - `NSBonjourServices`：`_mi-connect._udp`、`_lyra-mdns._udp`。
  - 在主 App 中使用苹果原生 **`NWBrowser`** 并发浏览这两个服务类型，解析 `NWEndpoint` 获取 IPv4 地址与 `NWTXTRecord`，整理为 L1 兼容设备列表 JSON 后，调用 **`miaocast_route_apply_json(devices_json, "merge_discovered", ...)`**，100% 复用 L1 的默认过滤规则（`apps=[5]` 同 IP 优先、TV 启发式、历史缓存合并上限 16 台）。

### 2. 推流与控制通路权限
- MiPlay 的正向 TCP `8899`、反向 WFD RTSP/Aux/Audio TCP、以及 UDP `TimerServer` 均属于局域网单播通信，**无需 `multicast` 特殊权限**；
- 主 App 与 Extension 两个 Target 的 `Info.plist` 均须配置 `NSLocalNetworkUsageDescription`，且首次发起发现或连接前须引导用户允许「本地网络」访问。

---

## 六、仓库已就绪资产与 `ios/` 目标工程规划

### 已就绪（直接复用，无需重写）
- **L1 C ABI 导出与头文件**：[core/src/ffi_c.rs](../core/src/ffi_c.rs)、[core/include/miaocast_core.h](../core/include/miaocast_core.h)（已完整导出 18 个 `miaocast_*` 函数及 `miaocast_string_free`）与 [core/include/module.modulemap](../core/include/module.modulemap)（Swift 中直接 `import MiaoCastCore`）。
- **XCFramework 打包流水线**：[scripts/build-ios-xcframework.sh](../scripts/build-ios-xcframework.sh)（自动运行 `cargo test --workspace`，编译 `aarch64-apple-ios` 真机与 `aarch64-apple-ios-sim` 模拟器静态库并输出 `ios/Frameworks/MiaoCastCore.xcframework`）。
- **跨端视觉规范与图标**：[design/UI_SPEC.md](../design/UI_SPEC.md)、[design/tokens.json](../design/tokens.json) 与 `design/icons/*.svg`（20 枚统一矢量图标）。

### 待新建的 `ios/` 目录结构规划

```text
ios/
├── Frameworks/
│   └── MiaoCastCore.xcframework            # 由 scripts/build-ios-xcframework.sh 生成（.gitignore 忽略或按需构建）
├── MiaoCast.xcodeproj/               # Xcode 工程（建议配合 xcodegen project.yml 维护）
├── Shared/                               # 主 App 与 Extension 共用轻量代码（零重型依赖）
│   ├── MiaoCastCoreBridge.swift            # 对 core/include/miaocast_core.h 的类型安全封装与 miaocast_string_free RAII 管理
│   ├── ExtensionControlProtocol.swift    # 控制面 JSON 消息定义与编解码
│   └── SpeakerModels.swift               # 与 L1 device_catalog / latency 对齐的数据模型
├── MiaoCast/                         # [Target 1] 主 App（SwiftUI · L3 + 发现 + 控制入口）
│   ├── App/MiaoCastApp.swift
│   ├── Discovery/BonjourSpeakerBrowser.swift   # NWBrowser（_mi-connect._udp + _lyra-mdns._udp）
│   ├── IPC/ExtensionControlClient.swift        # 1Hz stats 轮询 + 加减箱/调音量指令下发
│   ├── UI/
│   │   ├── Theme/UiPalette.swift               # 严格映射 design/tokens.json 明暗双主题 + .ultraThinMaterial
│   │   ├── CastMainView.swift                  # 四段式主界面（顶栏 / NowPlaying 降级卡 / ReplayKit 槽位 / 设备列表）
│   │   ├── BroadcastPickerButton.swift         # 封装 RPSystemBroadcastPickerView
│   │   └── SettingsView.swift                  # 3 张分组卡片（外观与发现 / 权限与静音说明 / 连接与调试）
│   └── Resources/Assets.xcassets               # 导入 design/icons/*.svg（Template Image）与扁平同心音箱 AppIcon
├── MiPlayBroadcastExtension/             # [Target 2] Broadcast Upload Extension（L2 音频采集编码 + L1 推流）
│   ├── SampleHandler.swift                     # RPBroadcastSampleHandler 生命周期与 50MB 内存守卫
│   ├── Audio/PcmToAacConverter.swift           # AudioToolbox 动态重采样 + 48kHz 256kbps 裸 AAC 编码器
│   └── IPC/ExtensionControlServer.swift        # App Group Unix Socket / 127.0.0.1 控制服务端
└── MiaoCastTests/                    # [Target 3] XCTest 单测
    ├── PcmToAacConverterTests.swift            # 44.1kHz/48kHz/Float32/Int16 Mock CMSampleBuffer 编码契约测试
    ├── RouteAndLatencyContractTests.swift      # 验证与 MiaoCastCore C ABI 的路由/延迟归一化一致
    └── ExtensionControlProtocolTests.swift     # 控制面消息序列化与边界单测
```

---

## 七、与 Android 端的差异与诚实工程边界

| 能力项 | Android 端（已实现） | iOS 端（本方案） | 产品与文档诚实边界 |
| --- | --- | --- | --- |
| **音频捕获范围** | 严格单 App（`addMatchingUid` Fail-Closed） | **整机非 DRM 音频混音**（`.audioApp` 无 UID 过滤） | 投歌期间刷短视频、微信语音、通知提示音会混入音箱；UI 明确标为「系统音频广播」，不伪造单 App 隔离 |
| **DRM 音源** | 仅拒捕 `allowAudioPlaybackCapture="false"` 的 App | **Apple Music 等 FairPlay DRM 内容会被系统静音** | QQ 音乐 / 网易云 / 播客 / B 站等按真机实测结果逐项登记进 [COMPATIBILITY.md](COMPATIBILITY.md) |
| **本机静音** | 代码自动静音 `STREAM_MUSIC` + `PcmProbe` 6 拍锁定 | **无公开 API 自动静音；用户手动将音量键调至 0** | `ReplayKit` 采样位于系统音量衰减之前，预期手机无声但音箱照常发声（须真机复核） |
| **开播授权与指示** | 每次会话弹 `MediaProjection` 授权 + 状态栏图标 | 每次开播点 `RPSystemBroadcastPickerView` 确认 + 红条/灵动岛红点 | 均属操作系统强制隐私提示 |
| **正在播放与跳转** | 通知监听读任意第三方封面与播控，点封面按包名拉起 | 沙箱不可读取任意第三方会话/图标，按 [UI_SPEC.md](../design/UI_SPEC.md) 降级展示广播状态提示与有限 URL Scheme 跳转 | 不申请越权权限，未知音源静默降级不报错 |
| **应用更新检测** | `HttpsURLConnection` 拉取公开 `update.json` + L1 `miaocast_update_evaluate_json` 裁决 + 强制 SHA-256 与签名校验后 `FileProvider` 调起安装 | 复用同一份 `update.json` 与 L1 C ABI `miaocast_update_evaluate_json`（经 `URLSession` 只读 HTTPS + ETag） | App Store 分发版走商店更新（编译宏禁用自更新弹窗遵循审核规范）；自签 / IPA 分发版以 `.sheet` 展示跨版本区间日志并跳转 Safari 打开 `miaocast-releases` 发布页 |
| **运行内存红线** | 主进程内存宽松 | **Extension 进程 50MB Jetsam 强杀上限** | 丢弃 `.video` 帧、严控零拷贝与有界队列，真机 Instruments 必测 |

---

## 八、分阶段落地与真机验收清单

### 阶段 1：最小真机探针（优先排雷，不写复杂 UI）
1. 在 macOS 上安装 Rust targets（`aarch64-apple-ios`、`aarch64-apple-ios-sim`）并执行 `./scripts/build-ios-xcframework.sh` 验证产物生成。
2. 建立最小 Xcode 双 Target 工程（主 App + Broadcast Upload Extension），部署至 iOS/iPadOS 真机完成 **5 项硬核实测**：
   - **发现验证**：`NWBrowser` 在真机上正常扫出局域网小米音箱，无组播权限报错；
   - **音源与 DRM 摸底**：分别测试主流第三方音乐/播客 App 与 Apple Music，记录哪些 `.audioApp` 有稳定非零 PCM、哪些被系统静音；
   - **本机静音实测**：将设备物理音量键拉到 `0`，确认本机扬声器无声且音箱持续满幅播放；
   - **长稳与 50MB 内存验收**：锁屏连续投送 ≥15–30 分钟，用 Xcode Instruments Allocations 确认 Extension 峰值内存稳定低于 35MB（留足 50MB 安全余量）；
   - **多箱并发与控制面验收**：运行期通过控制面动态加减音箱、独立调音量、停止后音箱收到 `CLOSE(0x0002)` 正常释放。

### 阶段 2：完整跨端对齐客户端
1. 严格按 [design/UI_SPEC.md](../design/UI_SPEC.md) 与 [design/tokens.json](../design/tokens.json) 实现四段式 SwiftUI 主界面、明暗双主题与设置页。
2. 补齐 `XCTest` 自动化测试与 CI 语法/构建检查。
3. 将真机实测通过与不通过的边界如实同步至 [COMPATIBILITY.md](COMPATIBILITY.md) 与 [LIMITATIONS.md](LIMITATIONS.md)。
