# LX06：历史真成功 vs v7/v8 — 对照（已按 v8 验收纠正）

**结论边界：** 区分力与证伪清单；**不宣称根因 / LX06 已修。**  
v8 真机后全文见 `docs/LX06_V8_POSTMORTEM.md`。

## 纠正（重要）

历史成功 INITIAL 的 `mirror_mode=2,state=0` 采样在 **PLAY 之后**，与 v8 **pre-Open** **不等价**，**不能**据此声称「历史 Open 前必为 2/0」或把 pre-Open 1/2 直接写成根因。

当前有效同阶段对照仅：v8 同 Legacy 下 LX06 pre-Open **1/2 失败** vs L17A pre-Open **2/0 成功**（相关，非因果）。  
且成功流中首 PCM 后也会出现 `state=2`，故 **state=2 ≠ 故障**。

## 已锁定

- 历史真反向成功（04 Safety；07 安装 App Legacy）成立；「当前进程无 LX06 RTSP」不可扩读为历史从未成功。  
- v7：真实 Safety 仍 timeout → 「未选中 Safety」证伪。  
- v8：去 poll 后 LX06 仍 timeout；poll 非最初根因（故障始于 v4）。  
- C2 旧 CLI 亦 timeout → 削弱「仅 acceptance 包」单一说；非严格同哈希基线。

## 下一步

唯一实验 **R1**（只读 probe/devinfo/channel + 可选 UI，不 Open）：见 `LX06_V8_POSTMORTEM.md` §6。
