# Android 免投屏感音频采集引擎设计方案（Shizuku 后端 · 通吃全 App 单机方案）

> **状态：待实现 · 架构参考方案（Proposed / Not Implemented）**
> 本文档沉淀「消除每次投屏授权弹窗」与「消除系统常驻『正在投屏』提示」的技术调研结论与后续开发参考蓝图。所有未在真机实测验证的 ROM 兼容行为（状态栏隐私绿点、隐藏 API 签名差异、单 App 环回静音、DRM 音源边界）均须按第七节验收清单实测后，方可写入 [COMPATIBILITY.md](COMPATIBILITY.md)。

---

## 一、问题背景与候选方案调研结论

### 1. 当前痛点与根因
当前 Android 端采用普通应用权限下的 `MediaProjection` + `AudioPlaybackCaptureConfiguration.addMatchingUid(uid)`（见 `android/app/src/main/java/com/miplay/sender/capture/PlaybackCaptureFactory.kt`）。虽然严格做到了单 App 捕获且不采集画面，但由于 Android 框架将 `AudioPlaybackCapture` 强绑定在 `MediaProjection`（屏幕录制/投屏体系）上，存在两项系统级硬限制：
1. **每次会话必须重新弹窗授权**：Android 14+ 硬性禁止跨会话复用 `MediaProjection` 授权 Token（见 `ProjectionSessionPolicy.kt`）；
2. **全程常驻「正在投屏」系统提示**：只要 `MediaProjection` 处于活跃状态，SystemUI 即强制展示投屏/录屏图标或状态栏胶囊，普通第三方权限无法隐藏。

### 2. 为什么不能通过 `MediaSession` 顺带拿到音频？
当前主界面已通过 `NotificationListenerService` + `MediaSessionManager.getActiveSessions()`（见 `NowPlayingTracker.kt`）获取封面、歌名、进度并转发播控指令，但**无法借此获取音频流**：
- **控制面与数据面物理隔离**：`MediaSession` / `MediaController` 仅属于控制面（Metadata + PlaybackState + TransportControls），不暴露 PCM 缓冲区、不暴露 `audioSessionId`，主流音乐 App 亦不暴露可直接拉取的无加密媒体 URL；
- **`Visualizer(0)` 不可用于听歌**：`android.media.audiofx.Visualizer` 虽无需投屏权限，但仅输出 8-bit 无符号单声道、最高约 20Hz 的间歇采样快照（仅用于频谱可视化），非连续 48kHz 16-bit 立体声 PCM。

### 3. 候选方案综合评估（在「通吃所有 App + 纯手机单机 + 免 Root」约束下）

| 候选路线 | 定位与结论 | 弹窗 | 「正在投屏」提示 | 核心原因与取舍 |
| --- | --- | :---: | :---: | --- |
| **Shizuku 底层音频采集后端（本方案）** | ✅ **主力演进路线** | **无** | **无**（Android 11+） | 借助 `com.android.shell`（`uid 2000`）原生系统权限绕开 `MediaProjection`，参考 `scrcpy 2.0+` 机制，纯手机无线调试即可激活，通吃所有 App |
| **现有 `MediaProjection` + `AudioPlaybackCapture`** | ✅ **默认零依赖兜底** | 有 | 有 | 无需额外工具、兼容 Android 10–16 与所有标准环境，保持默认可用 |
| **虚拟 DLNA / QPlay 音频渲染器（DMR）** | 📌 **远期补充参考** | **无** | **无** | 天然单 App 隔离且音质无二次重采，但仅支持自带 DLNA/QPlay 的音乐 App，不满足「通吃所有 App」 |
| **局域网多协议网桥（AirPlay 2 / DLNA / 蓝牙）** | 📌 **远期保留方案** | **无** | **无** | 见 [AIRPLAY_BRIDGE_DESIGN.md](AIRPLAY_BRIDGE_DESIGN.md)，依赖局域网常驻主机，不满足「纯手机单机」 |
| **无障碍服务自动点击授权弹窗** | ❌ **已排除** | 闪现 | **仍有** | 无法消除「正在投屏」提示，Android 14+ 安全窗口易拦截，且无障碍权限过重 |
| **Root / LSPosed Hook SystemUI** | ❌ **不作为主线** | **无** | **无** | 违背免 Root 普通用户门槛，仅作为高权限环境理论对照 |

---

## 二、分 Android 版本能力分级矩阵（诚实红线）

Shizuku 启动的 `UserService` 运行在 `com.android.shell`（`uid 2000`）上下文中。由于 AOSP 在不同大版本逐步向 `com.android.shell` 授予音频特权，实际能力严格按系统版本分层：

| Android 版本 | `shell` (`uid 2000`) 关键权限 | 免投屏弹窗 | 免「正在投屏」提示 | 音频捕获范围 | 本机静音能力 | 激活方式（免 Root） |
| --- | --- | :---: | :---: | --- | --- | --- |
| **Android 13–16 (API 33–36)** | `CAPTURE_AUDIO_OUTPUT` + `MODIFY_AUDIO_ROUTING` | ✅ **是** | ✅ **是** | **单 App UID 隔离** 或 **显式全局媒体**（支持无缝热切换） | ✅ **真正单 App 本机静音**（`ROUTE_FLAG_LOOP_BACK`）或保留本机出声 | 手机本机「无线调试」一键激活（无需电脑） |
| **Android 11–12 (API 30–32)** | `CAPTURE_AUDIO_OUTPUT` | ✅ **是** | ✅ **是** | **仅整机混音**（`REMOTE_SUBMIX`，不支持单 UID） | ✅ 整机原生静音（`REMOTE_SUBMIX` 特性） | 手机本机「无线调试」一键激活（Android 11 首次启动需短暂前台壳） |
| **Android 10 (API 29)** | 无 `CAPTURE_AUDIO_OUTPUT` | ⚠️ 可通过 `appops` 跳过 | ❌ **无法消除** | 保持现有 `MediaProjection` 单 UID | 保持现有 [LOCAL_MUTE.md](LOCAL_MUTE.md) 策略 | 需 USB ADB 执行 `appops set <pkg> PROJECT_MEDIA allow` |

> **Android 13+ 带来的额外收益**：
> 相比 [LOCAL_MUTE.md](LOCAL_MUTE.md) 中普通权限只能静音整条 `STREAM_MUSIC` 流（导致投歌期间刷短视频、听微信语音一同静音），Android 13+ 的 `AudioMix(ROUTE_FLAG_LOOP_BACK)` + `RULE_MATCH_UID` 可以**只抽走目标音乐 App 的音频流**送往音箱，手机扬声器仅对该音乐 App 静音，其他 App 照常从手机发声且不会串入音箱。

---

## 三、L2 可插拔采集架构（对 L1 Rust 核心零侵入）

本方案完全落在 L2 Android 采集层，**不改动 L1 共享 Rust 核心（`core/`）、JNI 接口（`core/src/ffi.rs`）与 iOS C ABI（`core/src/ffi_c.rs` / `core/include/miplay_core.h`）**。多音箱毫秒级同频、双认证、15 秒看门狗与 `AacEncoder` 全部原样复用。

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│ 主 App 进程：CastService（持有 AacEncoder、L1 Rust Engine、通知与锁）         │
│                                                                              │
│   AudioCaptureBackend（统一抽象接口：输出 48kHz · 16-bit · Stereo PCM）       │
│     ├── [首选] ShizukuPolicyBackend    (API 33+, AudioPolicy + AudioMix)     │
│     ├── [可选] ShizukuSubmixBackend    (API 30-32, REMOTE_SUBMIX 整机显式)   │
│     └── [兜底] MediaProjectionBackend  (API 29+, 现有标准方案 + 可选 appops) │
│                                                                              │
│         ▲ 原始 PCM 流（ParcelFileDescriptor 管道 / LocalSocket，4096B/帧）    │
│         │ 严禁走 Binder byte[] IPC，避免 1MB 事务上限与高拷贝抖动             │
├─────────┼────────────────────────────────────────────────────────────────────┤
│ Shizuku UserService 独立进程：ShizukuCaptureService（uid 2000 · shell 上下文）│
│                                                                              │
│   1. HiddenApiBypass 放行 android.media.audiopolicy 隐藏类访问               │
│   2. 构造 AudioMixingRule → AudioMix → AudioPolicy 并向 AudioManager 注册     │
│   3. AudioPolicy.createAudioRecordSink(audioMix) → 实时循环 read() 写入管道   │
│   4. 监听主进程 Binder linkToDeath + 管道 EPIPE，异常时立即注销 AudioPolicy   │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 1. 为什么将编码与推流留在主 App 进程？
- `ShizukuCaptureService` 只承担极简的「注册音频策略 + `AudioRecord.read()` + 写 `ParcelFileDescriptor` 管道」职责，**不加载 Rust `.so`、不建网络连接、不管理音箱状态**；
- 音频帧契约与现有管线完全一致：`48,000 Hz` / `PCM_16BIT` / `CHANNEL_IN_STEREO`，每次读取 `1024 samples = 4096 bytes`（`21.333ms`），直接喂给现有 `AacEncoder` → `NativeBridge.pushAac()`，复用现有 `PcmProbe` 有声检测。

### 2. 待新增的 Android 侧模块规划（不破坏现有分层）
```text
android/app/src/main/java/com/miplay/sender/
├── capture/
│   ├── AudioCaptureBackend.kt           # 统一采集后端接口与状态/能力声明
│   ├── MediaProjectionBackend.kt        # 从 CastService 抽离的现有标准后端（零行为回归）
│   └── shizuku/
│       ├── ShizukuAvailability.kt       # 检测 Shizuku 安装/运行/授权状态与系统版本分级
│       ├── ShizukuCaptureService.kt     # 运行于 uid 2000 的 UserService（AudioPolicy / Submix）
│       ├── ShizukuPolicyBackend.kt      # API 33+ 动态路由后端（单 UID / 全局媒体 / 热切换）
│       ├── ShizukuSubmixBackend.kt      # API 30-32 REMOTE_SUBMIX 显式整机后端
│       ├── AudioPolicyReflector.kt      # 封装 HiddenApiBypass 与多 ROM 签名兼容反射
│       └── IShizukuCaptureService.aidl  # 控制面 AIDL（start / updateUid / stop / 传递 Pipe FD）
└── compat/
    └── ShizukuCompat.kt                 # 隔离 API 30/33 检查，确保 NewApi Fatal Lint 零告警
```

---

## 四、核心行为契约：捕获模式、热切源与防卡死清理协议

### 1. 严格守住 Fail-Closed 红线（绝不静默降级）
- **单 App 隔离模式（默认，API 33+）**：
  - 启动前仍通过现有 `CaptureTargetResolver` 实时校验目标包同用户、非共享 UID 与 SHA-256 签名；
  - `AudioMixingRule` 强制添加 `RULE_MATCH_UID` + `USAGE_MEDIA` / `USAGE_GAME` / `USAGE_UNKNOWN`；
  - 若反射注册失败或目标身份失效，**必须直接报错停止并提示用户切换模式，严禁静默退化为整机捕获**。
- **全局媒体模式（可选显式开关）**：
  - 仅在用户主动选择「捕获全部媒体（不指定单个 App）」时启用（API 33+ 通过仅匹配媒体 `USAGE` 的 `AudioMixingRule` 实现；API 30–32 通过 `REMOTE_SUBMIX` 实现）；
  - 主界面音源卡与通知栏必须明确标注「全局媒体模式（Shizuku）」，让用户清晰知晓当前所有媒体发声都会被投送。

### 2. 本机静音策略映射（API 33+）
- **单 App 本机静音（推荐默认）**：`AudioMix.Builder.setRouteFlags(ROUTE_FLAG_LOOP_BACK)` —— 仅环回采集，不路由至手机物理扬声器/耳机，**不再需要修改系统全局 `STREAM_MUSIC` 静音状态**；
- **保留本机出声**：`AudioMix.Builder.setRouteFlags(ROUTE_FLAG_LOOP_BACK_RENDER)` —— 环回采集的同时继续向本机扬声器输出（需按机型实测验证 `LOOP_BACK_RENDER` 可用性）。

### 3. 切源热更新（零弹窗切换音乐 App）
在 Android 13+ `ShizukuPolicyBackend` 运行期间，若用户点击切换另一个音乐 App（或跟随 `NowPlayingTracker` 切换）：
1. 主进程校验新目标包签名与 UID；
2. 通过 AIDL 调用 `updateTargetUid(newUid)`；
3. `ShizukuCaptureService` 在短暂加锁内注销旧 `AudioPolicy`、按新 UID 注册新 `AudioPolicy` 并重建 `AudioRecord`，**保持现有 L1 `CastSession` 与音箱连接不断流、不弹窗**。

### 4. 三重防卡死清理协议（极关键安全不变量）
> [!CAUTION]
> 当使用 `ROUTE_FLAG_LOOP_BACK` 接管音频路由时，若采集进程异常退出而未调用 `unregisterAudioPolicy()`，目标 App 可能会持续无声直到系统回收策略。因此**必须实现三重兜底清理**：

1. **正常停止顺序**：`AudioRecord.stop()` → `AudioRecord.release()` → `AudioManager.unregisterAudioPolicy(policy)` → 关闭 `ParcelFileDescriptor` → 主进程 `Shizuku.unbindUserService()`；
2. **主进程死亡监听（`IBinder.DeathRecipient`）**：主进程启动时传递一个 Binder Token 给 `ShizukuCaptureService`，后者立即 `linkToDeath()`；一旦主 App 崩溃或被系统强杀，子进程在 `binderDied()` 回调中立刻执行 `unregisterAudioPolicy()` 并 `exitProcess(0)`；
3. **管道断流自保**：读取线程写入 `ParcelFileDescriptor` 遇到 `IOException` / `EPIPE` 或连续阻塞超过阈值（如 2 秒）时，自动中止采集并释放 `AudioPolicy`。

---

## 五、与工程红线及跨端规范的对齐要求

1. **API 29 (`minSdk = 29`) 与 `NewApi` Fatal Lint**：
   - Shizuku 与 `AudioPolicy` 相关逻辑必须按 `Build.VERSION.SDK_INT` 隔离，保证 `android/gradlew test lintStandardDebug` 零告警通过；
   - 不影响未安装 Shizuku 的 Android 10–16 设备正常使用默认 `MediaProjection` 模式。
2. **16KB ELF 页对齐与零云端**：
   - 引入 `dev.rikka.shizuku:api`、`dev.rikka.shizuku:provider` 与 `org.lsposed.hiddenapibypass:hiddenapibypass` 时，核验其产物不包含非 16KB 页对齐的 `arm64-v8a` `.so`，且无任何云端通信。
3. **跨端设计规范（`design/UI_SPEC.md`）同步**：
   - 进入编码阶段时，须同步在 [design/UI_SPEC.md](../design/UI_SPEC.md) 的设置页规范中补充 **「采集引擎（Android 专属）」** 卡片说明（选项：`标准投屏模式` / `Shizuku 无感模式`、Shizuku 就绪状态徽章、单 App vs 全局媒体模式说明），并明确标注 iOS 端受沙箱模型限制不展示此设置项。

---

## 六、诚实工程边界与已知风险

1. **状态栏「录音小绿点」隐私指示**：脱离 `MediaProjection` 后，「正在投屏」横幅、状态栏投屏图标与系统投屏通知会**彻底消失**；但在 Android 12+ 上，部分 ROM 在 `shell` 进程打开 `AudioRecord` 时右上角仍可能亮起极小的绿色录音隐私圆点，具体表现因机型而异，须实机验证。
2. **国产定制 ROM 反射兼容风险**：`AudioMix` / `AudioPolicy` / `AudioMixingRule` 属 `@SystemApi` 隐藏类，部分定制 ROM（HyperOS / HarmonyOS / ColorOS / OriginOS）可能变更内部构造签名或限制 `shell` 注册动态路由策略；须参考 `scrcpy` 的多版本反射适配，并在失败时清晰回退提示。
3. **手机重启后需重新激活 Shizuku**：Android 系统重启后会自动关闭无线调试，需在手机上重新启动一次 Shizuku（约 3~5 秒，纯手机完成、无需电脑）；不重启则长期有效。
4. **Android 10 硬边界**：Android 10 上 `com.android.shell` 不具备 `CAPTURE_AUDIO_OUTPUT`，因此无法摆脱 `MediaProjection`；仅能通过 `appops set <pkg> PROJECT_MEDIA allow` 省略重复确认弹窗，无法消除「正在投屏」提示。
5. **拒捕 App 与 DRM 音源**：虽然 `shell` 级 `AudioPolicy` 理论上比普通 `AudioPlaybackCapture` 覆盖面更广，但硬件 DRM / 特殊保护音频流仍可能输出静音，具体支持清单以真机实测为准，不提前承诺。

---

## 七、分阶段实施与真机验收清单（未来启动开发时执行）

### 阶段 0：最小真机探针（POC，优先排雷，不改主流程 UI）
在正式重构 `CastService` 前，先用最小独立探针在目标 Android 13+ 真机上完成 **6 项硬核实测**：
1. **权限与反射探通**：通过无线调试激活 Shizuku，验证 `ShizukuCaptureService`（`uid 2000`）能否成功反射构造并注册 `AudioPolicy` + 创建 `AudioRecord`；
2. **PCM 有效性与延迟验证**：播放任意音乐 App，验证管道持续产出非零 48kHz 立体声 PCM 且通过 `PcmProbe`，喂入现有 `AacEncoder` + L1 引擎向音箱推流无爆音/欠载；
3. **系统 UI 观感实测**：确认全程**零投屏授权弹窗、零「正在投屏」提示**，并如实记录右上角是否出现录音隐私绿点；
4. **单 App 真静音与不串音验证**：以 `ROUTE_FLAG_LOOP_BACK` + `RULE_MATCH_UID` 投送音乐 App，同时打开另一视频/语音 App，确认音乐仅从音箱出声、另一 App 仅从手机出声且不串入音箱；
5. **异常防卡死验收**：投送中强杀主 App 进程（`am force-stop`），确认 `DeathRecipient` 触发 `unregisterAudioPolicy()`，目标音乐 App 在手机上立即恢复正常出声；
6. **长稳与切源验收**：锁屏连续投送 ≥30 分钟无断流/内存增长；运行期热切换另一个音乐 App 确认零弹窗无缝切换。

### 阶段 1：正式集成可插拔后端与设置页
1. 抽离 `AudioCaptureBackend` 接口，将现有 `MediaProjection` 代码无回归迁入 `MediaProjectionBackend`；
2. 落地 `ShizukuPolicyBackend`（API 33+）、`ShizukuSubmixBackend`（API 30–32 可选）与 Android 10 `appops` 辅助引导；
3. 同步更新 [design/UI_SPEC.md](../design/UI_SPEC.md) 与设置页 UI，补充采集引擎状态机与 Fail-Closed 契约 JVM 单测，跑通 `cargo test --workspace`、`clang -std=c11` 头文件检查与 `./gradlew test lintStandardDebug`。

### 阶段 2：真机兼容矩阵与文档同步
1. 将各实测机型与 ROM 的真实结果（成功项、绿点表现、反射差异、DRM 音源情况）如实登记至 [COMPATIBILITY.md](COMPATIBILITY.md)、[LIMITATIONS.md](LIMITATIONS.md) 与 [LOCAL_MUTE.md](LOCAL_MUTE.md)。
