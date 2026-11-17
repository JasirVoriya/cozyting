# API29 兼容报告（0.9.3 / versionCode 12）

目标机参考（**未在本环境真机验证**）：HUAWEI MAR-AL00，Harmony 3 / EMUI13，Android 10 API29 arm64-v8a。  
ADB 仅 Windows 本地；本代理只做代码与构建。

## 构建产物

| 项 | 值 |
|---|---|
| APK | `/opt/cursor/artifacts/app-acceptance-debug.apk` |
| applicationId | `com.miplay.sender.acceptance` |
| versionName | `0.9.3-api29-acceptance` |
| versionCode | 12 |
| minSdk / targetSdk | **29** / **36** |
| ABI | arm64-v8a |
| 签名 SHA-256 | `aa457261427d693f0f04acd56652442e5403ed4288c0b8e5b61b8e48b91abab4` |
| 正式包 `com.miplay.sender` | **未构建、未覆盖** |

## 技能

请求加载的 `systematic-debugging` / `test-driven-development` 技能文件在本环境中**不存在**；按同等流程执行：先审计 → 写回归测试 → 再改实现 → lint/测试/构建。

## API30+ 审计与适配（非仅改 minSdk）

| 区域 | 风险 | 适配 |
|---|---|---|
| `RenderEffect` 毛玻璃（API31） | MainActivity 直接引用 → API29 方法执行/校验失败 | `BlurCompat` + 仅 SDK≥31 加载 `BlurEffectApi31`；旧系统压暗降级 |
| `WindowInsets.Type`（API30） | 类/方法在 API29 不可用 | `WindowInsetsApplyCompat` → AndroidX `WindowInsetsCompat` |
| `POST_NOTIFICATIONS`（API33） | 低版本无需运行时申请 | `NotificationPermissionCompat` 仅 ≥33 请求 |
| `getParcelableExtra(…, Class)`（API33） | 已有分支 | 保持 SDK≥33 / deprecated 双路径 |
| FGS `mediaProjection\|mediaPlayback` | 类型自 API29；权限声明 API34 | 清单保留；`startForeground(id,n,type)` API29 可用 |
| MediaProjection / AudioPlaybackCapture | 自 API29 | 保留 UID+usage 严格过滤；无全系统回退 |
| PackageManager 签名 | 禁 API33-only Flags 重载 | `PackageSigningCompat` 用 `getPackageInfo(String,int)` + `GET_SIGNING_CERTIFICATES` |
| 包可见性 `<queries>` | API30+ | 保留；API29 忽略该元素、可见性更宽 |
| Native JNI | 需与 minSdk 对齐 | `cargo ndk -p 29` 重编 `libmiplay_sender_core.so` |

CastService 构造期 NPE 修复（`lateinit localMute` @ onCreate）**保持**，`CastServiceMuteInitTest` / 契约测试仍覆盖。

## 上次真机缺陷（代码侧，未声称真机已过）

1. **两层选择**：App 选择器 UID 与系统 MediaProjection 框分离；文案与日志明确「系统框不会改写 UID」。选测试音源 A 而 UID 仍为 `com.luna.music` → 静音是预期不一致，不是授权已切换。  
2. **验收音量**：生产默认音箱进度仍为 30；验收变体默认 **0**；验收停止投送 **不恢复** 本机 STREAM_MUSIC 索引。  
3. **静音探针**：捕获持续近零 → `UNVERIFIED_NO_CAPTURE_ENERGY`（明确未验证，不假通过）。

## 已测 / 未测

| 项 | 结果 |
|---|---|
| JVM 单测 acceptanceDebug | **85 passed / 0 failed**（日志见 artifacts） |
| Lint `NewApi`（abortOnError） | **无 NewApi error** |
| 验收 debug APK 签名/包名/sdkVersion | 已核验 |
| API29 系统镜像 / 模拟器启动 smoke | **未测**（本环境无 `system-images`） |
| HUAWEI MAR-AL00 真机 / USB ADB | **未测**（仅 Windows 侧后续） |
| 真实音频捕获、音箱同步、电视投送 | **未测**；测试不向电视、不改音箱分组 |

## 源码归档

`/opt/cursor/artifacts/miplay-sender-source-0.9.3.tar.gz`（与本版同步，含 android/core/docs/scripts）。
