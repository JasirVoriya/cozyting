# LX06 反向 RTSP 最小真机诊断（v5 之后）

## 已锁定事实（勿再争论）

| 事实 | 来源 |
|------|------|
| L17A 单机反向 ~450ms 成功；静音链路帧/心跳正常 | v5 independent-result |
| LX06 晚加入已 `single_room→multi_room`、Open/listen 正确、ss 超时前持续 LISTEN，仍 10.02s timeout | late-join-listener / logcat |
| 双机同时 `multi_room`：LX06 仍 timeout；L17A ~488ms 反向成功 | dual-full-logcat |
| H1「错误 single_room 是充分根因」**已证伪** | 同上 |
| 本仓库注释：LX06 曾在 Safety 路径跑通 Open/RTSP；legacy 亦有官方 milink 放音记载 | `core/src/auth/mod.rs` |
| 本仓库日志**从未**出现过 LX06 `RTSP connected back`（仅 timeout） | 全文检索 |
| Open 失败路径原先不发 CLOSE；v6 起失败后尝试 CLOSE + 等待期内短轮询 8899 | 代码契约 |
| **不**把失败归咎于“网络”或“已修好 LX06”，除非下列实验区分成立 | — |

静音源 / 无投影本轮足够；PCM=0 不得当音乐/隔离/同步通过。

## 可证伪差异假设（优先序）

1. **H-solo**：LX06 **单机**也无法反向（与双机/晚加入无关）。  
2. **H-auth**：Auto→legacy 控制成功但 LX06 Open/反向失败；**强制 Safety** 可反向（对照 auth/mod 06:10 跑通记载）。  
3. **H-sticky**：多次 Open 超时未 CLOSE 导致 LX06 `state=2` 悬挂；CLOSE/冷启动后单机可通。  
4. **H-noreply**：音箱未尝试 SYN（协议拒绝 Open）vs **H-syn-drop**：有 SYN 但未达 LISTEN（入站路径）。  
5. **H-ack-late**：Open 后迟来的 0x0001/0x0041/NOTIFY 携带拒绝信息（v6 `during-reverse` 日志）。

已弱化/排除：H1 ref 单独充分；手机未 LISTEN（晚加入路径已否）；广告 IP≠listen（日志一致）。

## 最小实验序（独立执行者；不改路由/音箱配置；VM 不连 LAN）

每步失败即停止并交日志；成功才进入下一步。全程媒体/音箱音量 0；内置 silent；同一 v6 APK。

### D0 — LX06 单机（最高优先）

1. App 停止并确认无 ServiceRecord；可选再 probe LX06，记录 `state`/`mirror_mode`。  
2. **只勾选 LX06**，认证=自动，静音源起播。  
3. 记录：Open `ref`/`listen`、是否 `RTSP connected`、或 timeout + 是否出现 `CLOSE after Open/reverse failure`。

| 结果 | 结论 |
|------|------|
| 单机成功 | 排除 H-solo；问题在双机/争用/时序 → 再做双机同开（先关干净） |
| 单机仍 timeout | 双机/晚加入是表象；进入 D1 |

### D1 — 同机强制 Safety

1. 认证选 **Safety加密**，仍只勾选 LX06。  
2. 日志须有 `auth=safety`（或 safety bootstrap），再看反向。

| 结果 | 结论 |
|------|------|
| Safety 通、Legacy/Auto 不通 | H-auth 成立 → 再定 Auto 策略（勿盲改） |
| 仍 timeout | H-auth 证伪；进入 D2 |

### D2 — 释放 / 粘滞

1. 确认上一步失败日志含 `CLOSE after Open/reverse failure`。  
2. 立即 probe LX06：`state` 是否从 2→0。  
3. **不**改网络；若用户同意音箱断电重启（非本执行者改配置），冷启动后再 D0 一次。

| 结果 | 结论 |
|------|------|
| CLOSE 后 state→0 且再 D0 成功 | H-sticky 成立 |
| state 仍 2 或 D0 仍失败 | H-sticky 不足；进入 D3 |

### D3 — 受限抓包（手机侧，仅广告端口）

在 D0/D1 某次 Open→timeout 窗口：

```text
# 示例：Open 日志中的 listen 端口
tcpdump -ni <wlan> tcp port <listen_port> and host 192.168.31.234
# 同时 ss -ltn | grep <listen_port>
```

| 结果 | 结论 |
|------|------|
| 无 SYN | H-noreply（音箱未发起）→ 协议/会话层 |
| 有 SYN 无完成握手 | H-syn-drop / 中途重置 → 再查本机防火墙（**不**改路由器） |
| SYN+完成但进程未 accept | 监听/进程异常（与现 LISTEN 证据冲突，需复验） |

### D4 — 对照 L17A

同 APK、同认证模式再跑 L17A 单机 30s，确认基线未回归。

## 禁止

- 延长 reverse 超时冒充修复  
- 无 D0/D1 结果就改认证默认或 Open URL  
- 宣称真音乐 / UID 隔离 / 同步（本诊断用 silent）  
- VM 连用户 LAN、操作用户设备、改音箱/网络配置  

## 成功交回的最小包

1. 各步 logcat（含 Open / during-reverse / CLOSE / RTSP connected 或 timeout）  
2. D0/D1 的 probe 前后 JSON  
3. 若做 D3：tcpdump 摘要（有无 SYN/RST）+ ss LISTEN 时间戳  
