# 安全策略（Security Policy）

## 支持版本

| 版本 | 是否接收安全修复 |
| --- | --- |
| `main` 分支 / 最新 Release（`0.9.x`） | ✅ 支持 |
| 更早历史版本 | ❌ 不再维护 |

---

## 本项目的安全与隐私边界

- **纯局域网运行**：不连接任何云端 API，不上传通知内容、音频数据、设备列表或日志。
- **音频捕获隔离**：`RECORD_AUDIO` 权限仅用于 `AudioPlaybackCapture` 播放捕获（不采集麦克风），启动前强制校验目标包同用户、非共享 UID 与签名摘要，任一不满足即 **fail-closed** 拒绝。
- **双认证隔离**：Legacy（明文挑战）与 Safety（OPack + 双向 HMAC + 链式 AES-128-CBC）链路严格分离，不跨模式混用。
- **只读诊断约束**：`miplay-cli inspect` 强制限制命令白名单并在退出时仅执行 TCP shutdown，不发送 `CLOSE` 破坏正在进行的外部会话。

---

## 如何报告安全漏洞

如果你发现涉及以下范围的安全隐患：

- 绕过单 UID 捕获隔离（可能导致捕获到非目标 App 音频或麦克风）
- Safety 认证/加密实现缺陷或敏感内存/日志泄露
- 局域网协议解析导致的远程崩溃（DoS）或越界读写
- 权限提升、Intent 劫持或跨应用敏感数据暴露

**请勿直接发布公开 Issue。** 建议通过以下任一私密方式上报：

1. **优先**：使用 GitHub 仓库的 **Security → Advisories → Report a vulnerability**（Private Vulnerability Reporting）提交私密安全报告。
2. 如暂未启用私有报告入口，可先在 Issue 中仅留言「请求安全漏洞私下沟通渠道」（勿附带复现细节或 PoC），维护者将尽快响应并建立私密联络方式。

### 报告建议包含的内容

- 影响版本与模块（如 `core/src/auth/safety.rs`、`CastService.kt` 等）
- 复现步骤或最小 PoC
- 潜在影响范围与（如有）修复建议

感谢你帮助提升本项目与局域网音频互操作生态的安全性！
