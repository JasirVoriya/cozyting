# macOS 原生客户端设计蓝图与开发计划（Core Audio Process Tap + L1 Rust 核心）

> **状态：已定版 · 待按阶段推进（Approved Blueprint · M1–M9）**
> 本文档是 **窝爱听（CozyTing）** 原生 macOS 客户端的产品形态、工程架构、系统边界与内部任务拆解的**单一事实来源**。
> 所有 macOS / Apple 端开发必须严格遵守本蓝图与 [`apple/AGENTS.md`](../apple/AGENTS.md)（方案 A：共享 `CozyTingShared` 底座 + 独立 `macOS` / `iOS` 壳），**严禁脱离本计划另起炉灶**。每完成一个阶段（M1–M9），须在同一 PR 中将第六节对应条目标记为 `[x]` 并记录实测边界。

---

## 一、定位、系统基线与诚实边界

### 1.1 产品定位与系统基线
- **最低系统要求**：**macOS 14.2（Sonoma）及以上**（Apple Silicon `arm64` 优先，支持 Universal `arm64 + x86_64` 编译）。
- **核心优势（相比虚拟声卡方案与移动端的差异化）**：
  - **免虚拟声卡**：基于 macOS 14.2+ 原生 **Core Audio Process Tap**（`AudioHardwareCreateProcessTap` + `CATapDescription`），不要求用户安装 BlackHole / Soundflower 或内核驱动；
  - **免录屏红框 / 免无线调试**：仅需一次系统「仅系统音频录制（`NSAudioCaptureUsageDescription`）」授权，即可直接实现**严格单 App 隔离捕获**与 **真·单 App 本机静音**（`CATapDescription.muteBehavior = .mutedWhenTapped`）；
  - **天然双区互不打扰**：投送选定音乐 App 到全屋小米 / 小爱音箱时，Mac 上刷视频、开视频会议、听微信语音照常走本机扬声器或耳机，互不串音、互不暂停。
- **首发分发形态**：**GitHub Releases 直装 DMG**（通过编译宏隔离非 App Store 能力，不因 Mac App Store 强沙箱阉割核心体验）。
- **纯局域网红线**：100% 复用 L1 Rust 核心 [`core/`](../core) 与 [`cozyting_core.h`](../core/include/cozyting_core.h)，零云端、零账号、零遥测。

### 1.2 三端能力对比与诚实边界

| 能力维度 | Android（已实现 v0.9.12） | **macOS（本蓝图 · macOS 14.2+）** | iOS（规划 · `IOS_CLIENT_DESIGN.md`） |
| --- | --- | --- | --- |
| **音频捕获隔离** | 严格单 UID（`AudioPlaybackCapture` / 无感 `AudioPolicy`） | **严格单 App 进程树**（Core Audio Process Tap，覆盖 Electron/浏览器子进程） | 整机非 DRM 混音（`ReplayKit`） |
| **本机静音** | Android 13+ 无感单 App 静音；标准模式全局媒体静音 | **原生真·单 App 静音**（`mutedWhenTapped`，一步授权，免无线调试） | 无公开 API，需手动调音量至 0 |
| **多箱同频 / 延迟 / 音量保护** | 63 台同频、4 档延迟热切、高音量二次确认 | **100% 复用 L1 核心**，体验完全一致 | 100% 复用 L1 核心 |
| **常用音源管理与热切** | 枚举本机 App、高清图标、拖动排序、不断箱热切 | **`NSWorkspace` 完整支持**（高清原图标、排序、点击唤起、不断箱热切） | 受沙箱限制不支持 |
| **自动识别正在播放音源** | `MediaSession` 播放状态上升沿 | Core Audio `kAudioProcessPropertyIsRunningOutput` 进程发声上升沿 + 白名单防抖 | 不支持 |
| **「正在播放」封面/进度** | 通知监听统一读取任意第三方 App | **分层降级**（应对 macOS 15.4+ `mediaremoted` 权限封锁）：AppleScript 适配 Apple Music/Spotify；其余 App 诚实展示音源卡片 + 系统媒体键控制 | 沙箱降级展示 |
| **快捷交互形态** | 控制中心磁贴 + 透明悬浮控制卡 + 常驻通知 | **顶部菜单栏常驻图标 + 毛玻璃悬浮控制卡** + 菜单栏滚轮调音量 + 可选主窗口 | `ControlWidget` + Deep Link `.sheet` |
| **DRM 加密内容** | 尊重 `allowAudioPlaybackCapture="false"`，不绕过 | **尊重 FairPlay / Widevine 内容保护，不绕过**，实测结果逐项登记进 `COMPATIBILITY.md` | FairPlay DRM 会被系统静音 |

### 1.3 明确「不做」清单（防止范围蔓延）
- 不编写内核扩展或第三方虚拟声卡驱动（不依赖 BlackHole）；
- 不向下兼容 macOS 13 及更早版本；
- 首发不强行适配 Mac App Store 强沙箱（仅用编译宏预留隔离点）；
- 不绕过 DRM 音频保护，也不强依赖易随 macOS 小版本失效的私有 `MediaRemote` 进程注入；
- 不引入重型第三方框架、云端账号或统计埋点。

---

## 二、产品形态与界面雏形（对齐 `design/UI_SPEC.md` Style B）

采用 **「Menu Bar 优先（90% 高频操作在悬浮卡完成） + 按需独立窗口（管理/设置/日志）」** 的原生桌面形态，严格消费 [`design/tokens.json`](../design/tokens.json) 明暗双主题色板与 `design/icons/*.svg` 线性矢量图标。

### 2.1 顶部菜单栏图标（`MenuBarExtra`）
- 图标：复用新版 F2「深夜亮灯小屋 + 垂尾小猫」单色真镂空模板图标（Template Image），待机常规单色，投送中切换活跃态（带微高亮/计数提示）；
- 左键点击：直接在当前桌面或全屏应用上方弹出毛玻璃悬浮控制卡（`NSPopover` / `MenuBarExtra(.window)`）；
- 鼠标悬停菜单栏图标滚动滚轮：按步进 ±`5%` 快速调节投送总音量（跨安全上限仍触发高音量二次确认），无需额外辅助功能权限；
- 右键菜单：提供快速暂停/恢复、切回本机播放、打开主窗口、偏好设置、退出应用。

### 2.2 核心界面：菜单栏毛玻璃悬浮控制卡（宽 `352pt`）

```text
┌───────────────────────────────────────────────────┐
│ 窝爱听   ● 投送中 · 2 台          [均衡 400ms ▾] ⚙ │  ← 顶栏：状态胶囊 / 4档延迟热切 / 设置与主窗入口
├───────────────────────────────────────────────────┤
│ [非阻塞更新轻提示条：发现新版本 vX.Y.Z · 查看 · ×] │  ← 按需显示，点击 × 按 tag 持久化关闭
├───────────────────────────────────────────────────┤
│ ┌────┐  正在播放歌名（或「正在投送 · 网易云音乐」）  │  ← 正在播放区（左 64pt 圆角封面/图标 + 右文控）
│ │封面│  歌手名              [⏮]   [▶/⏸]   [⏭]     │    • AppleScript 播放器：完整封面/歌名/进度条
│ └────┘  ━━━━●━━━━━━━━━━━━━━  01:24 / 03:58        │    • 其他音源：诚实展示 App 卡 + 系统媒体键控制
│ 🔊 ━━━━━━━━●━━━━━━━━━━━━━━━━━━━  45%              │  ← 总音量 4pt 细轨道（跨上限弹 Style B 二次确认）
├───────────────────────────────────────────────────┤
│ 音源  [🎵 网易云 · 已静音本机]  [QQ]  [Spotify]  › │  ← 单 App 音源槽位 + 常用音源横向快捷胶囊（热切不断箱）
├───────────────────────────────────────────────────┤
│ 输出设备                    2 台   [恢复上次]   ↻  │  ← 手动刷新淘汰离线；上次组合置顶
│ ○ 本机播放（Mac 扬声器）                           │
│ ● 客厅音箱 · 客厅              398ms   MiPlay   ✓ │  ← Style B 亮卡反显 + 行内独立音量滑块
│   🔊 ━━━━━━━●━━━━━━━━━━━━━━━━  40%                │
│ ● 卧室音箱 · 卧室              402ms   MiPlay   ✓ │
│   🔊 ━━━━━●━━━━━━━━━━━━━━━━━━  32%                │
│ ○ 书房音箱 · 书房                      MiPlay   ○ │
└───────────────────────────────────────────────────┘
```

### 2.3 配套独立窗口（按需打开，不抢占桌面）
1. **可选独立主窗口**：复用四段式信息架构，提供专辑封面柔和氛围底（`.ultraThinMaterial`）与更宽裕的多音箱/音源管理视图。
2. **常用音源管理页**：经 `NSWorkspace` 枚举 `/Applications` 与运行中音频应用、展示高清原图标、支持自由增删与拖动排序、为每个常用音源提供独立「发声自动识别」开关，点击即可唤起对应软件。
3. **设置窗口（与 Android 1:1 对齐的 4 张分组卡片）**：
   - **外观与发现**：三段式主题 `[跟随系统 | 浅色 | 深色]`、四段式语言 `[跟随系统 | 简体中文 | English | 日本語]`、显示全部发现设备开关、重新扫描入口；
   - **音频与系统权限**：「系统音频录制」权限实时状态与直达系统设置入口、投送时单 App 本机静音开关、高音量保护（默认开 / 上限 `60%` / `30%–90%` 步进 `5%`）、发声自动识别总开关、可选硬件音量键（F11/F12）接管开关（附辅助功能权限说明）；
   - **连接与调试**：直出「运行日志」入口（附本地日志体积）、折叠高级区（手动添加音箱 IP、音源模式：选定 App / 测试静音、自定义播放延迟 `ms`、房间参考模式、认证模式）；
   - **关于窝爱听**：居中 F2 品牌头 + 版本号、`检查更新`、`接收测试版更新` 开关、`版本发布与更新日志` 外链、`赞赏支持`（受 `ENABLE_SPONSOR_ENTRY` 宏控制，Style B 静态赞赏卡片，无支付 SDK 与弹窗推销）。
4. **本地运行日志窗口（`LogViewerView`）**：结构化时间线（日分割线 + 级别/来源色条 + `key=value` 胶囊 + 长 JSON 折叠格式化）与原始文本切换、关键词搜索、一键导出 `.txt` 与清空。
5. **Style B 更新详情弹窗**：消费 L1 `cozyting_update_evaluate_json` 返回的 `(current_version, target_version]` 跨版本日志，结构化渲染导语块、三语分组徽标与嵌套缩进，支持 SHA-256 校验与 DMG 下载打开。

---

## 三、方案 A 跨 Apple 端分层架构

严格执行 [`apple/AGENTS.md`](../apple/AGENTS.md) 定义的三层目录与单向依赖边界：

```text
第三方音乐 App（网易云 / QQ 音乐 / Spotify / Apple Music / 浏览器等）
   │ Core Audio 进程音频流（按 App Bundle ID 聚合全部子进程 AudioObjectID）
   ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ apple/macOS（纯 macOS 壳 · 最低 macOS 14.2）                              │
│  • ProcessTapAudioEngine：AudioHardwareCreateProcessTap + CATapDescription│
│    (.mutedWhenTapped) → Aggregate Device IOProc → 无锁环形缓冲            │
│  • RunningAppSourceMonitor：NSWorkspace + kAudioProcessPropertyIsRunningOutput
│  • NowPlayingBridge：AppleScript (Apple Music / Spotify) + 诚实降级卡     │
│  • MenuBarExtra / NSPopover 悬浮卡 + 独立设置/日志窗口                     │
└────────────────────────────────────┬─────────────────────────────────────┘
                                     │ 依赖（严禁反向依赖）
┌────────────────────────────────────▼─────────────────────────────────────┐
│ apple/CozyTingShared（本地 Swift Package · macOS / 未来 iOS 共用）        │
│  • CozyTingAudioKit：AudioToolbox 动态重采样 + 48kHz 256kbps 裸 AAC 编码  │
│  • CozyTingCoreKit ：cozyting_core.h RAII 桥接、数据模型、偏好、日志/更新 │
│  • CozyTingDesignKit：tokens.json 色板、SVG 图标、三语词典、SwiftUI 组件  │
└────────────────────────────────────┬─────────────────────────────────────┘
                                     │ 静态链接 C11 ABI
┌────────────────────────────────────▼─────────────────────────────────────┐
│ core/（L1 共享纯 Rust 核心 · libcozyting_core.a / CozyTingCore.xcframework）│
│  • mDNS 发现 + 8899 miName/roomName + Legacy/Safety 双认证               │
│  • WFD RTSP + UDP TimerServer + OffsetBarrier 14 AU + 63 台毫秒同频      │
│  • 高音量保护裁决 + 跨版本更新引擎 + 轮转核心日志                         │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 四、音频捕获、进程树追踪与实时线程模型

### 4.1 严格单 App 捕获与进程树聚合（Fail-Closed）
1. **目标解析**：用户选定目标 App（持久化 `bundleIdentifier` + 应用显示名，**绝不持久化易漂移的 PID**）。
2. **进程树映射**：启动捕获时查询 `kAudioHardwarePropertyProcessObjectList`，筛出所有归属目标 `bundleIdentifier`（或其进程父链属于目标 App 的 `Helper (Audio)` / `Renderer` 子进程）的 `AudioObjectID` 集合。
3. **动态重绑与防退化**：监听 `kAudioHardwarePropertyProcessObjectList` 属性变更；若目标 App 新增音频子进程则平滑更新 Tap；若目标 App 退出或没有任何匹配进程，则干净停止并通知 UI，**绝不退化为全局混音或麦克风采集**。
4. **单 App 本机静音守卫**：当启用「投送时本机静音」时配置 `CATapDescription.muteBehavior = .mutedWhenTapped`；在停止投送、切换音源、`applicationWillTerminate` 及 `deinit` 路径确保销毁 Tap 与 Aggregate Device，杜绝目标 App 残留静音。

### 4.2 实时回调与编码链路（零阻塞）
```text
Core Audio IOProc（实时线程：零 malloc / 零锁 / 零 I/O）
   │ 写入定长无锁环形缓冲（Lock-free SPSC RingBuffer）
   ▼
专用编码线程（CozyTingAudioKit）
   │ 1. 检测源 AudioStreamBasicDescription（44.1k / 48k / Float32 / Int16 / 单双声道）
   │ 2. AudioConverter #1 动态重采样归一化 → 48000Hz · 2ch · Linear PCM（1024 samples / 21.333ms）
   │ 3. AudioConverter #2 硬件 AAC-LC（kAudioFormatMPEG4AAC · 256kbps）→ 裸 AU（无 7B ADTS 头）
   ▼
cozyting_engine_push_aac(handle, ptr, len)
   │ L1 sync_channel(96) → media-clock → ADTS/PES/MPEG-TS/RTP → 多音箱同步发送
   └─ 返回 -4（队列满）时计数并丢弃溢出帧，绝不堆积内存
```

### 4.3 无死锁停止顺序
1. 置原子停止标志，实时回调立即返回；
2. 停止 Aggregate Device IOProc，销毁 Aggregate Device 与 Process Tap（即时解除单 App 静音）；
3. 释放编码线程与 `AudioConverter`；
4. 在后台队列调用 `cozyting_engine_stop(handle)`（向所有音箱发送 `CLOSE(0x0002)` 并 `join` 全部 L1 线程）。

---

## 五、系统难点与合规降级策略

1. **macOS 15.4+ `mediaremoted` 私有权限限制（最大难点）**
   - 苹果在 macOS 15.4 起拦截第三方进程直接访问私有 `MediaRemote` 框架；
   - **本蓝图定版策略（合规优先）**：通过 `AppleScript` / `ScriptingBridge` 为支持脚本词典的播放器（Apple Music、Spotify 等）提供完整歌名、艺人、封面与播放进度；对网易云音乐、QQ 音乐、浏览器等不支持统一脚本词典的客户端，诚实展示「正在投送 · \<App 图标 + 名称\>」与系统媒体键（播放/暂停/切歌）控制，不引入脆弱的私有二进制注入。
2. **自动识别正在播放的音源**
   - 监听 Core Audio 进程对象的 `kAudioProcessPropertyIsRunningOutput` 变化，仅对「常用音源名单中已开启自动识别」的 App 做约 `600ms–1000ms` 连续发声防抖，过滤短提示音误切；手动切源后设置短暂冷却窗口避免被旧会话立刻拉回。
3. **Wi-Fi 频段探测**
   - 优先通过 `CoreWLAN` 读取当前接口信道频段（≥4900MHz → 800ms，否则 1000ms）；若系统因未授定位权限返回空，则安全回退默认基线（1000ms），不影响用户随时热切 4 档延迟。
4. **硬件音量键（F11 / F12）接管**
   - 默认零额外权限方案：鼠标悬停菜单栏图标滚轮调音量 + 面板内键盘快捷键调音量；
   - 全局拦截 F11/F12 媒体音量键作为设置页可选高级开关（显式说明需要「辅助功能」权限，未开启时绝不静默申请）。

---

## 六、M1–M9 内部开发任务清单与验收闭环（零废代码 · 按序推进）

> 所有内部开发任务均在此清单追踪（不在公开 GitHub Issues 刷屏拆分细碎工程任务单；公开社区进展仅在单个 Roadmap Issue 汇总）。每完成一项，在对应 PR 中勾选 `[x]` 并附简短实测说明。

- [x] **M1 · Apple 方案 A 工程骨架、L1 静态库打包脚本与 `CozyTingCoreKit` 桥接**
  - **交付物**：
    - `apple/CozyTingShared/Package.swift`（声明 `CozyTingCoreKit`、`CozyTingAudioKit`、`CozyTingDesignKit` 三子库）与最小 macOS 应用工程骨架；
    - `scripts/build-macos.sh`（编译 `aarch64-apple-darwin` 静态库并组装头文件 / 可选 Universal）；
    - `CozyTingCoreKit` 全量类型安全封装 [`cozyting_core.h`](../core/include/cozyting_core.h)（RAII `cozyting_string_free`、发现/路由/引擎/高音量/日志/更新 API）及 `CozyTingCoreKitTests`。
  - **验收**：`cargo test --workspace`（93 tests）+ C11 头文件校验（`clang -std=c11 -Wall -Wextra -Werror -fsyntax-only`）+ `swift test`（33 tests / 6 suites）+ `./scripts/build-macos.sh`（`aarch64-apple-darwin` XCFramework + XcodeGen + Release ad-hoc `xcodebuild`）全绿。（注：`--universal` 路径未在本机编译，因当前 `rustup` 未安装 `x86_64-apple-darwin`；音频捕获与真实音箱建连将在 M2/M3 实测。）

- [x] **M2 · 生产级单 App 音频捕获、原生静音与 AAC 编码管线（含真机音源与 DRM 验收）**
  - **交付物**：
    - `CozyTingAudioKit`：C11 无锁环形缓冲（`CozyTingAudioAtomics` + `LockFreeRingBuffer`）、`PcmProbe`、`AudioToolboxAACEncoder`（双阶段动态格式重采样 + 48 kHz 256 kbps 裸 AAC-LC 编码）、`AudioEncodingPipeline`（`-4` 背压丢帧计数）及多格式 Mock `AudioBuffer` 单测（44.1 / 48 / 96 kHz、单/双声道、Planar/Interleaved、Int16/Float32、动态切换）；
    - `apple/macOS/Sources/Audio/`：`ProcessTreeResolver`（BSD 父子进程树 + Helper Bundle ID 聚合，严格 Fail-Closed）、`MuteGuard`（退出/`SIGINT`/`SIGTERM` 静音释放守卫）、`ProcessTapAudioEngine`（`AudioHardwareCreateProcessTap`、`mutedWhenTapped`、零分配实时 `IOProc`、不断箱热切源与 4 步无死锁停止顺序）。
  - **验收**：`cargo test --workspace` + C11 校验 + `swift test`（含多格式 Mock `AudioBuffer` AAC 与背压单测）+ `./scripts/build-macos.sh` Release 构建全绿；五类真实音源与 DRM 边界矩阵已如实登记于 [`docs/COMPATIBILITY.md`](COMPATIBILITY.md)（注：自动化无人值守构建环境无法交互授予 macOS 系统音频录制 TCC 弹窗，且网易云音乐 / Spotify 未安装于当前构建机，五类音源真机听感与 DRM 表现已诚实标记为 `⏳` 待人工交互实测）。

- [x] **M3 · 局域网发现、房间名解析与多音箱毫秒级同频投送集成**
  - **交付物**：
    - `CozyTingCoreKit`：`NetworkInterfaceHelper`（POSIX `getifaddrs` 局域网 IPv4 优选与校验）、`DeviceDisplayFormatter`（`miName · roomName` 去重展示、上次组合置顶与离线/隐藏过滤）、`LatencyPreset`（4 档 + 自定义 `ms` 映射）及单测；
    - `apple/macOS/Sources/Cast/CastCoordinator.swift`：接入 `cozyting_discover_json_ex`（冷启动合并缓存、手动刷新强制淘汰离线并保留在播箱）、L1 路由状态机驱动的多箱同频/动态加减箱/Legacy·Safety 双认证、1 Hz 原位状态与延迟轮询、4 步无死锁停止与后台队列隔离，并接入 `AppDelegate` 启动与退出生命周期。
  - **验收**：`cargo test --workspace` + C11 校验 + `swift test`（含发现/格式化/路由/延迟契约测试）+ `./scripts/build-macos.sh` Release 构建全绿。（注：多箱真实局域网音箱并发建连、动态增删与 `CLOSE(0x0002)` 听感验收待人工现场交互验证。）

- [x] **M4 · 菜单栏常驻图标、毛玻璃悬浮控制卡与 Style B 明暗双主题**
  - **交付物**：
    - `CozyTingDesignKit`：`CozyTingIcons`（28 个语义图标 + F2 深夜亮灯小屋垂尾小猫 `evenOdd` 矢量标）、`StyleBComponents`（4pt 细轨道 `CozyVolumeTrackSlider` 防轮询打断拖动、`CozyStatusBadge`、Style B 亮卡反显 `CozyDeviceRowCard`、`CozyHighVolumeConfirmView`）与 `PlatformAdapters`（18pt Template 菜单栏图标生成与明暗色板映射）；
    - `apple/macOS/Sources/UI/`：`MenuBarStatusController`（`NSStatusItem` + `NSPopover` 352pt 悬浮卡 + 右键菜单 + 悬停图标滚轮调音量）、`PopoverControlCardView`（四段式毛玻璃控制卡）与 `MainWindowView`（可选独立主窗口 + 跟随系统/浅色/深色切换）。
  - **验收**：`cargo test --workspace` + C11 校验 + `swift test`（含 `CozyTingDesignKitTests` 图标/矢量/主题单测）+ `./scripts/build-macos.sh` Release 构建全绿。（注：浅/深/跟随系统实机桌面视觉与真机滑块手势流畅度待人工交互验收。）

- [ ] **M5 · 音量持久化、高音量二次确认、上次组合一键恢复与 4 档延迟热切**
  - **交付物**：
    - 总音量与最近 32 台单音箱独立音量 `UserDefaults` 持久化 + 冷启动高音量安全钳制；
    - L1 高音量保护跨线 Style B 二次确认弹窗；
    - 上次音箱组合置顶与「恢复上次」胶囊；
    - 顶栏 4 档延迟快捷胶囊（标准/均衡/低延迟/极速 + 自定义 `ms`）+ `CoreWLAN` 频段探测与无权限回退 + 菜单栏滚轮/面板快捷键调音量。
  - **验收**：重启恢复音量钳制、跨线确认与投送中 4 档免断连热切均实机验证通过，补齐单元契约测试。

- [ ] **M6 · 常用音源管理、进程发声自动识别热切与一键唤起音源 App**
  - **交付物**：
    - `NSWorkspace` 枚举已安装与运行中应用、高清原图标展示、常用音源增删与拖动排序、点击一键唤起；
    - 基于 `kAudioProcessPropertyIsRunningOutput` + 常用音源白名单防抖的自动识别与投送中不断箱热切源。
  - **验收**：切换音乐 App 自动识别热切不断音箱连接，系统提示音与非白名单 App 不误切。

- [ ] **M7 · 「正在播放」分层适配与系统媒体控制（应对 macOS 15.4+ 限制）**
  - **交付物**：
    - `NowPlayingBridge`：对 Apple Music / Spotify 经 AppleScript 读取曲目、艺人、封面、进度与播控；对其他音源诚实降级展示音源 App 卡片 + 系统媒体键控制；
    - 可选「硬件 F11/F12 音量键接管」高级开关（带显式辅助功能权限说明，默认关闭）。
  - **验收**：AppleScript 已适配与未适配两类音源均有清晰、诚实的 UI 呈现，未授权时不弹窗打扰。

- [ ] **M8 · 中/英/日三语 i18n、本地运行日志查看导出与设置/关于页**
  - **交付物**：
    - `CozyTingDesignKit` `Localizable.xcstrings` 补齐 `zh-CN` / `en` / `ja` 全量文案与应用内语言切换；
    - 4 组设置页卡片、「关于窝爱听」与受 `ENABLE_SPONSOR_ENTRY` 控制的纯静态赞赏弹窗；
    - `LogViewerView` 独立日志窗口（结构化时间线 + JSON 折叠 + 搜索 + `.txt` 导出 + 清空，复用 L1 `diag_log`）。
  - **验收**：三语切换无硬编码残留与文本截断，日志轮转/导出/清空功能正常。

- [ ] **M9 · 透明版本更新、DMG 打包签名公证、CI 门禁与公开文档同步**
  - **交付物**：
    - 扩展 `update.json` 与 `scripts/generate-update-manifest.py` 支持 `macos` 平台；接入跨版本结构化更新日志弹窗与 SHA-256 校验；
    - DMG 打包脚本（支持 Developer ID 签名/公证与本地 Ad-hoc 构建）及 `.github/workflows/ci.yml` 新增 `macos-latest` 构建与 `swift test` 门禁；
    - 同步更新三语 `README`、GitHub Pages、`docs/llms.txt` 与 [`docs/COMPATIBILITY.md`](COMPATIBILITY.md)。
  - **验收**：GitHub Actions `macos-latest` CI 全绿，端到端 DMG 安装运行验收通过。
