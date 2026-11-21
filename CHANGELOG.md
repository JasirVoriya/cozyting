# 更新日志（Changelog）

本文件面向用户与开发者记录主要版本变更，遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 约定。更细粒度的工程验证与提交证据索引见 [docs/修复日志.md](docs/修复日志.md)。

---

## [Unreleased]

### Added
- **L1 Rust 核心下沉与跨端 FFI 解耦（#7）**：新增 `core/src/engine.rs`（句柄表与多箱并发协调器）与 `core/src/device_catalog.rs`（设备发现分类、默认过滤、历史缓存合并、路由互斥状态机与认证模式解析）；新增面向 iOS Swift 的 C ABI (`core/src/ffi_c.rs`、`core/include/miplay_core.h`、`core/include/module.modulemap`) 与 `scripts/build-ios-xcframework.sh`。
- **原创品牌视觉资产**：新增扁平同心音箱原创 Logo（浅/深双色）、Android Adaptive Icon / Android 13+ 单色主题图标 / 通知栏小图标，以及 README 横幅与社交分享预览图。
- **架构文档与交互式浏览器**：新增零依赖 [交互式架构浏览器](docs/architecture.html)、跨端三层架构图、整体数据流图与运行时架构动效 SVG。
- **GitHub 开源工程化**：新增结构化 YAML Issue 表单（Bug 反馈、设备兼容实测上报、功能建议）、PR 模板、CI 自动测试与 16KB 对齐校验、Tag 自动发布 APK + 多平台 `miplay-cli` 流水线、GitHub Pages 自动部署、Dependabot、`CONTRIBUTING.md`、`SECURITY.md`、`docs/COMPATIBILITY.md` 与 `README_EN.md`。

### Changed
- Android `CastService` 移除约 218 行手写 `routeLock` / reconcile 状态机并委托给 L1 Rust `Engine`；`LatencyDisplay` 优先消费核心归一化的 `ui_state` 与 `display_latency_ms`。

---

## [0.9.4] - 2026-11-20

### Added
- **正式开源发布基线**：全仓以 `AGPL-3.0-only` 许可证发布，提供正式签名构建脚本 `scripts/build-release.sh` 与 16KB 对齐验收脚本 `scripts/verify-acceptance-apk.sh`。
- **设备行内延迟展示（#4）**：移除顶部汇总与 IP 尾号，在每台活跃音箱行内原位显示整数 `ms`（连接/采样缺值显示转圈，停止/失败/取消选择时隐藏）。
- **无名非 MiPlay 节点智能过滤（#1）**：mDNS 同 IP 合并优先保留 `_mi-connect` (`apps=[5]`) 节点，默认隐藏未声明 MiPlay 能力的非电视节点，并提供设置内「显示全部发现设备」兜底开关。
- **显式本机静音策略（#2）**：新增 `GLOBAL_STREAM_MUTE`（默认）与 `KEEP_LOCAL_OUTPUT`（保留本机出声）双策略开关，诚实说明 Android 普通权限边界。
- **独立操作日志**：`CastService` 新增 64KB 轮转 `files/cast_ops.log`，避免关键操作事件被 1Hz 统计日志冲刷。

### Fixed
- **切歌间隙短暂双出声（#3）**：`LocalOutputMute` 引入 6 拍有声初始验证锁定（`probeLocked`），锁定后切歌静音不再误判并解除本机静音；外部按键取消静音后不强行循环重静音。
- **多音箱并发投送与停止死锁**：修复 `NativeBridge_stop` 与 `media-clock` 的通道死锁（先释放 `feed` 再停会话并向音箱下发 `CLOSE`）、消除首台建连窗口期丢点击、新增中途加入 `connecting` 占位与断线 `pending` 退避统计连续性。
- **冷启动权限与构建安全（#5）**：修复 API 29–32 冷启动误弹权限框问题；移除构建脚本中的硬编码签名口令并加入防回归契约单测。

---

## [0.9.3] - 2026-11-17

### Added
- **Android 10（API 29）完整兼容层**：新增 `BlurCompat`（API 31+ `RenderEffect` / API 29–30 压暗降级）、`WindowInsetsApplyCompat`、`NotificationPermissionCompat`、`PackageSigningCompat`，并将 `NewApi` Lint 设为 `fatal` 门禁。
- **严格单 App 捕获隔离与验收变体**：`AudioPlaybackCapture` 强制单 UID + 白名单 + 签名校验（fail-closed）；新增独立包名 `acceptance` 变体与 `:testtones` 三 UID 声道隔离验收套件。

---

## [0.9.2] - 2026-11-16

### Fixed
- 修复 `CastService` 构造期过早初始化 `localMute` 导致的 `getApplicationContext()` NPE 崩溃。
