# MiPlay 发送端（纯局域网）

Android 手机作为发送端，经局域网把音频投送到小米音箱（MiPlay）。本仓库为私有草稿，不发布命名镜像仓库。

## 功能

- 发现/多选分组、暂停恢复、加入移除、音量、延迟、Legacy/Safety 认证、断线重连、前台通知
- 晚加入扩组：`multi_room` 升级（v5 已证伪「仅此即修 LX06」）；Open 失败 CLOSE；连接失败摘要含实际 `auth=`；见 `docs/LX06_REVERSE_DIAG.md`（**不宣称 LX06 已修好**）
- 音源：**测试静音** 或 **选定 App 音频**（`AudioPlaybackCapture` + 严格单 UID + usage 过滤）
- minSdk 34（Android 14+），compile/targetSdk 36
- 不申请 `QUERY_ALL_PACKAGES`；可启动 App 列表依赖 `<queries>` MAIN/LAUNCHER
- 不采集麦克风；`RECORD_AUDIO` 仅用于播放捕获；FGS 类型为 `mediaProjection` / `mediaPlayback`
- MediaProjection **每次会话重新授权**；切换目标会停止旧会话

## 明确不承诺

- 不能保证禁止捕获的第三方 App 可捕获
- 不能声称捕获会静音原 App 本地输出
- 电池优化入口 ≠ 全品牌后台保活；HyperOS Greeze 等冻结需真机验证
- 编译通过 ≠ 全机型兼容

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
