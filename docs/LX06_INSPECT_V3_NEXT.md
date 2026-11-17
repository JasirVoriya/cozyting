# inspect-v3 双机事实 → 源码判断 → 唯一下一步

依据：独立报告 + LX06/L17A 脱敏 JSON（2026-11-17 16:36–16:37 Asia/Shanghai）。  
**不宣称根因；不打新包；不连 LAN。**

## 1. 对照事实（已锁定）

| | LX06 | L17A |
|--|------|------|
| mirror/state/volume | **1 / 2 / 0** | **2 / 0 / 0** |
| group_id | `""`，present=**false**（键在且空） | 同左 |
| deviceType / channel_tlv | `"4"` / center | 同左 |
| media | status=`not_received`，source_name=`null` | 同左 |
| GET_CHANNEL | received=false，payload=null | 同左 |
| tx / ended_with | allowlist 一致，`tcp_shutdown`，无 CLOSE | 同左 |

## 2. 对 R1 假设

- **分组仍挂着（靠非空 groupId）**：两台皆空串 → **削弱**；不能反证外源会话。
- **media/CHANNEL 推断空闲或占用**：**禁止**；两台同为 not_received。
- **「两台都空闲且仅 LX06 为 1/2 → 本地粘滞」**：**不可走**（空闲未证）。
- 唯一仍成立的跨设备差异：LX06 `1/2` vs L17A `2/0`（相关，非因果）。

## 3. MEDIA/CHANNEL 无 ACK：源码结论

inspect 在同一 write 批次发出（空载荷）：

`GET_MIRROR(2) → GET_VOLUME(3) → GET_MEDIA_INFO(4) → GET_STATE(5) → GET_CHANNEL(6)`，

然后最多等 **5s**，且 **只接受精确 `(cmd, seq)`**；其它业务帧会直接 `unexpected` 失败。

实测 **exit=0** 且已填 mirror/volume/state → 窗口内 **确实收到了 seq 2/3/5 的 ACK**；若曾出现「错 seq / 错 cmd 的 MEDIA 或 CHANNEL 帧」，按现逻辑会整次失败，与成功退出矛盾。

因此：

| 猜测 | 判断 |
|------|------|
| 等待窗口太短导致「漏收已到达的 ACK」 | **证据不足**；同窗内其它 ACK 已到，且错帧会失败而非静默丢弃 |
| 发包序列/空载荷格式「写错命令」 | **证据不足**；同批 `Frame::empty` 的 MIRROR/VOLUME/STATE 已成功 |
| 音箱在此路径上未发 MEDIA/CHANNEL ACK | **与观测相容**（两台相同），但是音箱行为假说，**不是**已证 inspect 缺陷 |
| 正式 `bootstrap_legacy` 也发 `GET_MEDIA_INFO` 却 **不处理** `0x0015` | 旁证：历史上引导路径同样不依赖 MEDIA ACK；不能据此把「无 MEDIA ACK」写成 LX06 根因 |

**结论：无可证的 inspect 可观测缺陷需要再打诊断包。** 不加长超时、不重排命令、不引入会发 CLOSE 的 probe。

## 4. 唯一下一步（R2：用户目视米家，我们不动手）

**请用户（或协调方）自己打开米家 / 音箱本机界面查看，不要点击断开、解除分组、重启或改设置。**

问且只记三点：

1. **LX06** 当前是否显示正被「手机投送 / 妙播 / 正在播放」占用？（是 / 否 / 看不清）  
2. 若「是」，占用方是否像 **本验收手机以外的设备**？（是 / 否 / 看不清）  
3. **L17A** 用同一问法各记一次（对照）。

**为何有区分力（且不越界）：**

- 协议侧 media/CHANNEL 两台同为 not_received，**无法**再用协议判断占用；UI 占用是唯一仍允许、且不改音箱配置的外源线索。  
- 若 **LX06=占用且 L17A=未占用**，与协议 `1/2` vs `2/0` 同向，**支持**外源会话相关假说（仍非根因证明）。  
- 若 **两台都未占用**，外源 UI 假说 **削弱**；仍禁止把重启/解组当修复。  
- 若 **看不清 / 两台都占用**，本步无结论，停止，不升级为矩阵试验。

不做：重复 auth、延超时、双机推流、新 CLI/APK、提权抓包、代为操作米家。
