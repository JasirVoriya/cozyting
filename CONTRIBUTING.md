# 参与贡献（Contributing Guide）

感谢你关注 **MiPlay 发送端（纯局域网）**！无论是上报真机/音箱实测结果、反馈 Bug、完善文档，还是提交 Rust 核心与 Android/iOS 代码 PR，都非常欢迎。

---

## 一、最快参与方式

1. **上报音箱 / 手机兼容实测**：前往 [设备兼容实测模板](https://github.com/JasirVoriya/miplay-sender/issues/new?template=device_compatibility.yml) 提交你的测试结果（可用或不可用都同样宝贵），帮助完善 [设备兼容矩阵](docs/COMPATIBILITY.md)。
2. **反馈 Bug 或排查协议**：使用 [Bug 反馈模板](https://github.com/JasirVoriya/miplay-sender/issues/new?template=bug_report.yml)，如方便请附带 `files/cast_ops.log` 或只读命令 `miplay-cli inspect <IP> --auth legacy` 的脱敏输出。
3. **提交功能建议**：使用 [功能建议模板](https://github.com/JasirVoriya/miplay-sender/issues/new?template=feature_request.yml) 描述真实使用场景。
4. **提交代码或文档 PR**：按下方指南配好开发环境后发起 Pull Request。

---

## 二、开发环境要求

- **JDK**：17+
- **Android SDK**：`platforms;android-36`、`build-tools;36.0.0` 及 NDK（推荐 r27c / `27.2.12479018`）
- **Rust**：`≥ 1.75`（Edition 2021），安装 `aarch64-linux-android` target 与 `cargo-ndk`

```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/<version>"
rustup target add aarch64-linux-android
cargo install cargo-ndk --locked
```

---

## 三、本地构建与自检命令

提交 PR 前，请至少在本地运行以下验证：

```bash
# 1. Rust 核心与 CLI 单元测试
cargo test --workspace

# 2. 若修改了 C ABI 头文件（core/include/miplay_core.h），校验 C11 语法
clang -std=c11 -Wall -Wextra -Werror -fsyntax-only core/include/miplay_core.h

# 3. 编译 16KB 页对齐 arm64-v8a 原生库 + 运行 Android JVM 单测、Lint 与 Debug 构建
./scripts/build-debug.sh

# 或分步运行 Android 单测与 Lint（NewApi 为 fatal 硬门槛）
cd android && ./gradlew --no-daemon test lintStandardDebug
```

---

## 四、必须守住的工程红线

- **纯局域网 · 零云端**：不引入任何云端 API、账号登录、统计上报或第三方追踪 SDK。
- **严格单 App 捕获（Fail-Closed）**：不得破坏单 UID + `USAGE_MEDIA/GAME/UNKNOWN` 白名单校验；校验失败必须显式拒绝，绝不静默退化为全系统录屏或麦克风采集。
- **API 29 真实兼容**：`minSdk = 29`，Android Lint 已将 `NewApi` 设为 `fatal`，高版本系统 API 必须隔离在 `compat/` 封装层内。
- **16KB ELF 页对齐**：所有 `arm64-v8a` `.so` 编译需保留 `-C link-arg=-Wl,-z,max-page-size=16384`，确保 Android 15+ 16KB 页设备兼容。
- **诚实测试边界**：PR 中如实写明「已验证项」与「未测项」；编译通过 ≠ 全机型兼容，协议估算延迟 ≠ 声学实测。
- **敏感信息零入库**：严禁提交 `.keystore` / `.jks` / `keystore.properties` / `local.properties` / 签名口令 / 私有设备序列号 / 原始 `.pcapng` 抓包文件。

---

## 五、架构分层约定

- **L1 公共核心层（`core/`）**：平台无关协议、mDNS 发现、认证、多箱同步、路由与协调状态机、统一延迟状态；通过 `ffi.rs`（Android JNI）与 `ffi_c.rs`（iOS C ABI）对外暴露薄接口。
- **L2 / L3 平台实现层（`android/` 及规划中的 iOS）**：仅保留 OS 强相关能力（音频捕获、硬件编码、前台服务、原生 UI 与本地持久化）。

如需先摸清全仓模块上下游，推荐浏览 [在线交互式架构浏览器](https://jasirvoriya.github.io/miplay-sender/)（本地源码位于 [docs/architecture.html](docs/architecture.html)）。

---

## 六、开源许可证

本项目基于 **[GNU Affero General Public License v3.0 (AGPL-3.0-only)](LICENSE)** 开源。提交 Pull Request 即表示你同意你的贡献按相同许可证对外发布。
