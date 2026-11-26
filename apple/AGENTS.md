# Apple 端开发规范（`apple/` · 方案 A：共享底座 + 平台独立壳 · 强制）

> 本文件是 **窝爱听（CozyTing）** Apple 端（macOS + 未来 iOS）工程的最高执行红线，与根目录 [`AGENTS.md`](../AGENTS.md) 同等强制生效。
>
> **开工必读与防跑偏约束**：
> - 推进 macOS 客户端开发前，必须先读取 [`docs/MACOS_CLIENT_DESIGN.md`](../docs/MACOS_CLIENT_DESIGN.md)，严格按其定版的界面形态、架构边界与 **M1–M9 任务清单** 逐项推进并打钩更新；
> - 推进 iOS 客户端开发前，必须先读取 [`docs/IOS_CLIENT_DESIGN.md`](../docs/IOS_CLIENT_DESIGN.md)；
> - **严禁脱离既定方案 A 目录与蓝图另起炉灶或随意更改目录/模块划分**；确需调整架构时，必须先向用户说明原因并获明确批准，再同步更新本文件与对应设计文档。

---

## 1. 目录结构与三层拆分红线（严禁反向依赖）

Apple 端统一收口在仓库根目录 `apple/` 下，采用「**本地 Swift Package 共享三子库 + macOS / iOS 独立壳**」架构：

```text
apple/
├── AGENTS.md                        # 本规范文件
├── Frameworks/
│   └── CozyTingCore.xcframework     # 构建产物（.gitignore 忽略，按需由构建脚本生成）
├── CozyTingShared/                  # 本地 Swift Package（最低支持 macOS 14.2 / iOS 16）
│   ├── Package.swift
│   ├── Sources/
│   │   ├── CozyTingCoreKit/         # L1 C ABI 桥接、RAII 内存管理、强类型模型、持久化、日志/更新引擎（零 UI）
│   │   ├── CozyTingAudioKit/        # AudioToolbox 48kHz 256kbps 裸 AAC 编码、动态重采样、PcmProbe（零 UI）
│   │   └── CozyTingDesignKit/       # design/tokens.json 色板、design/icons/*.svg、三语词典、纯 SwiftUI 原子组件
│   └── Tests/
│       ├── CozyTingCoreKitTests/
│       ├── CozyTingAudioKitTests/
│       └── CozyTingDesignKitTests/
├── macOS/                           # 纯 macOS 壳（最低 macOS 14.2，默认非沙箱直装 DMG）
│   ├── Audio/                       # Core Audio Process Tap、进程树聚合、mutedWhenTapped、静音安全守卫
│   ├── System/                      # NSWorkspace 音源枚举、Core Audio 发声检测、CoreWLAN、AppleScript、媒体键
│   └── UI/                          # MenuBarExtra / NSPopover 悬浮卡、可选主窗口、设置/关于/日志窗口
└── iOS/                             # （未来推进时创建）纯 iOS 壳（最低 iOS 16）
    ├── App/                         # 主 App（NWBrowser、RPSystemBroadcastPickerView、NavigationStack、ControlWidget）
    └── BroadcastExtension/          # ReplayKit 扩展（只依赖 CozyTingCoreKit + CozyTingAudioKit，死守 50MB 红线）
```

### 模块拆分铁律
1. **单向依赖**：只有 `macOS/` 与 `iOS/` 允许依赖 `CozyTingShared`；`CozyTingShared` 内部只允许 `CozyTingDesignKit → CozyTingCoreKit` 与 `CozyTingAudioKit → CozyTingCoreKit`，**严禁任何反向依赖或跨壳引用**。
2. **iOS 扩展内存保护**：`CozyTingCoreKit` 与 `CozyTingAudioKit` 严禁引入 `SwiftUI`、`AppKit`、`UIKit` 或大体积图片资源，确保未来 iOS `BroadcastExtension` 仅链接这两者即可守住 50MB Jetsam 内存红线。
3. **构建产物隔离**：`apple/Frameworks/`、`.build/`、`DerivedData/` 等编译输出目录一律不得纳入 Git 跟踪；多 Agent 并发 worktree 下各自独立构建，禁止跨 worktree 共享可写缓存目录。

---

## 2. 反 `#if os(...)` 泥潭铁律（共享层纯净度约束）

1. **`CozyTingCoreKit` 与 `CozyTingAudioKit`**：原则上 **零 `#if os(...)`**，严禁导入 `AppKit` / `UIKit` / `SwiftUI`。
2. **`CozyTingDesignKit`**：
   - 只承载 **纯原子与局部复合 SwiftUI 组件**（如设备行、细轨道音量条、高音量二次确认弹窗、延迟档位菜单、结构化更新日志视图、日志时间线视图、关于与赞赏卡片）；
   - **严禁将整页级平台壳塞进共享层**：禁止在共享包内出现 `MenuBarExtra`、`NSPopover`、`NSWindow`、`NSWorkspace`、`RPSystemBroadcastPickerView`、`UIApplication` 或平台特定导航壳；
   - 极少数跨端视觉/系统适配（如毛玻璃材质、平台图片类型别名、剪贴板与系统分享出口）必须统一收口在单个文件 `PlatformAdapters.swift` 内，**禁止在业务视图中散落 `#if os(macOS)` / `#if os(iOS)`**。

---

## 3. L1 Rust 核心复用与 FFI 安全红线

1. **禁止在 Swift 侧重写 L1 逻辑**：
   - 设备发现过滤与缓存融合（`cozyting_discover_json_ex`）、路由状态机（`cozyting_route_apply_json`）、延迟预热计算（`cozyting_delay_for_frequency` / `cozyting_startup_runway_aus`）、高音量保护裁决（`cozyting_volume_guard_evaluate_json` / `cozyting_engine_set_volume_checked`）、版本更新裁决（`cozyting_update_evaluate_json`）与核心日志轮转（`cozyting_init_log_file` / `cozyting_recent_logs` / `cozyting_clear_logs`），必须统一通过 [`core/include/cozyting_core.h`](../core/include/cozyting_core.h) 委托给 L1 Rust，严禁在 Swift 中维护第二份重复规则。
2. **FFI 内存安全（RAII 强制）**：
   - 所有 `cozyting_*` C 函数与裸指针只允许出现在 `CozyTingCoreKit` 内部封装层，上层业务与 UI 严禁直接调用 C ABI；
   - 所有返回 `char *` 的 C 函数必须经过唯一内部辅助函数转成 Swift `String` 并在 `defer { cozyting_string_free(ptr) }` 中立即释放，禁止手工散落释放导致泄漏或二次释放。
3. **无死锁停止顺序（铁律）**：
   - 停止投送会话必须严格按序执行：
     1. 置停止标志，拒绝后续音频采集回调进入编码队列；
     2. 停止并释放音频捕获对象（Core Audio Process Tap + Aggregate Device / ReplayKit）与 `AudioConverter`；
     3. 调用 `cozyting_engine_stop(handle)`（唤醒 L1 `media-clock`、`join` 线程并向音箱发 `CLOSE(0x0002)`）；
     4. 清理引擎句柄、恢复/释放静音守卫并更新 UI 状态。
4. **C ABI 同步门禁**：
   - 凡修改 `core/src/ffi_c.rs` 或 `core/include/cozyting_core.h`，同一 PR 必须同步更新 `CozyTingCoreKit` 封装与单测，并通过：
     - `cargo test --workspace`
     - `clang -std=c11 -Wall -Wextra -Werror -fsyntax-only core/include/cozyting_core.h`
     - `swift test`

---

## 4. 音频捕获、Fail-Closed 与实时线程红线

1. **macOS 严格单 App 捕获（Fail-Closed）**：
   - 最低系统版本锁定 **macOS 14.2+**，主路径使用原生 `AudioHardwareCreateProcessTap` + `CATapDescription`（不引入 BlackHole 等虚拟声卡驱动，不申请屏幕录制权限，仅在 `Info.plist` 声明 `NSAudioCaptureUsageDescription`）；
   - 必须通过 **App Bundle ID → 进程树动态聚合** 绑定目标应用及其发声子进程（覆盖 Electron / Chromium `Helper (Audio)` / `Renderer`）；
   - 当目标应用未运行、已退出或进程对象失效时，必须干净停止或进入明确等待/报错态，**严禁静默退化为全系统全局混音或麦克风采集**。
2. **单 App 静音安全守卫**：
   - 使用 `CATapDescription.muteBehavior = .mutedWhenTapped` 实现真·单 App 本机静音时，必须在停止投送、热切换音源、目标退出、应用正常退出（`applicationWillTerminate`）及异常清理路径确保销毁 Process Tap 与 Aggregate Device，防止目标 App 残留静音。
3. **实时音频回调零阻塞纪律**：
   - Core Audio `IOProc` 实时回调线程内 **严禁堆内存分配（`malloc` / Swift 集合扩容）、严禁互斥锁争抢、严禁日志/文件/网络 I/O、严禁直接调用 FFI**；
   - 实时回调仅向定长无锁环形缓冲写入 PCM，由独立编码线程完成格式归一化/重采样（44.1kHz ↔ 48kHz、单声道 ↔ 立体声自动平滑重建）与 `AudioToolbox` 硬件 AAC-LC 编码（48kHz 立体声 256kbps 裸 AU，不含 7 字节 ADTS 头），再推入 `cozyting_engine_push_aac`；
   - 遇到 `cozyting_engine_push_aac` 返回 `-4`（L1 队列满）时只计数上报并丢弃溢出帧，严禁在 Swift 侧无限堆积内存。
4. **DRM 与系统限制诚实边界**：
   - 不尝试绕过 Apple Music FairPlay / 浏览器 Widevine DRM；不强依赖易随 macOS 小版本失效的私有 `MediaRemote` 注入；
   - 实测通过、静音或降级的音源必须如实登记至 [`docs/COMPATIBILITY.md`](../docs/COMPATIBILITY.md)。

---

## 5. Swift 并发、依赖与零臃肿红线

1. **零重型第三方依赖**：优先 100% 使用系统原生框架（`SwiftUI`、`CoreAudio`、`AudioToolbox`、`Network`、`CoreWLAN`、`Foundation`、`AppKit`）；引入任何外部 Swift Package（如 Sparkle）前必须先说明利弊并获得明确批准。
2. **Swift 6 严格并发合规**：
   - 新建代码默认满足 `Sendable` 与 Actor 隔离要求；
   - 所有 UI 状态集中收口在 `@MainActor` ViewModel；
   - 所有 L1 阻塞调用（`discover`、`connect`、`add_receiver`、`remove_receiver`、`set_playback_delay`、`stop`）必须派发至专用后台队列/执行器，**严禁阻塞主线程导致菜单栏或弹窗卡顿**。

---

## 6. 视觉、三语 i18n 与跨端状态对齐红线

1. **单一视觉事实来源**：颜色、圆角、字号与间距严格消费 [`design/tokens.json`](../design/tokens.json)，线性图标复用 `design/icons/*.svg`（设为 Template Image）；凡调整 macOS/iOS 界面结构或交互边界，同一 PR 同步更新 [`design/UI_SPEC.md`](../design/UI_SPEC.md)。
2. **全链路三语（`zh-CN` / `en` / `ja`）**：
   - 所有面向用户的字符串统一收敛至 `CozyTingDesignKit` 的 `Localizable.xcstrings`，禁止硬编码单语种自然语言文案；
   - 支持跟随系统语言与应用内切换；品牌名（中文 `窝爱听` / 非中文 `CozyTing`）与协议名（`MiPlay`）解耦规范与 Android 端保持一致。
3. **本地状态持久化 1:1 对齐 Android**：
   - 总音量、按设备 ID 维护的单音箱独立音量（最近 32 台）、高音量保护策略（默认开启、上限 `60%`、合法范围 `30%–90%` 步进 `5%`、冷启动安全钳制）、上次勾选音箱组合与置顶排序、更新轻提示条已关闭版本号 `tag`，均须与 Android 保持语义一致。

---

## 7. 隐私、纯局域网与安全分发红线

1. **纯局域网 · 零云端 · 零遥测**：除每 24 小时一次匿名只读 HTTPS + `ETag 304` 拉取公开 `update.json` 外，禁止任何外网请求、统计埋点与账号登录；运行日志仅存本地私有沙箱且严禁包含音频内容。
2. **分发模式与编译宏隔离**：
   - macOS 首发定位为 **GitHub Releases 直装 DMG**；
   - 对 App Store 沙箱敏感的能力（非沙箱自更新下载、纯静态赞赏码入口、AppleScript 跨应用查询）必须统一通过编译宏隔离（如 `COZYTING_APP_STORE`、`ENABLE_SPONSOR_ENTRY`），禁止在业务代码里硬编码渠道逻辑。
3. **隐私自检（沿用根 `AGENTS.md 1.2`）**：严禁提交任何签名证书、`.p12`、Apple ID、Team ID 私有凭证、本机绝对路径、真实家庭局域网 IP 或真实设备尾号；单测与文档统一使用 RFC 5737 占位网段（`192.0.2.x` 等）。

---

## 8. 自动化门禁、Git 流程与诚实交付红线

1. **独立 Worktree + A 档 PR 门禁**：
   - 凡涉及 `apple/` 代码、脚本或 `core/` C ABI 改动，均按根 [`AGENTS.md`](../AGENTS.md) `0.3` / `0.4` 在 `.worktrees/<type>-<topic>` 开发，走 A 档 GitHub PR + CI 门禁合入；
   - 每完成 `docs/MACOS_CLIENT_DESIGN.md` 中的一个阶段（M1–M9），必须在同一 PR 中勾选对应 `[x]` 并填写简短实测记录。
2. **PR 必过验证命令**：
   - L1：`cargo test --workspace` 与 `clang -std=c11 -Wall -Wextra -Werror -fsyntax-only core/include/cozyting_core.h`
   - Shared：`swift test`（在 `apple/CozyTingShared` 目录执行，覆盖 FFI 契约、多格式 AAC 编码、路由/音量/更新日志渲染单测）
   - macOS：`./scripts/build-macos.sh` 离线编译通过（待 M1 落地后生效）
3. **诚实测试声明**：编译与单测绿灯 ≠ 真机 Process Tap 捕获、单 App 静音与多音箱听感验收；PR 描述须明确区分「已真机验证」与「未验证边界」。
