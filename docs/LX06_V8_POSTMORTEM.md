# v8 真机后：证伪清单与唯一下一步（不发新 APK）

依据：`independent-result_3a2d` + `v8-test-evidence`。时间 2026-11-17 15:57–16:04 Asia/Shanghai。  
**不宣称根因；不宣称 LX06 已修；本轮不生成新验收 APK。**

## 1. 本轮硬事实

| 项 | 结果 |
|----|------|
| C1 LX06 v8 Legacy | `pre-Open mirror=1 state=2`，Open→~10.003s timeout；CLOSE seq16；0 AU |
| C3 L17A 同配置 Legacy | `pre-Open mirror=2 state=0`，466ms RTSP，10s 477AU / 2 心跳 |
| C2 现存旧 CLI | 同样 LX06 timeout；**不能**绑定早间成功构建哈希（部分对照） |
| 探针（Open 前） | LX06 已是 mirror1/state2；L17A 已是 mirror2/state0 |
| 探针（本轮结束后） | LX06 **仍** 1/2；L17A **仍** 2/0（C3 成功停后有 notify state=0/mode=2） |

## 2. 采样纠正（作废错误因果）

历史成功日志里的 INITIAL `mirror=2,state=0` 出现在 **PLAY 完成之后**，与 v8 的 **pre-Open** 不是同一采样点。  
**不得**再用「历史 Open 前必为 2/0」推因果。  
当前唯一同阶段对照：同 v8、同 Legacy、同 pre-Open 采样 → LX06=1/2 失败 vs L17A=2/0 成功（跨设备相关，非因果）。

另：成功播放路径上，L17A 在首 PCM 后也会 `notify state=2`。因此 **state=2 本身不是故障标志**；它在空闲探针上更像「音箱自认仍有活动会话」。

## 3. 已证伪 / 已削弱

| 假设 | 状态 |
|------|------|
| 未选中 Safety → LX06 失败 | **证伪**（v7 E1） |
| 仅切 Safety 即修 | **证伪** |
| 去掉 Open 后 poll 即修 | **证伪**（v8 C1）；且首次故障在 v4、早于 poll 诊断 |
| 「以前只有 shell、App 不行」 | **证伪**（11:59 安装 App 曾成功） |
| 仅 acceptance 包名/target 充分解释 | **削弱**（C2 旧 CLI 亦失败；但非严格同哈希基线） |
| 延超时 / 再跑 auth 矩阵 | **排除为下一步**（用户边界 + 无新区分力） |
| 历史 INITIAL 2/0 = 历史 pre-Open | **作废**（阶段不等价） |
| state=2 ⇒ 设备坏了 | **作废**（成功流中亦会出现 state=2） |

## 4. 仍未测 / 不能下的结论

- Safety-v8（C3 本轮显式 Legacy）
- 严格等同 06:10/11:59 成功二进制的哈希级复现
- SYN/抓包、网络归因
- 真音乐 / UID 隔离 / 可听同步 / 长稳
- **因果**：pre-Open 1/2 是否导致反向失败（仅相关）
- 远端 CLOSE 是否被音箱接受（协议无 ACK）

## 5. 只读能力：现有代码能识别什么

**已有、且本轮探针已用：**

- `probe` / bootstrap：`GET_MIRROR_MODE`、`GET_STATE`、`GET_VOLUME` → mirror / state / volume  
- 连接路径：`GetDeviceInfo` → 至少解析出 `channel`（C1 失败 JSON 有 `device_channel=center`）  
- 本测试会话关闭后：失败路径发 CLOSE；成功路径正常停 + notify。**v8 终态探针已覆盖**「关闭本会话后」的 mirror/state。

**已有 CLI、本轮证据未跑：**

- `miplay-cli devinfo <IP> [--auth legacy]`：解码 GetDeviceInfo KV（过滤账号/SN/MAC 等），可看 **groupId / deviceType / channel** 等非隐私字段  
- `miplay-cli channel <IP>`：只读 `GET_CHANNEL`（**不要**带 `--set`）

**现有二进制做不到（缺口，不是授权乱发命令）：**

- bootstrap 已发 `GET_MEDIA_INFO(0x0014)`，但 **ACK 载荷未入库/未打印** → 当前无法从 probe 看到 `mSourceName` / 曲目等「当前会话源」文案  
- 没有「只读打印对方会话源 IP」的已验证命令；**禁止**为此盲目试未知 cmd  
- 补齐 MEDIA_INFO 观测需要改代码；在根因未证前 **不**再打「仅堆日志」验收 APK

## 6. 唯一可验证下一步（R1）— 请你协调；我不连 LAN

**目的：** 在不动音箱配置/分组/重启/网络、不 Open/不推流的前提下，判断 LX06 空闲探针上的 state=2 是否伴随「分组或其它会话身份」可读痕迹，并与 L17A 对照。

**操作（同一虚拟屏、全程静音、不动 release）：**

1. 确认本验收 App / 自有 CLI **无**投送进程（无 ServiceRecord / 无 miplay-cli cast）。  
2. 对 **LX06 与 L17A 各执行一次**（仅只读）：  
   - `miplay-cli probe <IP>`  
   - `miplay-cli devinfo <IP> --auth legacy`  
   - `miplay-cli channel <IP>`（无 `--set`）  
3. （可选、非协议）目视米家/音箱 UI：LX06 是否显示正被其它手机/平板占用投送。只记录，不点解除。  
4. **不再** cast / Open；**不再** auth 矩阵；**不**延超时。

**若手机上现存 CLI 无 `devinfo` 子命令：** 不要换未知二进制灌设备；把 `miplay-cli` 无帮助/`usage` 原文交回即可，改由编码侧评估是否值得做**非验收包**的一次性只读工具——仍须你批准后才构建。

### 结果如何区分假设

| 观察 | 偏向 |
|------|------|
| LX06 `devinfo` 出现非空 **groupId**（或与立体声/多房间相关的 deviceType），L17A 无 | **H-group**：音箱侧仍挂分组身份；下一步只能是用户自行决定是否在 UI 解除（我们不擅自改） |
| 两边 groupId/channel 同构，仅 mirror/state 不同；UI 显示 LX06 被其它源占用 | **H-foreign-session**：会话被其它发送端占用；需该源正常结束，非本仓 CLOSE 可清 |
| 两边 group/channel/UI 均「空闲」，LX06 仍 1/2 | **H-local-sticky**：本地状态机粘滞；仍**禁止**重启/恢复出厂作「修复」；需另定只读或用户授权操作 |
| `devinfo`/`channel` 在 LX06 上失败或与 L17A 无差异且 UI 无占用提示 | 分组/外源假设**削弱**；下一刀才考虑是否值得最小代码露出 `GET_MEDIA_INFO` ACK（单独评审，不是再打诊断矩阵 APK） |

## 7. 本轮不做什么

- 不生成新 acceptance APK / 不堆日志版本  
- 不重复 Safety/Legacy/Auto 矩阵、不加长 reverse 超时  
- 不改音箱配置、不解除用户分组、不重启/恢复出厂、不改网络、不提权抓包  
- 无新设备证据前不宣称代码根因、不提交「猜测性协议补丁」
