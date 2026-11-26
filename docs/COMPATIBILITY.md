# 设备兼容矩阵（Compatibility Matrix）

> **诚实口径声明**：本表仅登记有真实设备或受控验证证据的记录，**编译通过 ≠ 全机型兼容，单箱可用 ≠ 多箱同步 / 静音 / 后台保活全通过**。详细未测缺口请先阅读 [docs/LIMITATIONS.md](LIMITATIONS.md)。

📣 **欢迎共建**：如果你手上有小米 / MiPlay 音箱或不同系统版本的 Android / iOS 发送端设备，无论测试结果是 **可用 ✅** 还是 **异常 ❌**，都欢迎点此提交实测反馈：

👉 **[提交你的设备兼容实测报告（GitHub 表单）](https://github.com/JasirVoriya/cozyting/issues/new)**

---

## 状态图例

| 标识 | 含义 |
| --- | --- |
| ✅ | 已有实测通过记录 |
| ⚠️ | 部分可用 / 存在已知限制（见备注） |
| ❌ | 实测失败或尚未解决 |
| ⏳ | 尚未实测 / 等待社区回报 |

---

## 一、接收端（音箱 / 电视 / MiPlay 设备）

| 接收端型号 / 代号 | 认证模式 | mDNS 发现 | 单箱投送 | 多箱并发 / 晚加入 | 备注与证据来源 |
| --- | --- | :---: | :---: | :---: | --- |
| **小米音箱 `L17A`** | Legacy / Auto | ✅ | ✅ | ⏳ | `0.9.4` MAR-AL00 真机 UI 静音投送验收通过（`streaming`、行内延迟显示、停止后 `inspect` 回读 `state=0`）；多箱双机真实音乐听感待补测 |
| **小爱音箱 Pro (`LX06`)** | Legacy / Safety | ✅ | ⚠️ | ❌ | 已知晚加入扩组 / 反向 RTSP 仍存在协议差异，见 [LX06_V8_POSTMORTEM.md](LX06_V8_POSTMORTEM.md)（**不宣称已修好**） |
| **Xiaomi Sound / Sound Pro** | Auto / Safety | ⏳ | ⏳ | ⏳ | 等待社区实测上报 |
| **小爱音箱 Art / Play 系列** | Auto / Legacy | ⏳ | ⏳ | ⏳ | 等待社区实测上报 |
| **小米电视（声明 MiPlay 能力）** | Auto | ✅ | ⏳ | ⏳ | 路由层已支持作为合法 MiPlay sink 勾选并展示协议徽标；实机音画/声道行为待社区回报 |

---

## 二、发送端（Android / iOS 设备与系统版本）

> **Android 端**：最低要求 **Android 10（API 29）**，当前正式包构建 **`arm64-v8a`**（支持 16KB ELF 页对齐）。  
> **macOS 端**：最低要求 **macOS 14.2（Sonoma）**，基于原生 Core Audio Process Tap（`AudioHardwareCreateProcessTap` + `CATapDescription(.mutedWhenTapped)`）与 `AudioToolbox` 48 kHz 256 kbps AAC-LC 硬件编码，免虚拟声卡；M1–M9（`CozyTingShared` 三子库 + `apple/macOS` 菜单栏悬浮卡/三语/日志/更新器/DMG 打包脚本）均已通过离线编译与单测，真机 TCC 系统音频录制授权、五类音源 DRM 表现与实箱多机听感待人工交互验收后逐项更新。  
> **iOS 端**：L1 Rust C ABI（`ffi_c.rs` / `cozyting_core.h` / `build-ios-xcframework.sh`）与跨端共享设计规范 `design/` 已就绪，L2/L3 原生客户端（`ReplayKit` / `AudioToolbox` / `SwiftUI`）规划推进中。

| 设备型号 / 平台 | 系统 / ROM | 版本 / API | 冷启动与发现 | 投送实测 | 后台保活 / 备注 |
| --- | --- | :---: | :---: | :---: | --- |
| **华为 MAR-AL00 (Android)** | EMUI / HarmonyOS（Android 10） | API 29 | ✅ | ✅ | API 29 冷启动零权限弹窗、模糊降级与 `L17A` 静音投送已核验；`0.9.3`/`0.9.4` 已实测可投送；长时后台/多箱真实音乐待进一步验证 |
| **Xiaomi / Redmi (Android)** | HyperOS / MIUI | API 31–36 | ⏳ | ⏳ | 需注意 HyperOS Greeze 后台冻结机制，电池优化入口不等于全机型永不杀后台 |
| **OnePlus / OPPO / realme (Android)** | ColorOS | API 29–36 | ⏳ | ⏳ | 等待社区实测上报 |
| **vivo / iQOO (Android)** | OriginOS | API 29–36 | ⏳ | ⏳ | 等待社区实测上报 |
| **Samsung / Pixel (Android)** | One UI / AOSP | API 29–36 | ⏳ | ⏳ | 等待社区实测上报（欢迎实测 Android 15/16 16KB 页设备） |
| **Mac (Apple Silicon / macOS)** | macOS Sonoma / Sequoia+ | macOS 14.2+ | ⏳ | ⏳ | `aarch64-apple-darwin` 静态库、`CozyTingAudioKit` 多格式 AAC 单测与 `ProcessTapAudioEngine` 离线编译通过；真机系统音频录制授权（TCC）与实箱投送待人工交互验收 |
| **iPhone / iPad (iOS / iPadOS)** | iOS / iPadOS | iOS 16+（规划） | ⏳ | ⏳ | L1 Rust C ABI / XCFramework 与 `design/` 规范已就绪，SwiftUI 客户端开发中 |

---

## 三、第三方音源 App 说明

### 3.1 Android 端
- 本项目基于 Android 标准 `AudioPlaybackCapture` API 实现**严格单 App UID 捕获**，不采用 Root 或系统级 Hook。
- 若第三方 App 在其清单中显式设置 `android:allowAudioPlaybackCapture="false"`，系统层会返回静音或拒绝捕获，本项目**不会尝试绕过**，也不承诺所有第三方 App 均可捕获。
- 在普通未 Root 权限下，Android 公开 API 不支持单独静音某一第三方 App 的本机输出，因此提供「全局媒体静音（默认）」与「保留本机出声」两种显式策略。

### 3.2 macOS 端（macOS 14.2+ Core Audio Process Tap）
- 基于原生 `AudioHardwareCreateProcessTap` + `CATapDescription(.mutedWhenTapped)` 实现**严格单 App 进程树隔离**（聚合主进程与 Chromium / Electron `Helper (Audio)` 子进程）与**真·单 App 本机静音**，免虚拟声卡，不绕过 FairPlay / Widevine DRM。
- 以下五类核心音源的真机捕获、单 App 静音与 DRM 表现按诚实边界逐项登记（自动化无人值守环境无法交互授予 macOS 系统音频录制 TCC 弹窗，且网易云音乐 / Spotify 未安装于当前构建机，均保持 `⏳` 待人工交互实测后更新）：

| 音源 App（macOS） | 单 App 进程树捕获 | 单 App 本机静音 | DRM / 边界说明 |
| --- | :---: | :---: | --- |
| **网易云音乐** | ⏳ | ⏳ | 待装机并完成 TCC 授权后实测（Electron/Chromium 子进程树聚合逻辑已实现） |
| **QQ 音乐** | ⏳ | ⏳ | 待完成 TCC 授权与实箱播放后实测 |
| **Spotify** | ⏳ | ⏳ | 待装机并完成 TCC 授权后实测（同步验证 AppleScript 元数据） |
| **Apple Music（Music.app）** | ⏳ | ⏳ | 受 FairPlay DRM 保护流可能返回静音缓冲（不绕过，待 TCC 授权后实测本地曲库与流媒体差异） |
| **Chrome / Safari 网页音频** | ⏳ | ⏳ | 待完成 TCC 授权后实测（普通 HTML5 音视频与 Widevine/FairPlay 受保护流将分别记录） |

---

## 四、如何快速采集只读设备信息？

如果你希望帮助适配一款新的小米音箱，可在同一局域网的电脑上使用 `cozyting-cli` 执行**只读查询**（仅发 `GET_*` 并以 TCP shutdown 退出，不会打断音箱当前状态）：

```bash
cozyting-cli discover
cozyting-cli inspect <音箱IP> --auth legacy
```

将脱敏后的输出附在 [设备兼容实测报告](https://github.com/JasirVoriya/cozyting/issues/new) 中即可。
