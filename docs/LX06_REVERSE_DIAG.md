# LX06 反向 RTSP（v8 真机后）

**状态：LX06 未解决。不发新诊断 APK。** 详见 `LX06_V8_POSTMORTEM.md`。

## 已证伪

- late-join multi_room 充分（v5）  
- 仅 Safety 即修（v7 E1）  
- 去掉 Open 后 poll 即修（v8 C1）；poll 亦非 v4 起最初根因  
- 历史 INITIAL 2/0 ≡ 历史 pre-Open（阶段错误，作废）  
- state=2 ⇒ 故障（成功播放中亦会出现）

## 下一步唯一动作

R1：现有 `miplay-cli` 对 LX06/L17A 只读 `probe` + `devinfo` + `channel`（无 cast/Open；不改音箱）。  
结果如何区分：见 POSTMORTEM §6。
