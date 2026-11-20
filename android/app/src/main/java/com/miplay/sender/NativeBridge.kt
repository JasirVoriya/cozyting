package com.miplay.sender

/** 对应 core/src/ffi.rs。所有阻塞调用（discover/probe/start/setVolume/addReceiver）都必须在后台线程调用。 */
object NativeBridge {
    init {
        // app_process 调试时没有 APK 的 nativeLibraryDir，可用 -Dmiplay.lib=/绝对路径/libmiplay_sender_core.so
        val explicit = System.getProperty("miplay.lib")
        if (explicit != null) System.load(explicit) else System.loadLibrary("miplay_sender_core")
        init()
    }

    @JvmStatic external fun init()
    /** 每行 "ip\tname\tmodel"；出错时 "ERROR\t原因"。 */
    @JvmStatic external fun discover(localIp: String, timeoutMs: Int): String
    @JvmStatic external fun probe(ip: String): String
    /** 立即返回句柄。silent=true 用内置 AAC 静音帧，否则用 pushAac 喂数据。 */
    @JvmStatic external fun create(silent: Boolean): Long
    /**
     * 阻塞：连接全部音箱直到推流开始。delayMs <= 0 = 自动（5 GHz 800 / 其他 1000）；
     * premute >= 0：推流前先用单独的 8899 会话把音量设为该值；volumeAfterPlay >= 0：PLAY 后（含中途加入/重连）再设一次。
     * refFunction 为空时按台数自动填 single_room / multi_room。返回 stats JSON（失败含 "error"）。
     */
    @JvmStatic external fun connect(handle: Long, ips: String, delayMs: Int, premute: Int, volumeAfterPlay: Int, refFunction: String, sourceName: String): String
    @JvmStatic external fun pushAac(handle: Long, au: ByteArray): Int
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
    /** 调试：对某台“网络黑洞” secs 秒（控制/音频/RTSP/计时器都不收不发，TCP 不关），验证超时重连。 */
    @JvmStatic external fun debugBlackhole(handle: Long, ip: String, secs: Int): Boolean
    /** 只做 SafetyAuth 互验 + 加密 GetDeviceInfo，返回 JSON。 */
    @JvmStatic external fun safetyProbe(ip: String): String
    @JvmStatic external fun stop(handle: Long)
}
