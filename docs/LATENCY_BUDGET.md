# 延迟预算与降延迟（可证伪）

VM 不连 LAN；下列为发送端代码路径的毫秒预算与已落地改动。真机听感需对照 `LATENCY` 日志 / 界面「延迟诊断」。

## 测量定义（界面 / stats.latency）

| 字段 | 含义 |
|------|------|
| `kind` | 固定 `estimate`：本机墙钟估计，**不是**协议 RTT / 音箱回传实测 |
| `e2e_est_ms` | `(TIME_OFFSET + PTS − capture_stamp) / 1000` |
| `schedule_ahead_ms` | `(TIME_OFFSET + PTS − now) / 1000` |
| 缺失 | JSON `null`，UI「—」，**不用 0 或固定预算冒充实测** |

包含段：捕获后入队、编码后 AU、发送调度、配置的 `playback_delay`、起播 runway 内容龄。  
未知段：音箱内部 playout 缓冲、单向网络时延、stamp 之前的 AudioRecord 硬件环缓（未单独探针）。

## 预算拆解（稳态，flush 之后）

| 段 | 约略 ms | 说明 |
|----|---------|------|
| `playback_delay` | 800（5GHz）/ 1000（其它） | 官方 MultiMirror；写入 `TIME_OFFSET = now + delay` |
| 起播 runway 14 AU | ≈299 | 官方预热；首帧内容龄，稳态边际下降 |
| AudioRecord 环缓 | ≤≈85（现 4×chunk） | 原 16×chunk≈341；欠载则需放大 |
| AAC MediaCodec | ≈21–43 | 1–2 帧 |
| 喂入队列 cap | ≤≈2048（96 AU） | 原 512 AU≈11s；满则 `pushAac=-4` |
| 连接期积压 | **曾可达数秒** | 捕获在 connect 前启动；**屏障处 flush** 丢掉 |

多秒体感的主因是连接期队列积压 + `playback_delay`，不是「神秘网络」。

## 已落地的可证伪改动

1. **TIME_OFFSET 屏障前 `flush` 连接期 AU**（主杠杆）  
   - 证伪：若 `feed_flushed_aus_at_start` 长期为 0 且 App 源仍数秒延迟 → 假设不成立，查捕获/编码。  
   - 日志：`latency: flushed N connect-time AUs…`
2. **喂入队列 512→96 AU** — 硬顶约 2s，避免再堆成十余秒。  
3. **AudioRecord 16→4×chunk** — 证伪：出现连续 `AudioRecord.read` 欠载则回退。

## 硬下限（不宣称已达亚秒）

在保持双机共享 `TIME_OFFSET` / 官方 delay 公式的前提下，稳态 E2E 估计下界约 **≈ playback_delay（0.8–1.0s）+ 编码/捕获数十 ms**。  
要把配置 delay 再压到亚秒（如 400ms）需真机双机同步回归；本轮未改官方 800/1000 默认。用户可在 UI「延迟ms」手动填更低值做对照实验。

## 同步机制（简）

- 组共享一条 AAC PTS 时间线 + 一个 `TIME_OFFSET`。  
- 末台到 PLAY → 预热 14 AU → `offset = now + playback_delay`。  
- 晚加入复用当前 offset；暂停恢复 / 切歌会 rebase 并重发 TIME_OFFSET。  
- 失步风险：某台媒体队列满被踢、重连补流、时间线 forward-only 修正堆积。  
- **诊断**：每接收端 `latency_e2e_est_ms` / `latency_schedule_ahead_ms`；静音≠同步通过。
