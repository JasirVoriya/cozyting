# 局域网 AirPlay 2 → MiPlay 网关（网桥）设计方案

> **状态：待实现 · 长期保留方案（Proposed / Deferred）**
> 本方案当前暂不启动编码，先作为架构蓝图入库保留。它定位为 Apple 生态（iPhone / iPad / macOS）的**零 App 局域网基础设施形态**，与移动端原生客户端（见 [IOS_CLIENT_DESIGN.md](IOS_CLIENT_DESIGN.md)）互补。

---

## 一、背景与方案价值

在 iOS 本机直跑方案中（`ReplayKit` 广播扩展），受限于苹果沙箱与系统安全模型，存在三项无法在单机层绕过的先天边界：
1. **无法捕获 Apple Music 等 FairPlay DRM 音源**；
2. **存在系统录屏红条，且为整机音频混音**（无法像 Android 那样按单 UID 屏蔽微信/短视频杂音）；
3. **无法在 iOS 控制中心原生 AirPlay 面板直接多选音箱**。

同时，若试图在同一台 iPhone 上由 App 自己模拟 AirPlay 接收端并回环投放，会撞上 iOS 沙箱禁止绑定 PTP 特权端口（UDP `319`/`320`）、系统同机自发现过滤、后台套接字冻结和 App Store 拒审四道硬墙。

**局域网网关（网桥）方案**将虚拟 AirPlay 2 接收端下沉至局域网内的常驻主机（如 NAS、Linux 小主机、树莓派、软路由或 macOS 主机），由宿主机面向局域网广播虚拟 AirPlay 2 设备，接收来自 iPhone / iPad / Mac 的原生 AirPlay 音频与控制事件，再直接调用本项目 **L1 Rust 核心（`core/`）** 经 MiPlay 协议毫秒级同频投送给局域网内的小米音箱。

### 核心收益
- **Apple 端零 App、零红条、全原生体验**：iPhone / iPad / Mac 无需安装任何客户端，锁屏后台 100% 原生，支持 **Apple Music、QQ 音乐、网易云音乐、播客、系统全局音频**。
- **控制中心原生多选与独立音量**：在 iOS 控制中心像勾选 HomePod 一样直接勾选多台虚拟小米音箱并分别调节音量。
- **纯局域网 · 零云端**：完全延续本项目架构红线，所有发现、解密、重采样、转码与 MiPlay 推流均在本地局域网闭环完成。

---

## 二、两种演进子模式（B1 极简原型 vs B2 完整网关）

| 维度 | 子模式 B1：单虚拟 AirPlay 入口（MVP 原型） | 子模式 B2：1:1 多虚拟 AirPlay 2 网关（完整形态） |
| --- | --- | --- |
| **虚拟设备数量** | 仅广播 **1 个**虚拟 AirPlay 1/2 设备（如 `MiPlay 全屋音箱`） | 发现局域网内 **N 台** MiPlay 音箱，动态广播 **N 个**虚拟 AirPlay 2 设备（如 `小爱音箱-0001`、`小爱音箱-0002`） |
| **选音箱方式** | 在宿主机 CLI / 极简局域网 Web 页勾选目标音箱组 | **直接在 iOS / macOS 控制中心 AirPlay 列表里勾选任意 1~N 台** |
| **独立调音量** | 在宿主机控制页调整 | **直接在 iOS 控制中心拖动单台设备的音量条**，映射为对应音箱 MiPlay 音量 |
| **时钟与协议要求** | AirPlay 1（RAOP）即可，**无需 `nqptp` / UDP 319·320** | 必须 **AirPlay 2**（需单个共享 `nqptp` 绑定 UDP `319`/`320`） |
| **推荐定位** | **阶段 0 验证原型**：最快跑通转码、延迟与 30 分钟稳定性 | **最终目标形态**：最贴合 Apple 生态原生直觉 |

---

## 三、核心架构设计：控制面 1:1 映射 + 媒体面合流单 `CastSession`

### 1. 为什么不能「N 个虚拟端各自独立推 N 条 MiPlay 会话」？
如果让每个虚拟 AirPlay 2 实例各自独立调用一次 MiPlay 建连：
- 两台小米音箱会分别拥有两套独立的 `EPOCH` 时钟与 `TIME_OFFSET`，**丢失本项目 L1 核心最关键的多箱毫秒级同频能力**，房间间会产生明显回声；
- 宿主机要对同一首歌执行 **N 倍的解密、44.1kHz→48kHz 重采样与 AAC 编码**，浪费算力与局域网带宽。

### 2. 「控制面 1:1 · 媒体面合流」拓扑

```text
iPhone / iPad / Mac（Apple Music / QQ 音乐 / 网易云等）
   │ 控制中心同时勾选「小爱音箱-0001」+「小爱音箱-0002」
   ▼
┌────────────────────────────────────────────────────────────────────────────┐
│ 局域网宿主机（Linux / NAS / 树莓派 / macOS · 同局域网常驻服务）              │
│                                                                            │
│  [共享单实例 nqptp] ◄── UDP 319 / 320 (IEEE 1588 PTP 时钟同步)             │
│         ▲ (共享内存时钟)                                                    │
│         ├────────────────────────────────────┐                             │
│         ▼                                    ▼                             │
│  [虚拟 AirPlay 2 端 #1]               [虚拟 AirPlay 2 端 #2]               │
│   name: 小爱音箱-0001                  name: 小爱音箱-0002                  │
│   port: 7000 · id: A1B2C3D40001       port: 7001 · id: A1B2C3D40002        │
│   role: Primary (送音频 + 控制)        role: Follower (仅送控制，丢冗余音频) │
│         │ PCM + 事件                         │ 仅启停/音量/TEARDOWN 事件    │
│         └───────────────┬────────────────────┘                             │
│                         ▼                                                  │
│             [BridgeCoordinator（Rust 编排层）]                              │
│               • 周期调用 core::discovery 维护虚拟设备表                     │
│               • 维护活跃音箱集合与 Primary 音频源选举/无缝切换              │
│               • AirPlay dB 音量 ──► MiPlay 0..100 逐台映射                  │
│                         │ 单路 PCM                                         │
│                         ▼                                                  │
│             [单路重采样 & AAC 编码管线]                                     │
│               44.1kHz PCM ──► 48kHz 立体声 ──► 256kbps 裸 AAC AU (21.333ms) │
│                         │ push_aac (单份流)                                │
│                         ▼                                                  │
│             [本项目 L1 Rust 核心：单个 core::engine::Engine / CastSession]  │
│               • 串行 reconcile 多箱扩组/退组（复用现有协调器）              │
│               • 单个 EPOCH + UDP TimerServer + OffsetBarrier 毫秒同频       │
└─────────────────────────┬──────────────────────────────┬───────────────────┘
                          │ MiPlay (8899 + RTSP/Audio)   │ MiPlay (同源时钟)
                          ▼                              ▼
               小米音箱 #1 (192.0.2.10)        小米音箱 #2 (192.0.2.11)
```

### 3. `BridgeCoordinator` 核心状态机规则

1. **设备发现与虚拟端生命周期**
   - 周期调用 [core/src/discovery.rs](../core/src/discovery.rs) + [core/src/device_catalog.rs](../core/src/device_catalog.rs) 发现局域网内支持 MiPlay（`apps=[5]`）的合法音箱。
   - 为每台音箱生成**确定性且跨重启稳定的 `airplay_device_id`**（基于音箱稳定标识派生，避免重启后 iOS 历史路由或名称失效）以及宿主机内唯一的监听端口（如 `7000 + slot`）。
   - 支持配置文件静态白名单/黑名单（例如默认排除电视或指定固定端口）。

2. **主音频源选举与无缝切换（Primary / Follower）**
   - **首台接入**：当虚拟端 `#1`（对应 `192.0.2.10`）收到 AirPlay `SETUP`/`RECORD` 进入播放态时，选举 `#1` 为 `Primary`，调用 [core/src/engine.rs](../core/src/engine.rs) 的 `create(false)` + `connect("192.0.2.10", ...)`，仅将 `#1` 的解码 PCM 接入重采样与 AAC 编码管线。
   - **动态扩组**：当用户在 iOS 控制中心追加勾选虚拟端 `#2`（对应 `192.0.2.11`）时，将 `#2` 标记为 `Follower`：
     - 立即调用 `engine::add_receiver(handle, "192.0.2.11")`，由 L1 协调器自动升级 `ref_function=multi_room` 并复用当前 `TIME_OFFSET` 同步扩组；
     - `#2` 仍须正常应答 iOS 侧的 RTSP / PTP / RTP 心跳与保活（否则 iOS 会认为连接失败并取消勾选），但其解出的音频帧直接丢弃，不重复送入编码器。
   - **主端退出无缝切换**：若用户取消勾选 `#1`（`TEARDOWN`）而 `#2` 仍在播，**立即将 `#2` 晋升为新 `Primary`** 接管 PCM 喂入，并调用 `engine::remove_receiver(handle, "192.0.2.10")`，保证 `#2` 持续播音不断流。
   - **全员退出**：当活跃集合变为空（或空闲超时），调用 `engine::stop(handle)` 释放所有音箱控制与媒体会话。

3. **独立音量与播控映射**
   - **音量映射**：AirPlay 协议下发的 `SET_PARAMETER volume` 为对数衰减 dB 值（典型范围 `-30.0 dB` ~ `0.0 dB`，`-144.0 dB` 表示静音）。网桥按感知映射曲线将其换算为 MiPlay 的整数 `0..100`，直接调用 `engine::set_volume(handle, speaker_ip, vol)`。
   - **切歌与暂停（`FLUSH`）**：收到 `Primary` 的 `FLUSH` 指令时，清空重采样器与未发出的本地编码队列，降低切歌与进度拖拽时的残留滞后。

---

## 四、底层 AirPlay 2 协议实现路线对比

在宿主机上实现「多虚拟 AirPlay 2 接收端」，有两条技术实现路径：

| 对比维度 | 路线 1（推荐）：编排托管 `shairport-sync` + `nqptp` | 路线 2（远期）：纯 Rust 内嵌 AirPlay 2 Receiver |
| --- | --- | --- |
| **协议覆盖与成熟度** | 社区长期维护，已成熟支持 AirPlay 2 缓冲音频、HomeKit 临时配对、FairPlay v3 解密与单机多实例共享 `nqptp` | 开源 Rust 生态尚无长期经 iOS 全版本实战打磨的完整 AirPlay 2 多房间接收端，需自行维护逆向协议栈 |
| **与 `miplay-bridge` 对接方式** | 每台音箱派生一个受控 `shairport-sync` 子进程：音频走 `stdout` / named pipe / Unix socket；启停、音量与元数据走内置 MQTT 或 metadata pipe | 同进程函数调用，零子进程管理 |
| **Apple iOS 升级抗风险能力** | **高**（上游社区快速跟进补丁，升级二进制即可） | **低**（iOS 改握手参数需本项目自行逆向修复） |
| **部署复杂度** | 中（用 Docker 镜像或宿主机包管理统一封装 `nqptp` + `shairport-sync` + `miplay-bridge`） | 低（单二进制） |

**建议策略**：**优先采用路线 1（Rust `miplay-bridge` 编排托管 `shairport-sync` + `nqptp`）**，把工程精力集中在合流调度、重采样编码与 L1 MiPlay 稳定投送上，而不是重复造脆弱的 AirPlay 2 逆向协议轮子。

---

## 五、宿主机网络、音频管线与部署规范

### 1. 单宿主机多虚拟 AirPlay 2 实例规则
在同一台 Linux / macOS 宿主机（单 IP）上挂载多个虚拟 AirPlay 2 实例时，必须满足：
1. **单 `nqptp` 守护进程**：全局仅运行 **1 个** `nqptp` 绑定 UDP `319` / `320`，所有虚拟实例通过共享内存读取时钟；
2. **实例强隔离四元组**：每个实例必须拥有独立的 mDNS 名称（`name`）、唯一 `airplay_device_id`、独立 RTSP TCP 端口（如 `7000`、`7001`…）和独立音频/元数据输出通道；
3. **macOS 宿主机注意事项**：macOS 系统自带「隔空播放接收器（AirPlay Receiver）」会占用 `319`/`320` 与 `7000` 端口；若部署在 macOS 上需先在「系统设置 → 通用 → 隔空投送与接力」中关闭系统自带隔空播放接收器。

### 2. 音频重采样与编码管线（宿主机侧）
- **输入**：`shairport-sync` 解码输出标准 44.1kHz 16-bit 立体声 PCM（每帧按固定块长读取）。
- **重采样**：转换为 **48kHz 立体声**（Rust 可选 `rubato` 高质量同步/异步重采样器，或 `soxr`）。
- **编码**：编码为 **48kHz 立体声 AAC-LC 256kbps 裸 AU（不含 ADTS 头，1024 samples / 21.333ms）**（Linux 可选 `fdk-aac` / `ffmpeg`，macOS 可调用原生 `AudioToolbox`）。
- **输出**：直接调用 `core::engine::push_aac` 喂入 L1 `CastSession`。

### 3. 推荐部署形态
- **Linux / NAS（Docker 优先）**：
  - 使用 `network_mode: host`（确保 mDNS 组播、UDP `319`/`320` 与 MiPlay 反向 TCP/UDP 均可直达局域网，不经过 NAT）；
  - 或在多实例 mDNS 遇路由器兼容性问题时，为容器配置 `macvlan` 独立局域网 IP。
- **Linux / 树莓派（裸机 `systemd`）**：
  - `nqptp.service`（先启动）→ `miplay-bridge.service`（依赖 `nqptp` 与网络就绪，自动拉起并托管各虚拟实例）。

---

## 六、延迟预算与诚实工程边界

### 1. 端到端延迟模型（估算，待实测）

| 环节 | 典型延迟 | 说明 |
| --- | --- | --- |
| AirPlay 2 协议缓冲 | ~2000 ms | AirPlay 2 缓冲音频模式的标准预缓冲窗口 |
| 重采样 + AAC 编码 + 管道缓冲 | ~30–60 ms | 44.1kHz → 48kHz 重采样 + 1024 samples AAC 凑帧 |
| MiPlay 接收端缓冲（见 [LATENCY_BUDGET.md](LATENCY_BUDGET.md)） | 800–1000 ms | 5GHz 默认 800ms，2.4GHz/未知默认 1000ms |
| **合计端到端延迟** | **约 2.8–3.0 s** | **适合音乐与播客；不适合视频追剧或实时游戏** |

> **高阶探索方向（实验级，未验证）**：由于 AirPlay 2 缓冲模式会提前约 2 秒把音频数据送达接收端，理论上若在接收端按 MiPlay 已知播放延迟提前消费缓冲区，可将总延迟压回 ~2 秒区间，甚至在实验条件下逼近与真 HomePod 混组的相位对齐；该方向存在切歌/seek 边界复杂度，暂不列入 MVP 承诺。

### 2. 诚实工程边界（不可回避的现实约束）
1. **依赖局域网常驻设备**：需要家里有一台 7×24 或听歌时开机的 Linux / NAS / 树莓派 / Mac 主机，不适用于外出移动场景。
2. **与真 HomePod 混组存在相位差**：多台小米音箱之间由本项目 L1 `OffsetBarrier` 保证毫秒级同频，但若同时把虚拟小米音箱与一台真 HomePod 勾选进同一 AirPlay 2 组，两者之间会有约 0.8–1.0s 的固定延迟差（回声）。
3. **非 MFi 逆向协议波动风险**：AirPlay 2 接收端属开源社区逆向实现，Apple 未来 iOS 大版本更新可能短暂影响配对或流握手，需跟随上游 `shairport-sync` / `nqptp` 升级维护。
4. **Apple Music 无线编解码边界**：iOS 向 AirPlay 2 多房间目标推送 Apple Music 时受苹果无线传输协议限制（多为 AAC 256kbps 封装），无法做到端到端 Hi-Res Lossless 源码直通。

---

## 七、分阶段实施与验收里程碑（未来启动时执行）

### 阶段 0：B1 单入口原型验证（最快闭环）
1. 在测试宿主机部署单个 `shairport-sync`（AirPlay 1 或 AirPlay 2）并输出 PCM 到标准输出 / 命名管道。
2. 新增轻量 Rust crate（如 `bridge/`）读取 PCM → 48kHz 重采样 → AAC-LC 裸 AU 编码 → 喂入现有 `core::engine::Engine` 推流至 1–2 台小米音箱。
3. 实测验证：音质无爆音/欠载、端到端实测延迟、切歌与暂停响应、连续播放 ≥30 分钟无时钟漂移与内存泄漏。

### 阶段 1：B2 多虚拟 AirPlay 2 网关与合流编排
1. 实现 `BridgeCoordinator`：自动同步 `core::discovery` 发现列表，为每台合法音箱维护独立端口与确定性 `airplay_device_id` 的受控 `shairport-sync` 实例。
2. 实现 `Primary` / `Follower` 音频合流选举与无缝切换，接入独立音量映射与 `FLUSH` 冲刷。
3. 实测验证：iOS / macOS 控制中心同时勾选 2 台虚拟音箱播放 Apple Music / 第三方音乐，确认单时钟同频无回声、单独拖动某台音箱音量准确生效、取消 Primary 后 Follower 不断流。

### 阶段 2：工程化打包与可观测性
1. 提供 `Dockerfile` / `docker-compose.yml`（含 `nqptp` + `shairport-sync` + `miplay-bridge`）与 `systemd` 模板。
2. 提供只读局域网状态接口/日志（展示发现音箱、当前 Primary/Follower 状态、各音箱 MiPlay 实时延迟 `ms` 与丢帧计数），严格保持零云端、无敏感信息落盘。
