# 真机受控验收步骤（需你协调操作）

前提：仅隔离虚拟屏、媒体音量全静音、不锁屏、不强制整机 Doze、不改用户网络；不点系统对话框时标记等待。

## 准备

1. 安装本交付的 `app-debug.apk`（包名 `com.miplay.sender`，不覆盖用户已有 release）。
2. 准备受控音源 App A / B（不同 UID、可播放固定频率测试音）；另备「拒绝捕获」测试包（`ALLOW_CAPTURE` 关闭）。
3. 两台音箱与手机媒体音量均为 0；确认无其他发送端占用。

## App 选择与 UID

1. 打开发送端 → 音源选「选定 App 音频」→「选择投送的 App」→ 选 A。
2. 勾选音箱 → 开始投送 → 在系统框中由你确认录屏授权。
3. 仅 A 播放时，音箱侧应出现 A 的特征；B 播放时不应进入（以频谱/可观测 PCM 为准，不以静音 UI 代替）。
4. 清除选择后不选 App 直接开始：应失败提示，不得全系统捕获。
5. 卸载 A 或重装后开始：应 fail-closed 并要求重选。
6. 切换目标到 B：旧会话停止，必须重新授权。

## 授权与生命周期

1. 拒绝 MediaProjection：明确提示，无残留服务。
2. 投送中从系统设置撤销投影：清理录制/编码/锁/会话。
3. 拒绝通知权限：提示可见性影响，仍可从 App 内停止。
4. 拒绝 RECORD_AUDIO：不能开始 App 捕获；文案须说明非麦克风用途。

## 后台（不承诺全品牌）

1. 可选打开电池优化豁免入口（你确认对话框）。
2. App 退到后台（仅虚拟屏生命周期）：记录是否断流；HyperOS Greeze/WakeLock DISABLED 如实记录，不宣称已彻底修复。

## 回归

暂停/恢复、加入/移除、通知栏停止、断线重连（可用 debugDrop）在静音源上各走一遍。

## 双机晚加入（speakers-1452 回归）

1. 先只勾选 L17A 起播至 `phase=streaming`（允许静音 AAC；勿把 PCM=0 当音乐通过）。
2. 会话中再加入 LX06。日志须出现：
   - `late-join ref_function single_room → multi_room`（若首台为自动/single_room）；
   - `preparePlayback + SetPlaySource ref=multi_room`；
   - `Open wfd://…, … ref=multi_room, listen=…`；
   - `post-Open control`（有/无立即 ACK 均记录 cmd/seq/len，无载荷正文）。
3. 成功判据：LX06 在既有 10s 反向等待内完成 RTSP，进入 streaming；**不得**靠加长超时“通过”。
4. 若仍 `timeout: reverse RTSP connection`：保留上述日志 + 可选手机侧对广告端口的 `tcpdump`/`ss -l`（本 VM 不连用户 LAN）。此时 H1（ref_function）被证伪，需另查音箱→手机入站可达性或 Open 拒绝原因。
5. PCM 全 0 / 未播 A/B 时：**不得**声称真音乐、隔离或同步通过。
