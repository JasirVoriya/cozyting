# Project Rules (`cozyting` / 窝爱听)

## 0. 变更审批与 Git 分支工作流（最高优先级 · 强制）

### 0.1 未经明确允许，严禁直接改动项目
- 在获得用户明确允许前，**严禁**修改任何项目文件（代码、配置、文档等）或执行会改变仓库状态的操作。
- 默认仅做只读分析与排查，向用户输出**明确结论与推荐方案**（必要时附影响范围与备选方案），等待用户确认后方可开始实施改动。

### 0.2 强制基于 `main` 新建分支改动，完成后合并回 `main`
- 获得允许准备改动时，**每一次独立改动都必须遵循以下闭环**（统一通过下方 `0.3` 的独立 Git Worktree 协议执行）：
  1. **从主分支拉新分支与独立 Worktree**：基于最新 `main` 在 `.worktrees/` 下创建独立 worktree 与新分支（如 `feat/<topic>`、`fix/<topic>`、`docs/<topic>`、`chore/<topic>`），**严禁直接在主工作区或 `main` 分支上做改动**，也不得把不相关改动混入其他正在进行的分支。
  2. **在独立 Worktree 完成全部改动与验证**：编辑、测试、自检与 commit 均在该 worktree 与新分支上进行。
  3. **变基验证后串行合入主分支**：先在 worktree 内基于最新 `main` 完成 `git rebase main` 并重新验证通过，再在主工作区持锁使用 `git merge --no-ff <branch>` 合入 `main`（合并前确认遵循提交前自检规范；是否推送远程由用户确认）。

### 0.3 多 Agent 并发开发隔离规范（Git Worktree 强制协议）

为支持多个 Agent 会话并发开发并杜绝 Git 状态踩踏、文件覆盖、构建冲突与真机争抢，**所有 Agent 执行任何写入类改动时，无论当前是否存在其他会话，一律遵守以下协议**：

#### 1. 主工作区常驻干净 `main`（禁止在主目录直接改动）
- 仓库主目录（Main Worktree）**仅用于**：只读调研、创建/移除子 Worktree、以及最终串行合并回 `main`。
- **严禁**在主目录直接切功能分支、修改业务文件或残留未提交改动，确保任意时刻主目录都处于干净的 `main` 状态，随时可供任意会话安全合并。

#### 2. 隔离目录与命名规范
- 获得用户允许（满足 `0.1`）准备实施改动后，必须基于最新 `main` 在仓库内 `.worktrees/` 下创建独立工作树（`.worktrees/` 已在 `.gitignore` 中忽略）：
  - 分支名：`<type>/<topic>`（如 `feat/volume-sync`、`fix/mdns-timeout`、`docs/faq`）
  - 目录名：`.worktrees/<type>-<topic>`（将分支名中的 `/` 替换为 `-`）
  - 创建命令：
    ```bash
    git worktree add .worktrees/<type>-<topic> -b <type>/<topic> main
    ```
- **严格路径边界**：Worktree 创建后，本会话的**全部文件读写、代码检索、构建、测试与 Git 提交操作**，必须严格限定在 `.worktrees/<type>-<topic>/` 目录内；**严禁**修改主目录或其他 `.worktrees/*` 目录下的任何文件。

#### 3. 并发冲突预检与会话声明（一会话一文件）
- 使用 Git 公共目录下的独立目录存放活跃会话声明，避免声明文件本身发生并发写冲突：
  - 目录路径：`$(git rev-parse --git-common-dir)/agent-sessions/`
  - 本会话文件：`<type>-<topic>.txt`（仅由本会话创建、更新和删除）
- **开工前必检**：
  1. 执行 `git worktree list` 并只读扫描 `agent-sessions/*.txt`，查看是否存在其他进行中的会话。
  2. 将本会话信息写入 `agent-sessions/<type>-<topic>.txt`，内容包含：分支名、worktree 路径、创建时间、任务简述、**预计修改的目录/高冲突文件**、**是否需要占用 ADB 真机**。
  3. **冲突预警（先报后做）**：若发现与其他活跃会话存在以下重叠，必须先暂停并向用户报告、等待确认后再继续：
     - 共同修改高冲突公共契约文件（如 `AGENTS.md`、`core/src/ffi.rs`、`core/src/ffi_c.rs`、`core/include/cozyting_core.h`、`design/UI_SPEC.md`、`design/tokens.json`、`README.md`、`README_EN.md`）。
     - 共同需要占用同一台 ADB 真机。

#### 4. 本地忽略文件挂载（项目构建初始化）
- `.gitignore` 忽略的本地文件不会随 `git worktree add` 自动复制。创建 worktree 后，若主目录存在以下文件/目录，应挂载到新 worktree 对应路径（优先使用软链接 symlink；若目标文件系统不支持软链接，则改为只读拷贝，仅当目标不存在时创建）：
  - `.local`（读取本地环境约束，并发会话**禁止**同时修改 `.local/` 下同一文件）
  - `android/local.properties`
  - `android/keystore.properties`、`android/app/acceptance.keystore`（仅签名构建需要时）
- 严禁在提交时将软链接或忽略文件加入 Git 跟踪（须通过 `1.2` 提交前自检）。

#### 5. 构建产物与共享硬件隔离
- **构建产物独立**：每个 worktree 独立使用自身的 `target/`、`android/.gradle/`、`android/build/` 与 `android/app/build/`，禁止跨 worktree 共享可写编译输出目录。
- **外部临时缓存隔离**：若本地环境将 `CARGO_TARGET_DIR`、Gradle `project-cache-dir` / `buildDir` 等重定向到仓库外临时路径，**必须按 `<type>-<topic>` 追加独立后缀隔离**，严禁多个并发会话共用同一外部构建/缓存目录。
- **ADB 真机独占**：若已有活跃会话在声明文件中标记占用真机，其他会话**严禁**执行 `adb install`、启停应用或抓取独占日志，仅允许执行离线验证（如 `cargo test`、Android Lint、JVM 单测、离线编译），待对方释放或用户明确指令后再进行真机测试。

#### 6. 串行合入 `main`（原子锁 + 先 Rebase 后 `--no-ff`）
开发与验证可并行，但**合并回 `main` 必须严格串行**：
1. 在自己的 worktree 内完成全部改动、测试、双端核对（第 2 节）与隐私自检（第 1.2 节）并 `git commit`。
2. 获取原子合并锁（利用 `mkdir` 的跨进程原子性）：
   ```bash
   LOCK_DIR="$(git rev-parse --git-common-dir)/agent-merge.lock"
   mkdir "$LOCK_DIR"
   ```
   - 若 `mkdir` 失败，说明另一会话正在合入 `main`，必须等待重试或向用户汇报，**严禁强行删除他人的锁目录**。
   - 加锁成功后，写入 `$LOCK_DIR/owner.txt`（记录分支名与时间戳）。
3. 持锁期间执行合入：
   - 确认主目录 `git status --porcelain` 为空且当前位于 `main` 分支。
   - 在自己的 worktree 内先执行 `git rebase main`：
     - 若发生冲突，先释放锁、在自己 worktree 内解决冲突并**重新跑完验证**后，再重新申请锁合入。
     - 若 `main` 在期间有新提交导致 rebase 产生新基底，同样须重新验证通过后再合入。
   - 在主目录执行非快进合并，保留本次任务的分支合并记录：
     ```bash
     git merge --no-ff <type>/<topic>
     ```
4. 无论合并成功或中途退出，均须及时清理自己创建的 `$LOCK_DIR`（仅当 `owner.txt` 属于自己时）。

#### 7. 收尾与异常恢复
- **正常完成**：合入 `main` 后，在本会话内完成清理：
  1. 移除本会话 worktree：`git worktree remove .worktrees/<type>-<topic>`
  2. 删除已合入的临时分支：`git branch -d <type>/<topic>`
  3. 删除本会话声明文件：`$(git rev-parse --git-common-dir)/agent-sessions/<type>-<topic>.txt`
- **严禁越权清理**：禁止任何会话删除、重置或强制移除其他会话的 worktree、分支或声明文件。
- **任务中止/失败**：不得使用 `git worktree remove --force` 或 `rm -rf` 擅自丢弃未提交改动；应保留现场，在回复中明确告知 worktree 路径与分支名，交由用户决定继续或清理。

## 1. 个人开发环境与隐私隔离规范（强制）

本项目为公开开源仓库。**严禁**将任何个人开发环境配置、私密拓扑或个人身份信息提交到 Git 跟踪文件中。

### 1.1 本地私有目录（`.local/`）
- 所有仅针对开发者个人机器、宿主机/NAS、ADB 连接方式、特定测试设备约束、本地构建变通命令、个人局域网设备 IP/名称以及临时 AI 会话计划与调试日志，**必须统一放在仓库根目录的 `.local/` 文件夹内**（如 `.local/DEV_ENV.md`）。
- `.local/` 已在 `.gitignore` 中全局忽略，**禁止**通过 `git add -f` 将其纳入版本控制。

### 1.2 每次开发必须执行的两次检查
1. **开发/调试开始前**：
   - 先检查本地是否存在 `.local/DEV_ENV.md`（及 `.local/` 下其他规范文件）；若存在，按其中的本机环境约束与构建/调试方式执行。
   - 若在本次任务中新增了个人环境偏好或本机调试约束，**只能写入 `.local/`**，禁止写入 `docs/`、`README.md`、源码注释或提交信息。
2. **提交/推送前（每次必检）**：
   - 审查所有待提交变更（`git diff --cached` 与新增文件），确认**不包含**：
     - 个人宿主机/NAS/ADB 路径与连接约束、本机绝对路径（如 `/Users/...`、`/Volumes/...`、`/srv/...`、`/opt/cursor/...`）
     - 真实人名、真实设备尾号/序列号/MAC 地址、家庭或内网真实 IP（如 `192.168.31.x` 等）
     - 临时 AI 会话计划、个人对话时间点或本地缓存日志文件名
     - 签名密钥、口令、`keystore.properties`、`local.properties`、`.pcapng` 原始抓包文件
   - 单测、示例代码与文档中的占位数据统一使用 **RFC 5737 文档网段**（`192.0.2.x` / `198.51.100.x` / `203.0.113.x`）或通用示例地址（`192.168.1.10` / `192.168.1.100`）与匿名占位设备名（如 `小爱音箱-0001`、`A1B2C3D4`）。

## 2. Android & iOS 双端同步约束（强制红线）

本项目采用「**L1 共享 Rust 核心 + L2/L3 Android 与 iOS 双端原生**」架构。**后续所有功能、架构、协议、状态机与 UI/交互改动，必须同时兼顾 Android 与 iOS 两套工程**，严禁只考虑单端导致双端分裂：

1. **业务逻辑、协议与状态机（L1 优先下沉）**：
   - 凡与操作系统无关的逻辑（设备发现、过滤、路由状态机、并发协调、延迟归一化、协议控制等），必须优先实现在 L1 公共层 `core/`，并**同步维护** Android JNI（`core/src/ffi.rs`）与 iOS C ABI（`core/src/ffi_c.rs` + `core/include/cozyting_core.h`），保证 C11 头文件与 Rust 单测双通过。
2. **UI 布局、视觉与交互（`design/` 同步更新）**：
   - 任何界面排版、组件尺寸、滑块/按钮样式、色板 Token、图标或交互流改动，**必须同步更新跨端单一事实来源 `design/`**（`design/UI_SPEC.md`、`design/tokens.json`、`design/icons/*.svg`）。
   - 若某项能力存在平台差异（例如 Android 可按包名读取任意第三方 App 图标并 `getLaunchIntentForPackage` 跳转，而 iOS 受沙箱限制需按已知音源/URL Scheme 降级），必须在 `design/UI_SPEC.md` 中**明确写清 iOS 端对应的 SwiftUI 实现与降级边界**。
3. **双端工程同步演进**：
   - 每次改动提交前，除验证 Android 端外，须核对 iOS C ABI / `design/` 规范是否同步；待 iOS 原生工程目录落地后，核心功能与 UI 改动须双端同步实现与验证。

## 3. 工程红线

- **纯局域网 · 零云端**：不引入任何云端 API、账号登录或统计上报。
- **严格单 App 捕获（Fail-Closed）**：不得破坏单 UID 音频捕获校验，绝不静默退化为全系统录屏或麦克风采集。
- **API 29 真实兼容**：`minSdk = 29`，Android Lint `NewApi` 为 `fatal`，高版本 API 必须隔离在 `compat/` 封装层内。
- **16KB ELF 页对齐**：`arm64-v8a` `.so` 保持 `-C link-arg=-Wl,-z,max-page-size=16384`。
- **诚实测试边界**：不扩大未验证结论；编译通过 ≠ 全机型兼容，协议估算延迟 ≠ 声学实测。

## 4. 对外公开内容的 SEO 与 GEO 优化（强制）

凡涉及**对外公开发布**的信息内容（`README.md` / `README_EN.md` / `README_JA.md` 等多语言文档、GitHub Pages `docs/*.html`、`docs/llms.txt`、`sitemap.xml` / `robots.txt`、`CHANGELOG.md`、Release Notes、`promo/` 物料、仓库 Description / Topics 等），新增或修改时必须同时考虑 **SEO（搜索引擎优化）** 与 **GEO（生成式引擎优化，面向 ChatGPT / Gemini / Perplexity 等 AI 检索与引用）**：

1. **关键词与定位一致**：首屏/首段用一句话讲清「是什么、解决什么问题、适用于谁」，自然覆盖核心关键词（如 MiPlay、小爱音箱、Android 音频投送、局域网、单 App 捕获），多语言版本（至少中/英/日）语义对齐，禁止关键词堆砌。
2. **结构化、可被 AI 引用**：使用清晰的层级标题、短段落、列表与表格；关键事实（支持机型、系统要求、限制、延迟数据）以可独立摘录的陈述句给出，并注明测试边界，遵守「诚实测试边界」红线。
3. **FAQ 与对比**：面向常见提问（"如何把安卓手机声音投到小爱音箱"等）提供问答式内容，便于搜索摘要与 AI 答案直接引用。
4. **Pages 技术 SEO**：每个页面须有唯一 `<title>`、`meta description`、`canonical`、`lang`/`hreflang`、Open Graph / Twitter Card、合适的 JSON-LD（如 `SoftwareApplication`、`FAQPage`）；图片带有描述性 `alt`；新增页面同步更新 `sitemap.xml`，必要时更新 `robots.txt`。
5. **GEO 入口同步**：公开内容的核心事实变化时，同步更新 `docs/llms.txt`，确保 AI 抓取到的是最新、准确、带链接的摘要。
6. **链接与权威性**：内部互链（README ↔ Pages ↔ docs），使用稳定的绝对链接；附 License、版本号、更新日期等可信信号。
7. **隐私红线优先**：SEO/GEO 优化不得违反第 1 节隐私隔离规范，示例数据仍须使用占位值。

## 5. 全链路多语言（i18n）规范（强制）

凡面向用户展示的**客户端 App、GitHub Pages 官网/文档、以及宣传视频与对外视觉物料**，均须原生支持多语言，严禁新增仅支持单一语言的硬编码实现：

1. **基线语言与可扩展架构**：
   - 默认基线至少同步维护 **简体中文（`zh-CN`）**、**英文（`en`）** 与 **日文（`ja`）** 三种语言，且数据结构与目录组织须便于后续无痛扩展其他语种。
   - 任何新增或变更功能、页面或分镜时，必须**同步补全全部已支持语言（至少中/英/日）的文案与资源**，禁止仅更新单一语言导致多语种漂移。
   - 品牌与协议专有名词（如 `CozyTing`、`MiPlay`、`AGPL-3.0`）保持统一规范，其余面向用户的文本禁止跨语种混杂或残留未翻译占位符。

2. **App 双端（Android & iOS + `design/`）**：
   - **禁止硬编码文案**：Android 端所有用户可见文本统一放入 `res/values/`、`res/values-en/`、`res/values-ja/` 等 `strings.xml`（正确处理格式化占位符与复数）；iOS 端同步使用 `Localizable.xcstrings`（或 `.lproj`），并与 `design/UI_SPEC.md` 保持文案键与交互一致。
   - **核心层与文案解耦**：L1 Rust 核心（`core/`）对外输出结构化状态/错误码而非硬编码自然语言句子，由各端按当前语言本地化展示。
   - **语言切换与排版适配**：支持跟随系统语言（及按需应用内切换）；UI 布局须同时兼容中文、英文长词与日文断行/字宽特性，避免按钮、标签、弹窗文本截断或重叠；UI 预览/截图脚本应支持按语种输出校验。

3. **GitHub Pages 与公开文档（`docs/` & `README`）**：
   - **完整多语言与切换入口**：GitHub Pages（`docs/index.html`、`docs/architecture.html` 及后续新增页面）须提供至少中/英/日三语完整版本与显式语言切换入口，同步维护对应多语言 README（`README.md`、`README_EN.md`、`README_JA.md` 等）。
   - **与 SEO / GEO 协同**：各语言页面具备对应的 `<html lang>`（`zh-CN` / `en` / `ja`）、唯一 `<title>`、`meta description`、`canonical`、`hreflang`（含 `x-default`）、`og:locale` 与 JSON-LD；同步更新 `docs/sitemap.xml` 与 `docs/llms.txt`。
   - **媒体资源联动**：Pages 中嵌入的界面截图、社交预览图与宣传视频，须按页面语种自动匹配对应语言版本资源。

4. **宣传视频与视觉物料（`promo/`）**：
   - **文案字典化**：`promo/`（Remotion 工程）中所有大字标题、功能标签、对比卡片、搜索演示与片尾文案须抽离至独立多语言字典（至少包含 `zh-CN` / `en` / `ja`），禁止在 `scenes/` 中硬编码单语种字符串。
   - **实机素材与成片按语种独立产出**：`<UiSlice>` / 实机界面截图素材按语种隔离管理，支持按语言参数或独立 Composition 分别渲染对应语种完整成片（如 `cozyting-promo-zh.mp4`、`cozyting-promo-en.mp4`、`cozyting-promo-ja.mp4`），并校验中/英/日字体回退（避免缺字或异体字混用）与画面留白；若后续引入字幕或配音，须按语种同步提供（如 `.vtt`/`.srt` 或独立音轨）。

5. **提交前多语言自检**：
   - 涉及 App UI、Pages 或 `promo/` 的改动，提交前须核对中/英/日及所有已支持语言的文案键完整、无硬编码遗漏、无排版溢出，且所有示例数据仍遵守第 1 节隐私红线。
