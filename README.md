# MiPlay 发送端（纯局域网）

Android 手机作为发送端，经局域网把音频投送到小米音箱（MiPlay）。本仓库为私有草稿，不发布命名镜像仓库。

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

## 许可与隐私

仅声音捕获；不采集图像；不访问用户局域网设备做扫描以外的投送；测试请用自建受控音源。
