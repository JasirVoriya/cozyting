# v8 真机后 + R1 预检纠正

**不宣称根因；不宣称已查到设备状态；不连 LAN。**

## 对「只读 probe」的纠正（R1 预检已核实）

| 入口 | 结束行为 | 能否称为无副作用只查询 |
|------|----------|------------------------|
| `probe` | `runtime_writer().close()` → 协议 **CLOSE 0x0002** | **否** |
| `devinfo`（源码） | 同上 CLOSE | **否**；且现存手机 CLI **help 未列出** |
| `channel`（源码） | `command_then_close` → CLOSE | **否**；help 未列出 |
| **`inspect`（新）** | 仅 **TCP shutdown**；allowlist 锁定无 CLOSE/Open/SET_*；`media` 仅 `source_name`+`keys_present` | 专为状态调查；**仍不证明**对音箱其它会话零影响（不猜固件） |

`ControlSession` **无** Drop→CLOSE；正式播放路径的 CLOSE 语义未改。  
**无证据**断言 CLOSE「只作用本连接」；此前 probe 前后 state 不变也不能独立证明无副作用。

## 已证伪（摘要）

Safety 未选中；仅 Safety；去 poll；INITIAL≡preOpen；state=2⇒故障；probe=无 CLOSE 只读。

## 下一步（由你协调，唯一一次）

对 LX06 与 L17A 各跑 **一次**：

```text
miplay-cli inspect <IP> --auth legacy
```

对照 JSON：`mirror_mode`/`state`/`group_id*`/`device_type`/`channel_tlv`/`media.source_name`（absent|empty|nonempty）/`tx_commands`（须无 `0x0002`）/`ended_with=tcp_shutdown`。  
不解除分组、不重启、不改网络；不 Open。静音、不动 release。
