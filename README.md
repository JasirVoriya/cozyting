<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/hero-banner-dark.svg">
  <source media="(prefers-color-scheme: light)" srcset="docs/assets/hero-banner-light.svg">
  <img alt="MiPlay Sender — Android & iPhone 纯局域网投送小米音箱" src="docs/assets/hero-banner-light.svg" width="100%">
</picture>

# MiPlay 发送端（纯局域网）

**跨端局域网音频捕获与投送（Android & iOS）· 零云端纯局域网 · 多音箱毫秒级同频同步**

<p>
  <strong>简体中文</strong> · <a href="README_EN.md">English</a> ·
  <a href="https://jasirvoriya.github.io/miplay-sender/">在线交互式架构图</a> ·
  <a href="docs/ARCHITECTURE.md">源码深度手册</a> ·
  <a href="docs/COMPATIBILITY.md">设备兼容矩阵</a> ·
  <a href="CHANGELOG.md">更新日志</a> ·
  <a href="CONTRIBUTING.md">参与贡献</a>
</p>

<p>
  <a href="https://github.com/JasirVoriya/miplay-sender/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/JasirVoriya/miplay-sender/actions/workflows/ci.yml/badge.svg"></a>
  <a href="https://github.com/JasirVoriya/miplay-sender/releases"><img alt="Version v0.9.4" src="https://img.shields.io/badge/Version-v0.9.4-1F2430?style=flat-square"></a>
  <a href="LICENSE"><img alt="License: AGPL-3.0-only" src="https://img.shields.io/badge/License-AGPL--3.0--only-FF5C6C?style=flat-square&logo=gnu&logoColor=white"></a>
  <img alt="Android 10+ (API 29-36)" src="https://img.shields.io/badge/Android-10%2B%20(API%2029--36)-3DDC84?style=flat-square&logo=android&logoColor=white">
  <img alt="iOS FFI Ready · SwiftUI Planned" src="https://img.shields.io/badge/iOS-FFI%20Ready%20%C2%B7%20SwiftUI-1F2430?style=flat-square&logo=apple&logoColor=white">
  <img alt="Pure LAN · Zero Cloud" src="https://img.shields.io/badge/Network-Pure%20LAN%20%C2%B7%20Zero%20Cloud-0EA5E9?style=flat-square">
  <img alt="Multi-Room Up to 63" src="https://img.shields.io/badge/Multi--Room-Max%2063%20Speakers-10B981?style=flat-square">
</p>

<p>
  <img alt="Rust 2021 (1.75+)" src="https://img.shields.io/badge/Rust-2021%20(%E2%89%A51.75)-181B22?style=flat-square&logo=rust&logoColor=white">
  <img alt="Kotlin 2.0.21" src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=flat-square&logo=kotlin&logoColor=white">
  <img alt="JNI + C ABI (FFI)" src="https://img.shields.io/badge/Bridge-JNI%20%C2%B7%20C%20ABI%20(FFI)-374151?style=flat-square&logo=rust&logoColor=white">
  <img alt="ABI arm64-v8a · 16KB Page" src="https://img.shields.io/badge/ABI-arm64--v8a%20%C2%B7%2016KB-2B313D?style=flat-square">
  <img alt="Audio AAC-LC 48kHz 256kbps" src="https://img.shields.io/badge/Audio-AAC--LC%2048kHz%20256kbps-FF5C6C?style=flat-square">
</p>

</div>

---

## 核心能力与技术底座

为什么不用系统录屏投屏？整机投音会把微信语音、短视频和通知提示音一并广播到客厅音箱；而原厂妙播又受限于特定品牌机型。**MiPlay Sender** 用纯局域网跨端架构实现了**「只投你选的那一个音乐 App，最多 63 台音箱毫秒级同频合唱」**：

![MiPlay Sender 核心能力与技术底座](docs/assets/feature-highlights.svg)

- **🎯 只投音乐、不串杂音（Fail-Closed）**：启动前实时校验目标包同用户、非共享 UID 与 SHA-256 签名，严禁静默退化为全系统录屏或麦克风采集；内置 `PcmProbe` 6 拍有声验证锁定，彻底解决切歌间隙手机/音箱短暂双出声。
- **🔊 63 台音箱毫秒级同频**：L1 纯 Rust 引擎采用确定性实时线程（**零异步运行时**），通过 `EPOCH` 单调时基、`UDP 40B TimerServer` 时钟主与 `OffsetBarrier` 14 AU 预读对齐，支持播放中途随时加减音箱与设备行内原位显示整数 `ms` 延迟。
- **🔐 零云端纯局域网 · 双认证隔离**：全程本地局域网直连、零账号登录；严格隔离 Legacy（`MD5 + HMAC-SHA1`）与 Safety（`OPack + 双向 HMAC + 链式 AES-128-CBC`）两条控制链路。
- **🧩 跨端客户端 + 无副作用诊断 CLI**：双端共用 L1 Rust 核心与 `design/` 明暗双主题规范（支持深色/浅色/跟随系统）；桌面端 `miplay-cli inspect` 支持只读查询音箱状态（退出仅 `TCP shutdown`，绝不发 `CLOSE` 打断当前播放）。

---

## 架构总览（以图代表）

> 🧭 **深入探索源码与模块上下游**：
> - **交互式图形探索**：访问 **[在线交互式架构浏览器](https://jasirvoriya.github.io/miplay-sender/)**（或打开本地零依赖 [`docs/architecture.html`](docs/architecture.html)），点击任意模块高亮追踪其全部 **↑ 上游来源 / ↓ 下游消费者**。
> - **全仓源码字典**：查阅 **[《架构与全仓源码深度手册》(docs/ARCHITECTURE.md)](docs/ARCHITECTURE.md)**（含 41 个 Android 源码、24 个 Rust 核心模块、19 组 JVM 契约单测与 6 阶段生命周期不变量详表）。

### 1. 跨端三层架构（L3 表现层 · L2 系统层 · FFI 桥接 · L1 Rust 核心）

![MiPlay Sender 跨端三层架构](docs/assets/cross-platform-architecture.svg)

- **L1 公共核心层（Rust `core/`，100% 跨端共享）**：统一承载 mDNS 发现、8899 控制与双认证、WFD RTSP、UDP 时钟主、多箱同步、流媒体封装，以及下沉的 `engine` 多箱并发协调器和 `device_catalog` 路由状态机。
- **FFI 薄桥接 + L2/L3 原生层**：Android 通过 JNI（16KB 页对齐 `.so`）连接 Kotlin，iOS 通过 C11 头文件（`miplay_core.h` / `.xcframework`）连接 Swift；双端共用 `design/`（`tokens.json` + 20 枚统一矢量图标 + `UI_SPEC.md`）。

### 2. 四条正交数据流向（媒体 · 控制 · 时钟同步 · 发现遥测）

![MiPlay Sender 整体架构与数据流向](docs/assets/architecture-overview.svg)

- **青色主音频流**：目标 App → 单 UID `AudioRecord` (48kHz) → `MediaCodec` (256kbps 裸 AAC) → `sync_channel(96)` → `media-clock` (21.333ms 节拍) → 每台接收端独立 `Packetizer` (`ADTS → PES → MPEG-TS → RTP PT=33`) → 音箱反向 Audio TCP。
- **琥珀控制流 & 翡翠时钟流 & 紫色遥测流**：`engine` 串行协调正向 TCP `8899` 控制认证；`UDP TimerServer` 与反向 `SourceRtsp` 汇合于 `OffsetBarrier` 锁定共享 `TIME_OFFSET`；1Hz 统计与 `PcmProbe` 驱动 UI 原位刷新。

### 3. 单进程实时线程模型与 6 条局域网通路

![MiPlay Sender 软件运行时整体架构](docs/assets/runtime-architecture.svg)

- **单进程零 IPC 拷贝**：原生采集线程（`MAX_PRIORITY`）与 Rust 引擎在同一地址空间内通过有界通道解耦；每台接收端拥有独立 5 线程（`media` / `rtsp` / `ctl` / `hb` / `timer`），慢端满队单端剔除，绝不阻塞全组。
- **无死锁停止协议**：停止投送时 `Engine::stop` **先 `drop(feed)` 唤醒 `media-clock`**，再 `join` 全部工作线程并向每台音箱下发 `CLOSE(0x0002)` 释放会话。

---

## 目录结构

```text
miplay-sender/
├── core/               # [L1 公共核心层] 纯 Rust 协议、多箱同步引擎、并发协调器 (engine) 与设备目录 (device_catalog)
│   ├── include/        #   [FFI] iOS / C11 头文件 (miplay_core.h) 与 module.modulemap
│   └── src/            #   24 个核心模块（含 Android JNI ffi.rs 与 iOS C ABI ffi_c.rs）
├── design/             # [L3 跨端共享设计层] 明暗双主题 tokens.json、20 枚 24x24 矢量图标与 UI_SPEC.md
├── android/            # [L2+L3 Android 端] Kotlin 原生客户端 (:app standard/acceptance) 与三 UID 隔离测试音源 (:testtones)
├── cli/                # [工程支撑] 独立终端工具 miplay-cli（discover / 只读 inspect / 投送与故障注入调试）
├── scripts/            # [构建流水线] Android debug/release 构建、iOS XCFramework 打包与 16KB 页对齐/签名验收脚本
└── docs/               # [文档与可视化] 交互式架构浏览器 (architecture.html)、深度手册 (ARCHITECTURE.md)、兼容矩阵与修复日志
```

> 完整的 65+ 源码文件与 19 个 JVM 单测文件职责字典见 **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#三全仓分层模块与文件职责字典按-l3--l2--ffi--l1--工程支撑展开)**。

---

## 快速上手：CLI 诊断 · 构建 · 测试

### 1. 下载或使用 `miplay-cli` 只读诊断局域网音箱

正式版 APK 与三端 `miplay-cli` 二进制可在 **[GitHub Releases](https://github.com/JasirVoriya/miplay-sender/releases)** 直接下载，也可本地运行：

```sh
# 扫描局域网 MiPlay / 小爱音箱（输出 6 列 TSV：IP、名称、型号、MiPlay 能力声明等）
cargo run --release -p miplay-cli -- discover

# 只读查询指定音箱状态（仅发 GET_* 并以 TCP shutdown 退出，不发 CLOSE，不打断正在播放的音乐）
cargo run --release -p miplay-cli -- inspect <音箱IP> --auth legacy
```

### 2. 本地构建（Android APK & iOS XCFramework）

依赖：JDK 17+、Android SDK（`platforms;android-36` / `build-tools;36.0.0` / NDK）、Rust ≥1.75、`aarch64-linux-android`、`cargo-ndk`（构建 iOS 另需 macOS + Xcode）。

```sh
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<version>
rustup target add aarch64-linux-android
cargo install cargo-ndk --locked

# Android Debug 一键构建（串联 cargo test + 16KB 页对齐 .so + Gradle 单测与 APK）：
./scripts/build-debug.sh

# Android Release 正式签名构建（通过 MIPLAY_RELEASE_* 或未跟踪的 android/keystore.properties 注入签名）：
./scripts/build-release.sh

# iOS 静态库与 MiPlayCore.xcframework 打包（macOS + Xcode）：
./scripts/build-ios-xcframework.sh
```

### 3. 运行自动化测试与静态门禁

```sh
# 1. L1 Rust 协议、媒体打包、Engine 协调器与设备目录单测（63 项）
cargo test --workspace

# 2. FFI iOS C ABI 头文件 C11 严格语法校验
clang -std=c11 -Wall -Wextra -Werror -fsyntax-only core/include/miplay_core.h

# 3. L2/L3 Android JVM 契约单测（19 个测试文件）与 NewApi Fatal Lint
cd android && ./gradlew --no-daemon test lintStandardDebug
```

---

## 诚实工程边界与相关文档

本项目坚持**只声明有代码或实机证据支撑的能力**，明确不承诺以下边界：

- **不绕过第三方捕获限制**：显式声明 `allowAudioPlaybackCapture="false"` 的第三方 App 无法捕获。
- **不伪造单 App 本机静音**：未 Root 普通权限下 Android 公开 API 不支持单独静音某一个第三方 App 的本机输出而不影响 `STREAM_MUSIC`，因此提供「全局媒体静音（默认）」与「保留本机出声」两种显式策略。
- **不把编译通过等同于全机型兼容**：各厂商后台冻结（如 HyperOS Greeze）及部分音箱固件差异（如 LX06 晚加入扩组，见 [`docs/LX06_V8_POSTMORTEM.md`](docs/LX06_V8_POSTMORTEM.md)）需按机型实测核验。

📚 **延伸文档索引**：
- [架构与全仓源码深度手册](docs/ARCHITECTURE.md) · [在线交互式架构浏览器](https://jasirvoriya.github.io/miplay-sender/)（[本地 HTML](docs/architecture.html)）
- [音箱与发送端设备兼容矩阵](docs/COMPATIBILITY.md) · [未测限制与已知缺口](docs/LIMITATIONS.md)
- [已合入修复日志](docs/修复日志.md) · [当前需求排期与验收条件](docs/待修复需求.md)

---

## 许可与免责声明

- **开源许可证**：本项目采用 **[GNU Affero General Public License v3.0 (AGPL-3.0-only)](LICENSE)** 开源发布。
- **非官方与互操作性声明**：本项目为独立的第三方开源客户端实现，仅用于局域网设备互操作性与音频投送技术研究。本项目与小米集团（Xiaomi）及其关联公司没有任何隶属、授权、认可或合作关系。文中所提及的产品名称、协议名称或商标均归其各自所有者所有。
- **隐私与边界**：仅捕获用户授权的声音流，不采集屏幕图像或麦克风，不连接任何云端 API，仅在用户本地局域网内按选定目标投送音频。
