# LX06 反向 RTSP 诊断（v7：可区分 auth 是否真执行）

## v6 矩阵已锁定（勿再同矩阵空转）

| 项 | 事实 |
|----|------|
| H-solo | LX06 **单机** Auto 亦 10s timeout |
| D1「强制 Safety」 | **未在日志证明** Safety 已执行；connect→prepare ~331ms ≈ Auto，而 D4 L17A Safety ~572ms |
| D4 | 同 APK L17A `auth=safety` 反向 ~490ms 成功 |
| LISTEN | D0/D1/D2 超时前广告端口持续 LISTEN |
| CLOSE | 仅本端发送 seq=16；协议无 CLOSE_ACK，不能当远端确认 |
| D3 | 无 tcpdump / 未提权 → **无 SYN 结论**；勿猜网络 |

本仓注释：Safety 曾在 LX06/L17A 跑通 Open/RTSP（`auth/mod.rs`）；**当前进程日志从未出现 LX06 `RTSP connected`**。

## v7 相对 v6 的可区分增量（不是“修好 LX06”）

必须在 logcat 看到：

1. `auth_mode_requested=N (safety|legacy|auto)`（Kotlin）
2. `AUTH_MODE set to N (...)` + `connect auth_mode_requested=...`
3. `auth_resolved=safety|legacy native_ver=... secure=true|false`
4. `ctrl send SET_PLAY_SOURCE ...` / `ctrl send OPEN ...`
5. 若全失败：`connect failed summary: ... auth=...` 与 `receiver_stats` JSON

**判定规则：** 若请求 Safety 但无 `auth_resolved=safety`，本轮 Safety 假设无效，须修自动化/配置后再测。

自动化推荐：

```bash
# 字符串（优先）
am start … --es auth safety --ez autostart true --ei secs 20 …
# 或整型
am start … --ei auth 2 --ez autostart true …
```

## 下一轮最小动作（有区分力；不 root / 不改音箱 / 不抓整网）

### E1 — LX06 × 强制 Safety（须有 auth_resolved=safety）

1. 安装 v7 acceptance（同签名升级）。
2. 仅 LX06、silent、`--es auth safety`。
3. **通过门槛：** 日志含 `auth_resolved=safety` 且 `secure=true`。  
   - 若仍无 → 配置链路仍坏，停，交 log。  
   - 若有且仍 timeout → H-auth「Safety 即可修」证伪；保留 summary。  
   - 若有且 RTSP connected → H-auth 成立（与 handoff 注释一致方向）。

### E2 — LX06 × 强制 Legacy（对照）

同条件 `--es auth legacy`，确认 `auth_resolved=legacy`。与 E1 对比时延/结果。

### E3 — L17A × Safety 冒烟

确认 `auth_resolved=safety` 且反向仍 <1s（防回归）。

**不做：** 延长 reverse 超时；重复无 auth_resolved 的 D0/D1；提权 tcpdump；改路由器/音箱。

## 静音边界

silent / 未播 A/B → 不得称真音乐、UID 隔离、同步通过。
