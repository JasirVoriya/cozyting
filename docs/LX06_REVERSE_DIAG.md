# LX06 反向 RTSP（v8：历史对照后的 Open 路径还原）

## 已证伪 / 已锁定（勿再空转）

| 项 | 状态 |
|----|------|
| H1 late-join multi_room 充分 | v5 证伪 |
| 「强制 Safety 即可」 | v7 E1 证伪（`auth_resolved=safety` 仍 timeout） |
| Legacy / Auto | v7 E2/E2A 同样 timeout |
| L17A Safety | v7 E3 成功 |
| 历史从未成功 | **否** — 04/07 真反向+首 PCM+AU；见 `LX06_HISTORY_VS_V7.md` |
| 「当前进程无 LX06 RTSP」扩读历史 | **禁止** |

## v8 相对 v7（不是“已修好”）

1. Open 后默认路径恢复为 **立即 accept**（去掉诊断 `poll_incoming`）。
2. `pre-Open mirror_mode/state` 与 prepare mirror ACK 写回，供与历史 2/0 vs 失败 1/2 对照。
3. 同包名同签名升级：`0.8.0-open-restore` / versionCode 8。

必看日志：

- `pre-Open mirror_mode=… state=…`
- `ctrl send SET_PLAY_SOURCE … prepare_mirror=…`
- 成功：`RTSP connected back`；失败：`connect failed summary`（仍含 auth=）

## 下一轮：仅 C1→C3（见 HISTORY 文档）

不重复 auth 矩阵；不延超时；不 root / 不改音箱网络；静音成功 ≠ 真音乐/隔离/同步通过。
