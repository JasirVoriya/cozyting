# Android 免投屏感音频采集引擎设计方案（内置无线调试直连 · 零外部 App 依赖）

> **状态：待实现 · 架构参考方案（Proposed / Not Implemented）**
> 本文档沉淀「消除每次投屏授权弹窗」、「消除系统常驻『正在投屏』提示」与「Android 13+ 真单 App 本机静音」的技术调研结论与后续开发蓝图。采用**单 App 内置无线调试直连**路线（不要求用户安装 Shizuku 等第三方提权工具）。所有未在真机实测验证的 ROM 兼容行为（状态栏隐私绿点、隐藏 API 签名差异、单 App 环回静音、无线调试配对交互、DRM 音源边界）均须按第八节验收清单实测后，方可写入 [COMPATIBILITY.md](COMPATIBILITY.md)。

---

## 一、问题背景与候选方案调研结论

### 1. 当前痛点与根因
当前 Android 端采用普通应用权限下的 `MediaProjection` + `AudioPlaybackCaptureConfiguration.addMatchingUid(uid)`（见 `android/app/src/main/java/com/cozyting/app/capture/PlaybackCaptureFactory.kt`）。虽然严格做到了单 App 捕获且不采集画面，但由于 Android 框架将 `AudioPlaybackCapture` 强绑定在 `MediaProjection`（屏幕录制/投屏体系）上，存在三项系统级硬限制：
1. **每次会话必须重新弹窗授权**：Android 14+ 硬性禁止跨会话复用 `MediaProjection` 授权 Token（见 `ProjectionSessionPolicy.kt`）；
2. **全程常驻「正在投屏」系统提示**：只要 `MediaProjection` 处于活跃状态，SystemUI 即强制展示投屏/录屏图标或状态栏胶囊，普通第三方权限无法隐藏；
3. **无法实现真正的单 App 本机静音**：普通权限只能全局静音 `STREAM_MUSIC`（见 [LOCAL_MUTE.md](LOCAL_MUTE.md)），导致投歌期间在手机刷短视频或听微信语音也一同无声。

### 2. 为什么不能通过 `MediaSession` 顺带拿到音频？
当前主界面与后台热切源已通过 `NotificationListenerService` + `MediaSessionManager.getActiveSessions()`（见 `NowPlayingTracker.kt`）获取封面、歌名、进度、播控与自动切源信号，但**无法借此获取音频流**：
- **控制面与数据面物理隔离**：`MediaSession` / `MediaController` 仅属于控制面（Metadata + PlaybackState + TransportControls），不暴露 PCM 缓冲区或 `audioSessionId`，主流音乐 App 亦不暴露可直接拉取的无加密媒体 URL；
- **`Visualizer(0)` 不可用于听歌**：`android.media.audiofx.Visualizer` 仅输出 8-bit 无符号单声道、最高约 20Hz 的间歇采样快照，非连续 48kHz 16-bit 立体声 PCM。

### 3. 候选方案综合评估（在「通吃所有 App + 纯手机单机 + 免 Root + 零外部依赖」约束下）

| 候选路线 | 定位与结论 | 弹窗 | 「正在投屏」提示 | 核心原因与取舍 |
| --- | --- | :---: | :---: | --- |
| **内置无线调试直连 `app_process` 音频采集（本方案）** | ✅ **主力演进路线** | **无** | **无**（Android 11+） | App 内置轻量无线 ADB 客户端（mDNS + SPAKE2/TLS 1.3），通过无线调试直接以 `com.android.shell`（`uid 2000`）拉起本应用内置音频采集 Helper，**用户只装一个 App、无需额外安装 Shizuku**；且 MiPlay 本身为纯局域网 Wi-Fi 场景，天然满足无线调试联网前提 |
| **现有 `MediaProjection` + `AudioPlaybackCapture`** | ✅ **默认开箱即用兜底** | 有 | 有 | 无需开启开发者选项、兼容 Android 10–16 与所有标准环境，保持默认可用 |
| **外部 Shizuku 框架托管 `UserService`** | ❌ **不作为主线引入** | **无** | **无**（Android 11+） | 底层同样是无线调试拉起 `uid 2000` 进程，但强依赖用户额外安装配置第二个 App，且与内置无线调试并存会导致两套 IPC/引导/依赖双倍维护，收益不匹配 |
| **虚拟 DLNA / QPlay 音频渲染器（DMR）** | 📌 **远期补充参考** | **无** | **无** | 天然单 App 隔离且无二次重采，但仅支持自带 DLNA/QPlay 的音乐 App，不满足「通吃所有 App」 |
| **局域网多协议网桥（AirPlay 2 / DLNA）** | 📌 **远期保留方案** | **无** | **无** | 见 [AIRPLAY_BRIDGE_DESIGN.md](AIRPLAY_BRIDGE_DESIGN.md)，依赖局域网常驻主机，不满足「纯手机单机」 |
| **无障碍服务自动点击授权弹窗** | ❌ **已排除** | 闪现 | **仍有** | 无法消除「正在投屏」提示，Android 14+ 安全窗口易拦截，且无障碍权限过重 |
| **Root / LSPosed Hook SystemUI** | ❌ **不作为主线** | **无** | **无** | 违背免 Root 普通用户门槛，仅作为高权限环境理论对照 |

---

## 二、分 Android 版本能力分级矩阵（诚实红线）

通过无线调试启动的内置 Helper 进程运行在 `com.android.shell`（`uid 2000`）上下文中。由于 AOSP 在不同大版本逐步向 `com.android.shell` 授予音频特权，实际能力严格按系统版本分层：

| Android 版本 | `shell` (`uid 2000`) 关键权限 | 免投屏弹窗 | 免「正在投屏」提示 | 音频捕获范围 | 本机静音能力 | 激活方式（免 Root · 零外部 App） |
| --- | --- | :---: | :---: | --- | --- | --- |
| **Android 13–16 (API 33–36)** | `CAPTURE_AUDIO_OUTPUT` + `MODIFY_AUDIO_ROUTING` | ✅ **是** | ✅ **是** | **单 App UID 隔离** 或 **显式全局媒体**（支持零弹窗热切源） | ✅ **真正单 App 本机静音**（`ROUTE_FLAG_LOOP_BACK`）或保留本机出声 | App 内引导开启「无线调试」，首次输 6 位配对码即可；后续同机静默重连 |
| **Android 11–12 (API 30–32)** | `CAPTURE_AUDIO_OUTPUT` | ✅ **是** | ✅ **是** | **仅整机混音**（`REMOTE_SUBMIX`，不支持单 UID） | ✅ 整机原生静音（`REMOTE_SUBMIX` 特性） | 同上（纯单 App 内置无线调试） |
| **Android 10 (API 29)** | 无 `CAPTURE_AUDIO_OUTPUT` | ❌ 标准模式仍需授权 | ❌ **无法消除** | 保持现有 `MediaProjection` 单 UID | 保持现有 [LOCAL_MUTE.md](LOCAL_MUTE.md) 策略 | 不支持原生无线调试与 shell 抓音，自动沿用标准模式 |

> **Android 13+ 带来的体验质变**：
> 相比普通权限只能静音整条 `STREAM_MUSIC` 流，Android 13+ 的 `AudioMix(ROUTE_FLAG_LOOP_BACK)` + `RULE_MATCH_UID` 可以**只抽走目标音乐 App 的音频流**送往音箱，手机扬声器仅对该音乐 App 静音，其他 App（短视频、微信语音、播客）照常从手机发声且不会串入音箱。

---

## 三、内置无线调试点火机制（纯 Kotlin/Java · 零额外 `.so`）

为同时守住 **16KB ELF 页对齐** 与极简包体，内置无线 ADB 客户端采用纯 Kotlin/Java 实现（或接入纯 JVM/Android 合规开源无线 ADB 库，配合 Android 系统内置 `Conscrypt` / TLS 1.3），**不引入任何第三方 C/C++ `.so`**。

### 1. 首次配对与日常静默重连流程
1. **本机服务发现（`NsdManager`）**：
   - 配对期监听 `_adb-tls-pairing._tcp.`（获取本机临时配对端口）；
   - 连接期监听 `_adb-tls-connect._tcp.`（获取本机当前无线调试连接端口）。
2. **首次 6 位配对码握手（一次性）**：
   - 5 步配置向导页引导开启「悬浮窗权限（`SYSTEM_ALERT_WINDOW`）」与「开发者选项 → 无线调试」，并在系统设置上方挂出 `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE` 的 Style B 顶部可拖动浮动数字键盘（`PairingFloatingOverlay`）；
   - 用户点击系统「使用配对码配对设备」后，mDNS 探测到配对端口即自动展开 6 位数字槽与 2 行紧凑浮动数字键盘（不抢系统配对弹窗焦点、不拉起系统软键盘、不依赖下拉通知栏），点按 6 位数字即自动完成配对；
   - App 使用 **SPAKE2 + TLS 1.3** 与本机 `adbd` 完成双向证书交换，并将生成的客户端私钥/证书持久化在 App 私有目录（不落明文日志、不云端备份，`android:allowBackup="false"`）。
3. **日常自动点火（无需再次输码）**：
   - 后续只要无线调试处于开启态，App 通过 `NsdManager` 找到 `_adb-tls-connect._tcp.` 端口，出示已保存证书建立 TLS ADB 连接；
   - 执行单条 `shell` 命令拉起内置采集 Helper：
     ```sh
     CLASSPATH=<our_apk_path> setsid /system/bin/app_process /system/bin \
       com.cozyting.app.capture.shell.ShellAudioHelperMain \
       --ctl-port <ephemeral_loopback_port> --pcm-port <ephemeral_loopback_port> \
       --token <random_256bit_hex> </dev/null >/dev/null 2>&1 &
     ```
   - Helper 启动就绪并连回主 App 的本机 `127.0.0.1` 临时环回端口后，主进程立即关闭监听端口；ADB 仅作为按需点火通道，音频流与播控全走 `127.0.0.1` 环回套接字（规避 Android 11–17 SELinux MLS 对 `u:r:shell:s0` 连接 `u:r:untrusted_app:s0:c...` 抽象 `LocalSocket` 的拦截），不占 ADB 会话。
   - **开发期附带收益**：由于 `ShellAudioHelperMain` 是标准 `app_process` 入口，开发验证阶段可直接经 `adb shell` 单测采集核心，与无线调试配对层完全解耦、无需在 App 内做任何 USB 产品入口。

---

## 四、L2 可插拔采集架构（对 L1 Rust 核心零侵入）

本方案完全落在 L2 Android 采集层，**不改动 L1 共享 Rust 核心（`core/`）、JNI 接口（`core/src/ffi.rs`）与 iOS C ABI（`core/src/ffi_c.rs` / `core/include/cozyting_core.h`）**。多音箱毫秒级同频、双认证、15 秒看门狗、`PcmProbe`、`AacEncoder` 与 `CastService` 热切源状态机全部原样复用。

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│ 主 App 进程：CastService（无感模式下 FGS 仅用 mediaPlayback，不触碰投屏）     │
│                                                                              │
│   WirelessAdbBootstrap（按需点火：NsdManager + SPAKE2/TLS 1.3 → app_process） │
│                                                                              │
│   AudioCaptureBackend（统一抽象接口：输出 48kHz · 16-bit · Stereo PCM）       │
│     ├── [首选] ShellPolicyBackend      (API 33+, AudioPolicy + AudioMix)     │
│     ├── [可选] ShellSubmixBackend      (API 30-32, REMOTE_SUBMIX 显式整机)   │
│     └── [兜底] MediaProjectionBackend  (API 29+, 从 CastService 抽离现逻辑)   │
│                                                                              │
│         ▲ 双向 LocalSocket（抽象命名空间 + 每会话 256-bit 随机 Token 鉴权）    │
│         │  • 控制流：短 JSON 行（startSingleUid / updateUid / stop / ping）   │
│         │  • 数据流：原始 PCM 帧（4096 bytes = 1024 samples = 21.333ms）      │
├─────────┼────────────────────────────────────────────────────────────────────┤
│ 内置 Helper 独立进程：ShellAudioHelperMain（uid 2000 · com.android.shell）    │
│                                                                              │
│   1. 校验主进程随机 Token，拒绝任何未授权本机进程接入                         │
│   2. HiddenApiBypass 放行 android.media.audiopolicy 隐藏类访问               │
│   3. 构造 AudioMixingRule → AudioMix → AudioPolicy 并向 AudioManager 注册     │
│   4. AudioPolicy.createAudioRecordSink(audioMix) → 循环 read() 写入数据流    │
│   5. 监听 LocalSocket EOF/EPIPE 与心跳超时，异常时立即注销 AudioPolicy 退出   │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 1. 为什么将编码与推流留在主 App 进程？
- `ShellAudioHelperMain` 只承担极简的「注册音频策略 + `AudioRecord.read()` + 写 `LocalSocket`」职责，**不加载 Rust `.so`、不建局域网连接、不管理音箱状态**；
- 音频帧契约与现有管线完全一致：`48,000 Hz` / `PCM_16BIT` / `CHANNEL_IN_STEREO`，每次读取 `1024 samples = 4096 bytes`（`21.333ms`），直接喂给现有 `PcmProbe` → `AacEncoder` → `NativeBridge.pushAac()`；
- [AndroidManifest.xml](../android/app/src/main/AndroidManifest.xml) 已声明 `android:foregroundServiceType="mediaProjection|mediaPlayback"`：切入内置无感模式时，`CastService` 只需启动 `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`，彻底不申请 `mediaProjection` 前台类型。

### 2. 待新增的 Android 侧模块规划（不破坏现有分层）
```text
android/app/src/main/java/com/cozyting/app/
├── capture/
│   ├── AudioCaptureBackend.kt               # 统一采集后端接口与状态/能力声明
│   ├── MediaProjectionBackend.kt            # 从 CastService 抽离的现有标准后端（零行为回归）
│   └── shell/
│       ├── WirelessAdbKeyStore.kt           # 私有目录客户端密钥/证书持久化（禁止备份）
│       ├── WirelessAdbDiscovery.kt          # NsdManager 发现 _adb-tls-pairing / _adb-tls-connect
│       ├── WirelessAdbPairingClient.kt      # SPAKE2 + TLS 1.3 6 位配对码握手
│       ├── WirelessAdbShellClient.kt        # TLS ADB 连接与单次 app_process 点火
│       ├── PairingNotificationGuide.kt      # 通知栏 RemoteInput 填码引导与状态提示
│       ├── ShellHelperSupervisor.kt         # 管理 Helper 生命周期、随机 Token 与 LocalSocket 会话
│       ├── ShellAudioHelperMain.kt          # 运行于 uid 2000 的 app_process 极简采集主入口
│       ├── AudioPolicyReflector.kt          # HiddenApiBypass 与多 ROM 隐藏 API 签名兼容反射
│       ├── ShellPolicyBackend.kt            # API 33+ 动态路由后端（单 UID / 全局媒体 / 热切源）
│       └── ShellSubmixBackend.kt            # API 30-32 REMOTE_SUBMIX 显式整机后端
└── compat/
    └── ShellCaptureCompat.kt                # 隔离 API 30/33 检查，确保 NewApi Fatal Lint 零告警
```

---

## 五、核心行为契约：Fail-Closed、热切源与三重防卡死清理协议

### 1. 严格守住 Fail-Closed 红线（绝不静默降级）
- **单 App 隔离模式（默认，API 33+）**：
  - 启动与热切源前仍通过现有 `CaptureTargetResolver` 实时校验目标包同用户、非共享 UID 与 SHA-256 签名；
  - `AudioMixingRule` 强制添加 `RULE_MATCH_UID` + `USAGE_MEDIA` / `USAGE_GAME` / `USAGE_UNKNOWN`；
  - 若反射注册失败、无线调试未就绪或目标身份失效，**必须直接报错停止并提示用户检查状态或切换模式，严禁静默退化为整机捕获**。
- **全局媒体模式（可选显式开关）**：
  - 仅在用户主动选择「捕获全部媒体（不指定单个 App）」时启用（API 33+ 通过仅匹配媒体 `USAGE` 的 `AudioMixingRule` 实现；API 30–32 通过 `REMOTE_SUBMIX` 实现）；
  - 主界面音源卡与通知栏必须明确标注「全局媒体模式（无感采集）」，让用户清晰知晓当前所有媒体发声都会被投送。

### 2. 本机静音策略映射（API 33+）
- **单 App 本机静音（推荐默认）**：`AudioMix.Builder.setRouteFlags(ROUTE_FLAG_LOOP_BACK)` —— 仅环回采集，不路由至手机物理扬声器/耳机，**跳过现有 `LocalOutputMute`，不再静音全局 `STREAM_MUSIC`**；
- **保留本机出声**：`AudioMix.Builder.setRouteFlags(ROUTE_FLAG_LOOP_BACK_RENDER)` —— 环回采集的同时继续向本机扬声器输出（需按机型实测验证 `LOOP_BACK_RENDER` 可用性）。

### 3. 切源热更新（零弹窗切换音乐 App）
直接对接 `CastService.kt` 已落地的 `hotSwitchCaptureTarget()` 与自动跟随切源逻辑：
1. 主进程校验新目标包签名与 UID；
2. 通过控制 `LocalSocket` 发送 `{"cmd":"update_uid","uid":...,"mute_local":true}`；
3. `ShellAudioHelperMain` 在短暂加锁内注销旧 `AudioPolicy`（必要时带短退避防异步反注册竞态）、按新 UID 注册新 `AudioPolicy` 并重建 `AudioRecord`，**保持现有 `LocalSocket` 数据流、编码器与 L1 `CastSession` 不断流、零弹窗**。

### 4. 三重防卡死清理协议（极关键安全不变量）
> [!CAUTION]
> 当使用 `ROUTE_FLAG_LOOP_BACK` 接管音频路由时，若采集进程异常退出而未调用 `unregisterAudioPolicy()`，目标 App 可能会持续无声直到系统回收策略。因此**必须实现三重兜底清理**：

1. **正常停止顺序**：主进程发送 `stop` → Helper 按顺序执行 `AudioRecord.stop()` → `AudioRecord.release()` → `AudioManager.unregisterAudioPolicy(policy)` → 关闭 `LocalSocket` 并 `exitProcess(0)`；
2. **连接断开即自毁（`EOF` / `EPIPE`）**：主 App 崩溃或被系统强杀（`am force-stop`）时，内核会立即关闭对端 `LocalSocket`；Helper 控制读线程收到 `EOF` 或 PCM 写线程遇到 `IOException` / `EPIPE` 的瞬间，立即执行 `unregisterAudioPolicy()` 并 `exitProcess(0)`；
3. **心跳看门狗兜底**：主进程每 `500ms` 发送轻量 `ping`（或利用控制通道保活），Helper 超过 `1.5s` 未收到心跳或 PCM 写入阻塞超 `2s`，同样立即反注册 `AudioPolicy` 并退出，防止半死连接拖死音频路由。

---

## 六、与工程红线及跨端规范的对齐要求

1. **API 29 (`minSdk = 29`) 与 `NewApi` Fatal Lint**：
   - 无线调试客户端与 `AudioPolicy` 相关逻辑全部按 `Build.VERSION.SDK_INT` 隔离在 `compat/` 与 `capture/shell/` 内，保证 `android/gradlew test lintStandardDebug` 零告警通过；
   - Android 10 设备自动使用默认 `MediaProjection` 模式，设置页清晰说明系统版本边界。
2. **16KB ELF 页对齐、安全隔离与零云端**：
   - 无线 ADB 客户端与 `HiddenApiBypass` 不引入任何非 16KB 页对齐的 `arm64-v8a` `.so`；
   - `LocalSocket` 仅允许持本次随机 256-bit Token 的主进程握手接入，且不开放任何对外 TCP 监听端口，全程零云端通信。
3. **跨端设计规范（`design/UI_SPEC.md`）同步**：
   - 进入编码阶段时，须同步在 [design/UI_SPEC.md](../design/UI_SPEC.md) 的设置页规范中补充 **「采集引擎（Android 专属）」** 卡片说明（选项：`标准投屏模式` / `无感模式（内置无线调试）`、首次配对状态徽章、一键引导配对入口、单 App vs 全局媒体模式说明），并明确标注 iOS 端受沙箱模型限制不展示此设置项。

---

## 七、诚实工程边界与已知风险

1. **状态栏「录音小绿点」隐私指示**：脱离 `MediaProjection` 后，「正在投屏」横幅、状态栏投屏图标与系统投屏通知会**彻底消失**；但在 Android 12+ 上，部分 ROM 在 `shell` 进程打开 `AudioRecord` 时右上角仍可能亮起极小的绿色录音隐私圆点，具体表现因机型而异，须实机验证。
2. **国产定制 ROM 反射与无线调试差异**：
   - `AudioMix` / `AudioPolicy` / `AudioMixingRule` 属 `@SystemApi` 隐藏类，部分定制 ROM（HyperOS / HarmonyOS / ColorOS / OriginOS）可能变更内部构造签名或限制 `shell` 注册动态路由策略；
   - 部分 ROM 需额外打开开发者选项中的安全调试开关，或在通知栏折叠 `RemoteInput` 输入框（可提供分屏/小窗或悬浮引导兜底）。
3. **手机重启后需重新打开无线调试**：Android 系统重启后会自动关闭无线调试；用户只需下拉快捷开关或进设置点开一次「无线调试」（约 2~3 秒，**无需重新输 6 位配对码**），App 即可自动静默重连。不重启则长期有效。
4. **Android 10 硬边界**：Android 10 既无原生无线调试配对，`com.android.shell` 也不具备 `CAPTURE_AUDIO_OUTPUT`，因此保持现有 `MediaProjection` 标准模式。
5. **拒捕 App 与 DRM 音源**：虽然 `shell` 级 `AudioPolicy` 理论上比普通 `AudioPlaybackCapture` 覆盖面更广，但硬件 DRM / 特殊保护音频流仍可能输出静音，具体支持清单以真机实测为准，不提前承诺。

---

## 八、分阶段实施与真机验收清单（未来启动开发时执行）

### 阶段 0：最小真机探针（POC，解耦音频采集与无线配对，优先排雷）
在正式重构 `CastService` 前，先在目标 **Android 13+ 真机**上分两小步完成 **7 项硬核实测**：
1. **核心采集探通（先用 `adb shell` 直拉 `ShellAudioHelperMain`）**：验证 `uid 2000` 进程能否成功反射构造并注册 `AudioPolicy` + 创建 `AudioRecord`；
2. **PCM 有效性与延迟验证**：播放任意音乐 App，验证 `LocalSocket` 持续产出非零 48kHz 立体声 PCM 且通过 `PcmProbe`，喂入现有 `AacEncoder` + L1 引擎向音箱推流无爆音/欠载；
3. **系统 UI 观感实测**：确认全程**零投屏授权弹窗、零「正在投屏」提示**，并如实记录右上角是否出现录音隐私绿点；
4. **单 App 真静音与不串音验证**：以 `ROUTE_FLAG_LOOP_BACK` + `RULE_MATCH_UID` 投送音乐 App，同时打开另一视频/语音 App，确认音乐仅从音箱出声、另一 App 仅从手机出声且不串入音箱；
5. **异常防卡死验收**：投送中强杀主 App 进程（`am force-stop`），确认 `LocalSocket` `EOF`/心跳看门狗立刻触发 `unregisterAudioPolicy()`，目标音乐 App 在手机上瞬间恢复正常出声；
6. **内置无线调试配对闭环验收**：脱离电脑，在纯手机端完成「通知栏输入 6 位配对码 → 持久化证书 → 自动连接 `_adb-tls-connect._tcp.` → 拉起 `ShellAudioHelperMain`」以及关闭再重开无线调试后的静默重连；
7. **长稳与热切源验收**：锁屏连续投送 ≥30 分钟无断流/内存增长；运行期手动/自动热切换另一个音乐 App 确认零弹窗无缝切换。

### 阶段 1：正式集成可插拔后端与设置页
1. 抽离 `AudioCaptureBackend` 接口，将现有 `MediaProjection` 代码无回归迁入 `MediaProjectionBackend`；
2. 落地内置无线调试模块（`WirelessAdb*`）、`ShellHelperSupervisor`、`ShellPolicyBackend`（API 33+）与 `ShellSubmixBackend`（API 30–32 可选）；
3. 同步更新 [design/UI_SPEC.md](../design/UI_SPEC.md) 与设置页 UI，补充采集引擎状态机、Token 鉴权与 Fail-Closed 契约 JVM 单测，跑通 `cargo test --workspace`、`clang -std=c11` 头文件检查与 `./gradlew test lintStandardDebug`。

### 阶段 2：真机兼容矩阵与文档同步
1. 将各实测机型与 ROM 的真实结果（成功项、绿点表现、无线调试引导差异、反射差异、DRM 音源情况）如实登记至 [COMPATIBILITY.md](COMPATIBILITY.md)、[LIMITATIONS.md](LIMITATIONS.md) 与 [LOCAL_MUTE.md](LOCAL_MUTE.md)。
