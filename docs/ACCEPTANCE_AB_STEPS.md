# 受控 A/B 过滤验收（最小步骤）

**不接触用户第三方 App 内容。** 只用本仓库构建的三枚测试音源。  
**不通过 `adb shell appops` 等方式绕过 MediaProjection**；录屏授权保留系统对话框，由操作者确认。

## 安装（并存，不覆盖用户包）

| APK | applicationId | 说明 |
|---|---|---|
| `MiPlay发送端·验收` | `com.miplay.sender.acceptance` | 独立验收签名 |
| `受控音源A·1kHz` | `com.miplay.testtone.a` | 可捕获，1000Hz |
| `受控音源B·2kHz` | `com.miplay.testtone.b` | 可捕获，2000Hz |
| `受控音源·禁止捕获` | `com.miplay.testtone.deny` | `allowAudioPlaybackCapture=false`，1500Hz |

三者不同 UID（系统安装时分配）。发送端正式包名 `com.miplay.sender` / `.release` 不变。

## 量化判据（发送端 PCM 探针）

验收 UI / logcat 标签 `MiPlaySender` 的 `PCM_PROBE`：

- **选 A 且仅 A 播放**：主导应为 `A`，且 `energyA ≫ energyB`
- **选 A 且仅 B 播放**：主导不应为 `B`（期望静音或 OTHER，且无稳定 2kHz 主导）
- **选 deny**：即使 deny 在播，捕获不应出现稳定 1.5kHz 主导能量
- **若 `Dominant.SILENCE` / RMS≈0**：可能因媒体音量=0 或禁止捕获/未播放；**不能据此证明 UID 过滤**

## 推荐操作顺序（虚拟屏、尽量静音）

1. 媒体音量可先保持 0（听感静音）。若探针长期 SILENCE，将音量调至非零但仍很低，或确认音源 App 通知显示「正在播放」——过滤证据看数字域能量，不看是否听见。
2. 打开音源 A → 开始播放。
3. 打开验收发送端 → 音源选「选定 App」→ 选择 `受控音源A` → 勾选音箱（或仅本地看探针也可先不连音箱）→ 开始 → **在系统框确认投影授权**。
4. 观察 PCM 探针：应报 A。记录一帧 `RMS / energyA / energyB / dominant`。
5. 停止 A，改为只播 B（仍选中目标包为 A）：探针不应稳定报 B。
6. 停止会话；改选目标为 B；重新授权；只播 B：应报 B。只播 A：不应稳定报 A。
7. 改选 deny 包并重新授权；播放 deny：捕获应无明显目标能量（或静音）。**勿用 adb 改 capture 权限。**

## 自动化已覆盖（本机）

- `PcmProbeTest`：合成 A/B/静音的主导频判决
- `CaptureTargetResolverTest`：UID fail-closed
- 不替代真机投影授权与 OEM 捕获策略

## 明确不声称

未在真机跑通上述步骤前，不声称过滤/全品牌通过。本文件只提供可复现步骤与量化读数方法。
