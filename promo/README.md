# 窝爱听（CozyTing）多语言产品宣传片工程（Remotion · zh-CN / en / ja）

本目录包含 **窝爱听（CozyTing）** 的 60 秒 1080p 60fps 纯视觉产品宣传视频源码，原生支持 **简体中文（`zh`）**、**英文（`en`）** 与 **日文（`ja`）** 三语独立成片。全片**零旁白、零人声说教**，文案全部抽离至 `src/i18n/`，并自动按语种匹配 `docs/assets/screenshots/{,en/,ja/}` 裁切的实机界面素材与中/英/日独立字体回退栈。

## 1. 视觉与分镜结构（120 BPM · 30 小节 · 60 秒）

| 章节 | 时间轴 | 帧区间 (`60fps`) | 核心用户价值 & 实机联动动效 |
| :--- | --- | --- | --- |
| **01 · 3 秒高燃 4 连快切 × 品牌定格** | `00:00 – 00:06` | `0 – 360` | `0.0–3.0s` 四连 0.75s（45 帧）卡点快切（任意安卓直连小爱 → 音乐 App 自动切源 → 刷视频回语音音箱照常放歌 → 63 台同频与 150ms 热切），`0.15s` 瞬出大字；`3.0–6.0s` 啪地定格亮出窝爱听 CozyTing Logo + 实机主界面与简介（同时作为三语视频封面帧） |
| **02 · 打破品牌限制** | `00:06 – 00:12` | `360 – 720` | 6 大主流安卓品牌卡 + 通用 Android 通栏卡注入真机，点击勾选音箱卡片弹出并直连小米 / 小爱音箱 |
| **03 · 自动切音源 × 刷视频不打断** | `00:12 – 00:22` | `720 – 1320` | 前半拍演活 3 款主流音乐 App 自动识别无缝切换；后半拍左右对称实景对照：手机本地播放短视频/语音 vs 客厅音箱音乐不断不串音 |
| **04 · 全屋同频 × 4 档延迟热切** | `00:22 – 00:36` | `1320 – 2160` | 前半拍突出多音箱卡片与独立音量、点亮全屋 4 房与最多 `63 台` 计数；后半拍真机切换 4 档延迟卡片（`800ms → 400ms → 250ms → 150ms`），右侧全屋房间延迟徽标、缓冲条与同频波形实时联动响应 |
| **05 · 无感直投 · 手机静音 · 零云端** | `00:36 – 00:42` | `2160 – 2520` | 单机强压暗聚焦「无感模式」「投送时手机静音」2 个真机开关特写，配合右侧纯局域网直连 · 零云端隐私图解 |
| **06 · 深浅双主题一键切换** | `00:42 – 00:48` | `2520 – 2880` | 放大真实主题切换条，轻触触发径向光波将三联真机从 Dark 无缝擦除为 Light |
| **07 · 搜索直达** | `00:48 – 00:52` | `2880 – 3120` | 居中 GitHub 搜索框逐字键入 `CozyTing` 并展开官方分发仓结果预览，拟真鼠标指针悬停点击搜索按钮切入结尾 |
| **08 · 产品名片永久定格** | `00:52 – 01:00` | `3120 – 3600` | 左侧深/浅双主题主界面真机，右侧窝爱听徽标、简介与 GitHub 搜索框（`JasirVoriya/cozyting-releases`），最终帧永久清晰停留在屏幕 |

## 2. 多语言构建与渲染

依赖环境：Node.js 20+、`ffmpeg`、`ffprobe`。

```bash
# 1. 安装依赖
npm install

# 2. 类型检查
npm run typecheck

# 3. 程序化合成 48kHz 立体声无旁白配乐（60 秒，输出至 public/music.wav）
npm run music

# 4. 从 ../docs/assets/screenshots/{,en/,ja/} 裁切三语深/浅色实机界面素材（输出至 public/ui/{zh,en,ja}/）
npm run assets

# 5. 按语种抽帧验收排版（前 5 秒快切与品牌定格帧：24 68 112 158 260）
node scripts/stills.mjs --locale zh 24 68 112 158 260
node scripts/stills.mjs --locale en 24 68 112 158 260
node scripts/stills.mjs --locale ja 24 68 112 158 260

# 6. 渲染三语 1080p 60fps MP4（输出至 out/cozyting-promo-{zh,en,ja}.mp4）
npm run render:zh
npm run render:en
npm run render:ja
# 或一次全量渲染：
npm run render:all
```

## 3. Web 官网压缩发布参数

GitHub Pages（`docs/assets/video/`）嵌入版本采用 `1280×720 30fps H.264 +faststart` 压缩并同步生成对应语种封面图：

- `docs/assets/video/cozyting-promo.mp4` & `cozyting-promo-poster.jpg`（简体中文默认）
- `docs/assets/video/cozyting-promo-en.mp4` & `cozyting-promo-en-poster.jpg`（英文）
- `docs/assets/video/cozyting-promo-ja.mp4` & `cozyting-promo-ja-poster.jpg`（日文）
