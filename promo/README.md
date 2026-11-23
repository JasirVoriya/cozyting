# 妙投（MiaoCast）多语言产品宣传片工程（Remotion · zh-CN / en / ja）

本目录包含 **妙投（MiaoCast）** 的 62 秒 1080p 60fps 纯视觉产品宣传视频源码，原生支持 **简体中文（`zh`）**、**英文（`en`）** 与 **日文（`ja`）** 三语独立成片。全片**零旁白、零人声说教**，文案全部抽离至 `src/i18n/`，并自动按语种匹配 `docs/assets/screenshots/{,en/,ja/}` 裁切的实机界面素材与中/英/日独立字体回退栈。

## 1. 视觉与分镜结构（120 BPM · 31 小节 · 62 秒）

| 章节 | 时间轴 | 帧区间 (`60fps`) | 核心用户价值 & 实机联动动效 |
| :--- | --- | --- | --- |
| **01 · 破光登场** | `00:00 – 00:08` | `0 – 480` | 声波涟漪汇聚为妙投同心扬声器 Logo，展开对应语种 `01-dark` 实机主界面，顶栏与专辑播放器卡片 2.5D 悬浮并实时推进播放进度 |
| **02 · 打破品牌限制** | `00:08 – 00:14` | `480 – 840` | 6 大主流安卓品牌卡 + 通用 Android 通栏卡注入真机，点击勾选音箱卡片弹出并直连小米 / 小爱音箱 |
| **03 · 自动切音源 × 刷视频不打断** | `00:14 – 00:24` | `840 – 1440` | 前半拍演活 3 款主流音乐 App 自动识别无缝切换；后半拍左右对称实景对照：手机本地播放短视频/语音 vs 客厅音箱音乐不断不串音 |
| **04 · 全屋同频 × 4 档延迟热切** | `00:24 – 00:38` | `1440 – 2280` | 前半拍突出多音箱卡片与独立音量、点亮全屋 4 房与最多 `63 台` 计数；后半拍真机切换 4 档延迟卡片（`800ms → 400ms → 250ms → 150ms`），右侧全屋房间延迟徽标、缓冲条与同频波形实时联动响应 |
| **05 · 无感直投 · 手机静音 · 零云端** | `00:38 – 00:44` | `2280 – 2640` | 单机强压暗聚焦「无感模式」「投送时手机静音」2 个真机开关特写，配合右侧纯局域网直连 · 零云端隐私图解 |
| **06 · 深浅双主题一键切换** | `00:44 – 00:50` | `2640 – 3000` | 放大真实主题切换条，轻触触发径向光波将三联真机从 Dark 无缝擦除为 Light |
| **07 · 搜索直达** | `00:50 – 00:54` | `3000 – 3240` | 居中 GitHub 搜索框逐字键入 `MiaoCast` 并展开官方分发仓结果预览，拟真鼠标指针悬停点击搜索按钮切入结尾 |
| **08 · 产品名片永久定格** | `00:54 – 01:02` | `3240 – 3720` | 左侧深/浅双主题主界面真机，右侧妙投徽标、简介与 GitHub 搜索框（`JasirVoriya/miaocast-releases`），最终帧永久清晰停留在屏幕 |

## 2. 多语言构建与渲染

依赖环境：Node.js 20+、`ffmpeg`、`ffprobe`。

```bash
# 1. 安装依赖
npm install

# 2. 类型检查
npm run typecheck

# 3. 程序化合成 48kHz 立体声无旁白配乐（62 秒，输出至 public/music.wav）
npm run music

# 4. 从 ../docs/assets/screenshots/{,en/,ja/} 裁切三语深/浅色实机界面素材（输出至 public/ui/{zh,en,ja}/）
npm run assets

# 5. 按语种抽帧验收排版（可选）
node scripts/stills.mjs --locale zh 380 650 1000 1320 1650 2050 2460 2850 3150 3680
node scripts/stills.mjs --locale en 380 650 1000 1320 1650 2050 2460 2850 3150 3680
node scripts/stills.mjs --locale ja 380 650 1000 1320 1650 2050 2460 2850 3150 3680

# 6. 渲染三语 1080p 60fps MP4（输出至 out/miaocast-promo-{zh,en,ja}.mp4）
npm run render:zh
npm run render:en
npm run render:ja
# 或一次全量渲染：
npm run render:all
```

## 3. Web 官网压缩发布参数

GitHub Pages（`docs/assets/video/`）嵌入版本采用 `1280×720 30fps H.264 +faststart` 压缩并同步生成对应语种封面图：

- `docs/assets/video/miaocast-promo.mp4` & `miaocast-promo-poster.jpg`（简体中文默认）
- `docs/assets/video/miaocast-promo-en.mp4` & `miaocast-promo-en-poster.jpg`（英文）
- `docs/assets/video/miaocast-promo-ja.mp4` & `miaocast-promo-ja-poster.jpg`（日文）
