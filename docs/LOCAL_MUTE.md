# 本机静音方案（投送到音箱时）

## 目标

勾选音箱投送时：手机扬声器/耳机不再出声，但 `AudioPlaybackCapture` 仍能采到源 App 音频。

## 选用 API

```text
AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE, flags=0)
AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_UNMUTE, flags=0)
# 恢复时尽量 setStreamVolume(STREAM_MUSIC, savedIndex, 0)
```

实现：`LocalOutputMute` + `AndroidStreamMusicVolume`；投送成功后 `beginMuteForCast()`，停止时 `restore()`。

## 为何不能直接宣称成功

STREAM_MUSIC mute/音量衰减进入 AudioFlinger 的 stream 路径；PlaybackCapture 混音是否在衰减**之前**取流，**因机型/ROM 而异**。官方公开文档未保证「mute 后 capture 仍有声」。

因此：

1. mute 后进入 `PENDING_VERIFY`
2. 用捕获 PCM 的 RMS 作探针（与听感无关）
3. 若曾有能量基线，随后连续多拍低于阈值 → **立即 UNMUTE**，状态 `UNAVAILABLE_CAPTURE_SILENCED`
4. 仅当 mute 后捕获仍有能量 → `ACTIVE_VERIFIED`（估计成功）
5. 始终近零且超过等待拍数 → `UNVERIFIED_NO_CAPTURE_ENERGY`（**明确未验证，不假通过**）

**无探针通过证据时，UI/文档不得写「已做到本机静音且捕获仍有声」。**

验收变体：`restoreVolumeIndex=false`，停止投送不把本机音量索引拉回 mute 前值。

## 失败回退

| 状态 | 含义 | 本机出声 |
|------|------|----------|
| `ACTIVE_VERIFIED` | mute + 捕获仍有能量 | 否（估计） |
| `UNAVAILABLE_CAPTURE_SILENCED` | mute 连带静音捕获，已回退 | **是** |
| `UNVERIFIED_NO_CAPTURE_ENERGY` | 捕获持续近零，未能验证 | 已请求 mute（未假通过） |
| `PENDING_VERIFY` | 等待源 App 出声基线 | 已请求 mute |
| `RESTORED` / `IDLE` | 未投送或已恢复 | 是 |

不做：重启音箱、改系统路由权限、提权、`MODIFY_AUDIO_ROUTING` 私有 API。

## 日志对照

`adb logcat -s MiPlaySender:I` 中的 `LOCAL_MUTE status=… api=… msg=…`
