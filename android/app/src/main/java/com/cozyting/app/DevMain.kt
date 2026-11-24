package com.cozyting.app

/**
 * 不安装 APK 也能在手机上验证 Kotlin↔JNI↔Rust 整条链路（adb shell app_process）。
 *   CLASSPATH=/data/local/tmp/cozyting.apk app_process -Dcozyting.lib=/data/local/tmp/libcozyting_core.so \
 *     /system/bin com.cozyting.app.DevMain <mode> [ips] [secs] [ref] [extra]
 * mode:
 *   discover                      发现音箱
 *   silent  ips secs              内置 AAC 静音帧
 *   encoder ips secs              全零 PCM → MediaCodec AAC → pushAac（系统音频源同一路径）
 *   latejoin ipA,ipB secs         先只连 ipA，6 s 后中途加入 ipB
 *   pauseresume ips secs          6 s 暂停，4 s 后恢复
 *   reconnect ipA,ipB secs        8 s 时模拟 ipB 断网，等待 15 s 心跳超时后自动重连
 *   channel ips secs              推流中 4 s 时只读查询 GetChannel
 *   switch ips secs               6 s 时 switchSource（切歌：重锚 + 重发 TIME_OFFSET）
 *   blackhole ips secs            6 s 时对最后一台“网络黑洞” 25 s，验证超时判定 + 自动重连
 *   safetyprobe ips               每台只做 SafetyAuth 互验 + 加密 GetDeviceInfo
 *   safety ips secs               全程走 Safety 链路（setAuthMode(2)）的静音投送
 * 音量：premute=0、volumeAfterPlay=0，结束前再 setVolume(0)。延迟传 0 = 自动（按 Wi-Fi 频段）。
 */
object DevMain {
    private fun log(s: String) = println("${System.currentTimeMillis() % 100000000} $s")

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.getOrElse(0) { "discover" }
        if (mode == "discover") { println("DISCOVER\n" + NativeBridge.discover("0.0.0.0", 3000)); return }
        val ips = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: run {
            println("USAGE: DevMain <mode> <ip1[,ip2,...]> [secs] [ref]")
            return
        }
        val secs = args.getOrElse(2) { "20" }.toInt()
        val ref = args.getOrElse(3) { "" }
        val list = ips.split(',')
        if (mode == "safetyprobe") { list.forEach { println("SAFETYPROBE $it " + NativeBridge.safetyProbe(it)) }; return }
        if (mode == "safety") NativeBridge.setAuthMode(2)
        val silent = mode != "encoder"
        val h = NativeBridge.create(silent)
        var enc: AacEncoder? = null
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        var feeder: Thread? = null
        if (!silent) {
            var pushed = 0; var dropped = 0
            enc = AacEncoder { au -> if (NativeBridge.pushAac(h, au) == 0) pushed++ else dropped++ }
            val pcm = ByteArray(1024 * 4)
            feeder = Thread {
                val t0 = System.nanoTime(); var n = 0L
                while (running.get()) {
                    enc.encode(pcm, pcm.size); n++
                    val ahead = n * 21_333_333L - (System.nanoTime() - t0) - 300_000_000L
                    if (ahead > 0) Thread.sleep(ahead / 1_000_000)
                    if (n % 250 == 0L) log("ENCODER produced=${enc.produced} pushed=$pushed dropped=$dropped")
                }
            }.apply { start() }
        }
        val initial = if (mode == "latejoin") list.first() else ips
        log("CONNECT " + NativeBridge.connect(h, initial, 0, 0, 0, ref, "CozyTing-dev"))
        val t0 = System.currentTimeMillis()
        val events = when (mode) {
            "latejoin" -> listOf(6 to "add")
            "pauseresume" -> listOf(6 to "pause", 10 to "resume")
            "reconnect" -> listOf(8 to "drop")
            "channel" -> listOf(4 to "channel")
            "switch" -> listOf(6 to "switch")
            "blackhole" -> listOf(6 to "blackhole")
            else -> emptyList()
        }.toMutableList()
        var nextStats = 2
        while (System.currentTimeMillis() - t0 < secs * 1000L) {
            Thread.sleep(100)
            val el = ((System.currentTimeMillis() - t0) / 1000).toInt()
            while (events.isNotEmpty() && el >= events.first().first) {
                when (events.removeAt(0).second) {
                    "add" -> log("EVENT add ${list.last()} -> " + NativeBridge.addReceiver(h, list.last()))
                    "pause" -> log("EVENT pause -> " + NativeBridge.pause(h))
                    "resume" -> log("EVENT resume -> " + NativeBridge.resume(h))
                    "switch" -> log("EVENT switchSource -> " + NativeBridge.switchSource(h))
                    "blackhole" -> log("EVENT blackhole ${list.last()} 25s -> " + NativeBridge.debugBlackhole(h, list.last(), 25))
                    "channel" -> log("EVENT queryChannel -> " + NativeBridge.queryChannel(h))
                    "drop" -> log("EVENT drop ${list.last()} -> " + NativeBridge.debugDrop(h, list.last()))
                }
            }
            if (el >= nextStats) { nextStats += 2; log("STATS " + NativeBridge.stats(h)) }
        }
        log("SETVOL0 " + NativeBridge.setVolume(h, "", 0))
        log("FINAL " + NativeBridge.stats(h))
        NativeBridge.stop(h)
        running.set(false); feeder?.join(1000); enc?.release()
        log("STOPPED")
    }
}
