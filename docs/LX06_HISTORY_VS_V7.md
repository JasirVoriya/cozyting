# LX06：历史真成功 vs v7 失败 — 区分力对照（v8）

**结论边界：** 有区分力的对照与可证伪实验计划；**不宣称根因，不宣称 LX06 已修。**  
依据：`history-comparison`（脱敏原行号/哈希）+ `independent-result_1ae2` + v7 证据。  
时间均为 2026-11-17 Asia/Shanghai。

## 1. 已锁定事实（不可再退回旧解释）

| 事实 | 证据 |
|------|------|
| LX06 当日确有反向成功 | 04 Safety：Open→RTSP 279ms、首 PCM、573 AU；07 安装 App silent Legacy：268ms、759 AU |
| 「以前 shell 现在 App」不充分 | 07 为 `com.miplay.sender` 安装 App 静音单机成功 |
| 「未选中 Safety」已证伪 | v7 E1：`auth_resolved=safety native_ver=3.1.6030516 secure=true` 仍 10.02s timeout |
| Legacy/Auto 亦失败 | E2 / E2A 真实 `1.0.1123012` / secure=false，同样 timeout |
| 同 APK Safety 对 L17A 有效 | E3 反向 ~485ms、477 AU |
| 「当前进程无 LX06 RTSP」≠「历史从未成功」 | 诊断文限定当前进程；历史包独立证明 |

## 2. 协议 / 版本 / 地址 / 顺序对照

| 轴 | 历史成功（04 / 07） | v7 失败（E1/E2/E2A） | 区分力 |
|----|---------------------|----------------------|--------|
| Open URL | `wfd://PHONE_IP:<ephemeral>?mirrorMode=1\0` | 同格式 | 无（URL 内 mirrorMode 恒为 1） |
| 地址/端口 | 每次 ephemeral TCP/UDP；PHONE_IP 同源 | 同 | 无（LISTEN 已覆盖等待窗） |
| 阶段顺序 | premute→bootstrap→prepare/SetPlaySource→Open→accept | 同，停在 accept | 窗口内行为见下 |
| 认证 | 04 Safety；07 Legacy | E1 Safety；E2/E2A Legacy | **auth 轴已证伪为充分条件** |
| SET_PLAY_SOURCE / OPEN | 旧日志少 cmd 行；源码同 `single_room` + Open seq14 | cmd 0x0040/0x0000 已记录 | 字段级未证明字节差 |
| **Open→RTSP 窗口** | **立即 `accept`，无 8899 poll** | v7：**post-Open 120ms poll + 等待期周期 poll** | **唯一窗口内协议差** |
| **mirror/state（引导查询）** | 成功 INITIAL：**mirror=2, state=0** | 失败摘要：**mirror=1, state=2**（无 notify 刷新） | **强相关，未证因果** |
| 包名/SDK | 07：`com.miplay.sender`（当时树 min29/target35） | acceptance min34/target36 | L17A 同包成功 → 非笼统 inbound 阻断；仍可做包名 A/B |
| 设备释放 | 07 正常停后 notify state=0 / mode=2 | 超时 CLOSE（无 ACK）；probe 仍可见 state=2 | 粘滞旁证，非根因证明 |

## 3. 假设（有区分力；未宣称根因）

1. **H-prestate**：LX06 在 bootstrap/prepare 报告 `mirror=1,state=2` 时拒绝反向；历史成功为 `2/0`。  
2. **H-open-poll**：v7 诊断性 `poll_incoming` 干扰 LX06 Open 处理（相对 06:10/11:59 路径）。L17A 同 poll 仍成功 → 若成立则机型相关。  
3. **H-package**：acceptance UID/SDK 与 11:59 `com.miplay.sender` 差异影响 LX06（L17A 反例削弱，不消灭）。  
4. **H-baseline**：相对 handoff/`1ee3ea5` 时代二进制的其它累积差（需基线 APK 对照）。

**已排除为充分解释：** 仅 Safety 未选中；仅 shell vs App；仅 state=2 当成功；延长超时；无证据的网络归因。

## 4. v8 代码动作（测试先行，未测不得称通过）

- 默认路径 **去掉** Open 后 / 反向等待中的 `poll_incoming`（恢复历史 Open→accept）。
- prepare 的 `GET_MIRROR_MODE_ACK` **写回** `status.mirror_mode`，并打 `pre-Open mirror_mode/state` 日志。
- 单测锁定历史 vs v7 pre-Open 对照函数；**不**把对照函数当修复证明。
- 保留失败后 CLOSE 与 auth 诊断日志；**不**延长 10s 超时；**不**重复 auth 矩阵。

## 5. 最小受控实验（由你协调；仅验收 App 静音；不碰 release / 不重启音箱 / 不抓包提权）

### C1 — v8 acceptance（本产物）LX06 silent × Auto 或 Legacy 一次

必看日志：`pre-Open mirror_mode=… state=…`；有无 `RTSP connected`。  
- 若 `pre-Open` 为 2/0 且仍 timeout → **H-prestate 弱化**；转向 H-open-poll 已落地后的剩余差 / H-package。  
- 若 `pre-Open` 为 1/2 且 timeout → **H-prestate 仍成立方向**；进入 C2。  
- 若 RTSP connected + 首 PCM → 记录为 **候选修复线索**（仍须 L17A 冒烟）；不得扩读真音乐/同步通过。

### C2 — 同日、不重启音箱：基线包 `com.miplay.sender`（handoff / `1ee3ea5` 或 11:59 等价构建）silent LX06 一次

- 基线成功 + v8 失败 → 代码/打包回归轴成立，再做最小 diff。  
- 基线也失败 → 偏向设备粘滞/环境轴；**禁止**擅自重启音箱清状态。

### C3 — L17A Safety 冒烟（防回归）

同 v8 APK；确认反向仍成功且日志有 `pre-Open`。

**不做：** 重复 Safety/Legacy/Auto 全矩阵；延超时；改网络/音箱/用户设备；提权抓包；宣称未测项通过。
