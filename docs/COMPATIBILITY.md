# 设备兼容矩阵（Compatibility Matrix）

> **诚实口径声明**：本表仅登记有真实设备或受控验证证据的记录，**编译通过 ≠ 全机型兼容，单箱可用 ≠ 多箱同步 / 静音 / 后台保活全通过**。详细未测缺口请先阅读 [docs/LIMITATIONS.md](LIMITATIONS.md)。

📣 **欢迎共建**：如果你手上有小米 / MiPlay 音箱或不同系统版本的 Android / iOS 发送端设备，无论测试结果是 **可用 ✅** 还是 **异常 ❌**，都欢迎点此提交实测反馈：

👉 **[提交你的设备兼容实测报告（GitHub 表单）](https://github.com/JasirVoriya/miplay-sender/issues/new?template=device_compatibility.yml)**

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
> **iOS 端**：L1 Rust C ABI（`ffi_c.rs` / `miplay_core.h` / `build-ios-xcframework.sh`）与跨端共享设计规范 `design/` 已就绪，L2/L3 原生客户端（`ReplayKit` / `AudioToolbox` / `SwiftUI`）规划推进中。

| 设备型号 / 平台 | 系统 / ROM | 版本 / API | 冷启动与发现 | 投送实测 | 后台保活 / 备注 |
| --- | --- | :---: | :---: | :---: | --- |
| **华为 MAR-AL00 (Android)** | EMUI / HarmonyOS（Android 10） | API 29 | ✅ | ✅ | API 29 冷启动零权限弹窗、模糊降级与 `L17A` 静音投送已核验；`0.9.3` 用户确认可投送；长时后台/多箱真实音乐待进一步验证 |
| **Xiaomi / Redmi (Android)** | HyperOS / MIUI | API 31–36 | ⏳ | ⏳ | 需注意 HyperOS Greeze 后台冻结机制，电池优化入口不等于全机型永不杀后台 |
| **OnePlus / OPPO / realme (Android)** | ColorOS | API 29–36 | ⏳ | ⏳ | 等待社区实测上报 |
| **vivo / iQOO (Android)** | OriginOS | API 29–36 | ⏳ | ⏳ | 等待社区实测上报 |
| **Samsung / Pixel (Android)** | One UI / AOSP | API 29–36 | ⏳ | ⏳ | 等待社区实测上报（欢迎实测 Android 15/16 16KB 页设备） |
| **iPhone / iPad (iOS / iPadOS)** | iOS / iPadOS | iOS 16+（规划） | ⏳ | ⏳ | L1 Rust C ABI / XCFramework 与 `design/` 规范已就绪，SwiftUI 客户端开发中 |

---

## 三、第三方音源 App 说明

- 本项目基于 Android 标准 `AudioPlaybackCapture` API 实现**严格单 App UID 捕获**，不采用 Root 或系统级 Hook。
- 若第三方 App 在其清单中显式设置 `android:allowAudioPlaybackCapture="false"`，系统层会返回静音或拒绝捕获，本项目**不会尝试绕过**，也不承诺所有第三方 App 均可捕获。
- 在普通未 Root 权限下，Android 公开 API 不支持单独静音某一第三方 App 的本机输出，因此提供「全局媒体静音（默认）」与「保留本机出声」两种显式策略。

---

## 四、如何快速采集只读设备信息？

如果你希望帮助适配一款新的小米音箱，可在同一局域网的电脑上使用 `miplay-cli` 执行**只读查询**（仅发 `GET_*` 并以 TCP shutdown 退出，不会打断音箱当前状态）：

```bash
miplay-cli discover
miplay-cli inspect <音箱IP> --auth legacy
```

将脱敏后的输出附在 [设备兼容实测报告](https://github.com/JasirVoriya/miplay-sender/issues/new?template=device_compatibility.yml) 中即可。
