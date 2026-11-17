# 未测限制与已知缺口

## 本环境已验证

- JVM 单测 24：App 选择 fail-closed、UID/共享 UID/跨用户/身份变化、捕获配置禁止空白名单、MediaProjection 会话策略、MiPlay 动作回归
- Rust `cargo test --workspace`：20 passed，1 ignored（需抓包向量的 Safety 解密）
- `assembleDebug` 产出 `app-debug.apk`（arm64-v8a）
- native `.so` LOAD 对齐 `0x4000`（16KB），本机构建可证实

## 未测 / 不能宣称

| 项 | 说明 |
|---|---|
| 真机 UID 隔离 A/B | 无用户设备；需受控音源与频谱证据 |
| 拒捕获 App | 需专用测试包 |
| 卸载重装 / 授权撤销 | 需真机 |
| API 34/35/36 模拟器仪器测试 | 本轮未起模拟器 |
| 各品牌后台（HyperOS Greeze 等） | WakeLock DISABLED 为系统行为；电池优化入口≠保活修复 |
| 稳定性 ≥30 分钟双音箱 | 原 soak 未通过且本轮无设备 |
| 网络抖动黑洞钩子实机 | 进程内注入≠真实丢包 |
| x86_64 / armeabi-v7a | 仅构建 arm64-v8a；其他 ABI 未产出 |
| AGP↔API36 官方兼容表 | AGP 8.7.3 测试上限 compileSdk 35；已 suppress 警告，构建成功但不等于官方完整支持声明 |
| 16KB 页真机启动 | 对齐已做；未在 16KB 页设备上安装验证 |
| release R8 安装烟测 | 可打 release，本轮以 debug 为主；未用用户 release 密钥 |
| 第三方禁止捕获 / 静音原输出 | 明确不保证、不声称 |

## 可证实的生命周期修复

- 停止/投影撤销路径：释放 AudioRecord、编码器、MediaProjection、Wake/WifiLock、取消通知、清理 grant session
- 开始顺序：用户授权 → mediaProjection FGS → `getMediaProjection` → UID 过滤捕获
- 切换目标：停止旧会话并要求重新授权；不复用授权 Intent
