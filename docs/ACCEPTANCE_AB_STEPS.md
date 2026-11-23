# 受控 A/B 过滤验收（最小步骤）

**不接触用户第三方 App 内容。** 只用本仓库构建的三枚测试音源。  
**不通过 `adb shell appops` 等方式绕过 MediaProjection**；录屏授权保留系统对话框，由操作者确认。

## 安装（并存，不覆盖用户包）

| APK | applicationId | 说明 |
|---|---|---|
| `妙投·验收` | `com.miaocast.app.acceptance` | 独立验收签名 |
| `受控音源A·1kHz` | `com.miplay.testtone.a` | 可捕获，1000Hz |
| `受控音源B·2kHz` | `com.miplay.testtone.b` | 可捕获，2000Hz |
| `受控音源·禁止捕获` | `com.miplay.testtone.deny` | `allowAudioPlaybackCapture=false`，1500Hz |

三者不同 UID（系统安装时分配）。发送端正式包名 `com.miaocast.app` 不变。

## 量化判据（发送端 PCM 探针）

验收 UI / logcat 标签 `MiaoCast` 的 `PCM_PROBE`：

- **选 A 且仅 A 播放**：主导应为 `A`，且 `energyA ≫ energyB`
- **选 A 且仅 B 播放**：主导不应为 `B`（期望静音或 OTHER，且无稳定 2kHz 主导）
- **选 deny**：即使 deny 在播，捕获不应出现稳定 1.5kHz 主导能量
- **若 `Dominant.SILENCE` / RMS≈0**：可能因媒体音量=0 或禁止捕获/未播放；**不能据此证明 UID 过滤**

## 推荐操作顺序（虚拟屏；本地过滤可不连音箱）

1. 打开验收 App「妙投·验收」→ **选择投送的 App** → 选 `受控音源A`（须已安装并正在播放）。
2. 点 **「本地音频过滤验收（不连接音箱）」**（不要被「请勾选音箱」挡住；此入口不要求音箱）。
3. 在**系统投影授权框**由你确认（不可复用旧 token；勿用 adb appops）。
4. 看界面/通知/`PCM_PROBE`：选 A 且 A 在播时应主导 `A`。
5. 只停 A 改播 B（目标仍为 A）：不应稳定报 `B`。
6. 停止本地验收 → 改选目标 B → 再点本地验收并**重新授权** → 只播 B 应报 `B`。
7. 目标改为 deny 并重新授权：播放 deny 应无明显主导能量。
8. **若长期 SILENCE**：不能证明过滤（音量0/未播放/禁止捕获）。过滤证据看数字域能量比，不看是否听见。

音箱投送路径仍需勾选音箱；与本地过滤入口分离。

## 自动化已覆盖（本机）

- `PcmProbeTest`：合成 A/B/静音的主导频判决
- `CaptureTargetResolverTest`：UID fail-closed
- 不替代真机投影授权与 OEM 捕获策略

## 明确不声称

未在真机跑通上述步骤前，不声称过滤/全品牌通过。本文件只提供可复现步骤与量化读数方法。
