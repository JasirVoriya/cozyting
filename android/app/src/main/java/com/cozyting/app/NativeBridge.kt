package com.cozyting.app

/** 对应 core/src/ffi.rs。所有阻塞调用（discover/probe/start/setVolume/addReceiver）都必须在后台线程调用。 */
object NativeBridge {
    init {
        // app_process 调试时没有 APK 的 nativeLibraryDir，可用 -Dcozyting.lib=/绝对路径/libcozyting_core.so
        val explicit = System.getProperty("cozyting.lib")
        if (explicit != null) System.load(explicit) else System.loadLibrary("cozyting_core")
        init()
    }

    @JvmStatic external fun init()
    /** 每行 "ip\tname\tmodel\tadvertises_miplay\tservice\tapps"；出错时 "ERROR\t原因"。 */
    @JvmStatic external fun discover(localIp: String, timeoutMs: Int): String
    /** 跨端结构化发现 JSON（由 Rust core 统一分类、过滤并合并历史缓存）。 */
    @JvmStatic external fun discoverJson(localIp: String, timeoutMs: Int, cachedJson: String): String
    /** 跨端结构化发现 JSON（支持按需并发只读 8899 `miName`/`roomName` 查询；forceRefreshNames=true 用于手动刷新）。 */
    @JvmStatic external fun discoverJsonEx(
        localIp: String,
        timeoutMs: Int,
        cachedJson: String,
        forceRefreshNames: Boolean,
    ): String
    /** 跨端共享路由状态机 JSON 入口：action = select_this | check | uncheck | merge_discovered。 */
    @JvmStatic external fun routeApplyJson(devicesJson: String, action: String, target: String): String
    @JvmStatic external fun probe(ip: String): String
    /** 立即返回句柄。silent=true 用内置 AAC 静音帧，否则用 pushAac 喂数据。 */
    @JvmStatic external fun create(silent: Boolean): Long
    /**
     * 阻塞：连接全部音箱直到推流开始。delayMs <= 0 = 自动（5 GHz 800 / 其他 1000）；
     * premute >= 0：推流前先用单独的 8899 会话把音量设为该值；volumeAfterPlay >= 0：PLAY 后（含中途加入/重连）再设一次。
     * refFunction 为空时按台数自动填 single_room / multi_room。返回 stats JSON（失败含 "error"）。
     */
    @JvmStatic external fun connect(handle: Long, ips: String, delayMs: Int, premute: Int, volumeAfterPlay: Int, refFunction: String, sourceName: String): String
    /** 同上，附带 volumeConfirmed：仅在用户明确确认超限音量后传 true，否则由 L1 按安全上限兜底钳制。 */
    @JvmStatic external fun connectWithVolumeConfirm(
        handle: Long,
        ips: String,
        delayMs: Int,
        premute: Int,
        volumeAfterPlay: Int,
        refFunction: String,
        sourceName: String,
        volumeConfirmed: Boolean,
    ): String
    @JvmStatic external fun pushAac(handle: Long, au: ByteArray): Int
    /** 协调式非阻塞增删接收端与调音量（排队/reconcile 全部下沉至 Rust core Engine）。 */
    @JvmStatic external fun queueAddReceiver(handle: Long, ip: String): String
    @JvmStatic external fun queueRemoveReceiver(handle: Long, ip: String): Boolean
    @JvmStatic external fun queueSetVolume(handle: Long, ip: String, volume: Int): String
    /** 带二次确认标志的协调式调音量：confirmed=true 放行已确认超限音量，false 按 L1 安全上限兜底。 */
    @JvmStatic external fun queueSetVolumeChecked(handle: Long, ip: String, volume: Int, confirmed: Boolean): String
    /** 同步全局音量过大保护策略至 L1 Rust 核心（默认开启、上限 60%）。 */
    @JvmStatic external fun setVolumeGuardPolicy(enabled: Boolean, safeLimit: Int)
    /** 跨端音量跨线裁决 JSON 入口（与 iOS C ABI 共享同一套实现）。 */
    @JvmStatic external fun evaluateVolumeChangeJson(
        enabled: Boolean,
        safeLimit: Int,
        currentVolume: Int,
        targetVolume: Int,
    ): String
    /** ip 为空 = 全组。返回每台的结果。 */
    @JvmStatic external fun setVolume(handle: Long, ip: String, volume: Int): String
    @JvmStatic external fun addReceiver(handle: Long, ip: String): String
    @JvmStatic external fun removeReceiver(handle: Long, ip: String)
    @JvmStatic external fun stats(handle: Long): String
    /** 暂停全组（停发媒体 + 每台 8899 Pause）。 */
    @JvmStatic external fun pause(handle: Long): String
    /** 恢复全组：全员同一个运行期 TIME_OFFSET → Resume×2 → 恢复发送。 */
    @JvmStatic external fun resume(handle: Long): String
    /** 调试：直接断开某台的连接（不发 CLOSE），验证 15 s 心跳超时后的自动重连。 */
    @JvmStatic external fun debugDrop(handle: Long, ip: String): Boolean
    /** 只读：查询运行中每台的声道（0=中置/立体声 1=左 2=右）。返回 "ip:ACK载荷hex|none;..."。 */
    @JvmStatic external fun queryChannel(handle: Long): String
    /** Wi-Fi 频率（MHz，<=0 未知）→ 播放延迟毫秒。 */
    @JvmStatic external fun delayForFrequency(mhz: Int): Int
    /** 0=auto（明文失败回退 Safety）1=legacy 2=safety，影响之后的 connect。 */
    @JvmStatic external fun setAuthMode(mode: Int)
    /** 切歌/换音源：丢弃旧缓冲，重锚时间线并对所有音箱重发 TIME_OFFSET。 */
    @JvmStatic external fun switchSource(handle: Long): String
    /** 运行期热更新播放缓冲延迟（delayMs <= 0 按频段自动；清缓冲并广播新 TIME_OFFSET，无需重连）。 */
    @JvmStatic external fun setPlaybackDelay(handle: Long, delayMs: Int): String
    /** 调试：对某台“网络黑洞” secs 秒（控制/音频/RTSP/计时器都不收不发，TCP 不关），验证超时重连。 */
    @JvmStatic external fun debugBlackhole(handle: Long, ip: String, secs: Int): Boolean
    /** 只做 SafetyAuth 互验 + 加密 GetDeviceInfo，返回 JSON。 */
    @JvmStatic external fun safetyProbe(ip: String): String
    /** 跨端 SemVer 比较（自动剥离 v 与构建后缀）：-1 / 0 / 1 / -2(非法)。 */
    @JvmStatic external fun compareVersions(a: String, b: String): Int
    /** 跨端更新裁决与区间日志聚合（纯逻辑，零网络 I/O）。 */
    @JvmStatic external fun evaluateUpdateJson(
        currentVersion: String,
        channel: String,
        platform: String,
        abi: String,
        osVersion: String,
        ignoredTag: String,
        rawResponseJson: String,
    ): String
    @JvmStatic external fun stop(handle: Long)
}
