package com.miplay.sender.capture.shell

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 主 App 与内置 `uid=2000` `ShellAudioHelperMain` 之间的本机环回（`127.0.0.1`）协议与鉴权工具。
 *
 * 安全不变量与 SELinux 兼容性：
 * 1. 仅绑定本机环回 `127.0.0.1` 临时随机端口（绝不监听 `0.0.0.0` / 局域网网卡），
 *    规避 Android 11–17 SELinux MLS 对 `u:r:shell:s0` 连接 `u:r:untrusted_app:s0:c...` 抽象 `LocalSocket` 的拦截；
 * 2. 每次会话随机生成 256-bit（32 字节）一次性 Token，控制口与 PCM 数据口首包均须通过常量时间校验，
 *    握手通过后主进程立即关闭监听端口，拒绝任何其他本机进程接入；
 * 3. 控制口走短 JSON 行，PCM 数据口直传连续 4096B（48kHz s16le 2ch）裸帧。
 */
object ShellCaptureProtocol {
    const val LOOPBACK_HOST = "127.0.0.1"
    const val TOKEN_BYTES = 32
    const val HEARTBEAT_INTERVAL_MS = 500L
    const val HEARTBEAT_TIMEOUT_MS = 2_500L
    const val WRITE_STALL_TIMEOUT_MS = 2_000L

    const val CMD_START_SINGLE_UID = "start_single_uid"
    const val CMD_START_GLOBAL_MEDIA = "start_global_media"
    const val CMD_UPDATE_UID = "update_uid"
    const val CMD_PING = "ping"
    const val CMD_STOP = "stop"

    fun generateRandomTokenBytes(): ByteArray =
        ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }

    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            append(HEX[v ushr 4])
            append(HEX[v and 0x0f])
        }
    }

    fun fromHex(hex: String): ByteArray? {
        val clean = hex.trim()
        if (clean.length != TOKEN_BYTES * 2) return null
        val out = ByteArray(TOKEN_BYTES)
        for (i in 0 until TOKEN_BYTES) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun constantTimeTokenEquals(expected: ByteArray, actual: ByteArray): Boolean =
        expected.size == TOKEN_BYTES &&
            actual.size == TOKEN_BYTES &&
            MessageDigest.isEqual(expected, actual)

    fun generateSocketPrefix(): String {
        val rnd = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return "miplay_cap_${toHex(rnd)}"
    }

    fun controlSocketName(prefix: String): String = "$prefix.ctl"
    fun pcmSocketName(prefix: String): String = "$prefix.pcm"

    fun encodeAuthLine(tokenHex: String): String =
        JSONObject().put("auth", tokenHex).toString()

    fun parseAuthTokenHex(line: String?): String? {
        if (line.isNullOrBlank()) return null
        return runCatching { JSONObject(line).optString("auth", "").takeIf { it.isNotBlank() } }.getOrNull()
    }

    fun encodeCommand(
        cmd: String,
        uid: Int = -1,
        muteLocal: Boolean = true,
    ): String = JSONObject().apply {
        put("cmd", cmd)
        if (uid >= 0) put("uid", uid)
        put("mute_local", muteLocal)
    }.toString()

    data class ParsedCommand(
        val cmd: String,
        val uid: Int,
        val muteLocal: Boolean,
    )

    fun parseCommand(line: String?): ParsedCommand? {
        if (line.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(line)
            val cmd = o.optString("cmd", "")
            if (cmd.isBlank()) return null
            ParsedCommand(
                cmd = cmd,
                uid = o.optInt("uid", -1),
                muteLocal = o.optBoolean("mute_local", true),
            )
        }.getOrNull()
    }

    fun encodeResponse(
        ok: Boolean,
        muteHandling: String = "",
        status: String = "",
        error: String = "",
        pong: Boolean = false,
    ): String = JSONObject().apply {
        put("ok", ok)
        if (muteHandling.isNotBlank()) put("mute_handling", muteHandling)
        if (status.isNotBlank()) put("status", status)
        if (error.isNotBlank()) put("error", error)
        if (pong) put("pong", true)
    }.toString()

    data class ParsedResponse(
        val ok: Boolean,
        val muteHandling: String,
        val status: String,
        val error: String,
        val pong: Boolean,
    )

    fun parseResponse(line: String?): ParsedResponse? {
        if (line.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(line)
            ParsedResponse(
                ok = o.optBoolean("ok", false),
                muteHandling = o.optString("mute_handling", ""),
                status = o.optString("status", ""),
                error = o.optString("error", ""),
                pong = o.optBoolean("pong", false),
            )
        }.getOrNull()
    }

    /**
     * 构造通过本机无线 ADB 执行的 `app_process` 后台点火命令。
     *
     * 关键时序说明（已在 Android 17 `adbd` 实测验证）：
     * - 不能裸写 `setsid ... &` 立刻让父 `sh` 退出：`sh` 毫秒级退出后，`adbd` 清理 `Subprocess` 会立刻
     *   向原进程组发送信号，导致子进程尚未完成 `setsid(2)` 脱离会话就被提前终止；
     * - 通过 `(... setsid ... </dev/null >/dev/null 2>&1 &); sleep 1`：子进程完整脱离会话且不持有
     *   任何父级 stdio 管道，同时父 `sh` 存活 1 秒保护点火窗口；主 App 侧异步等待环回握手（通常 ~250ms
     *   即返回，不叠加 1 秒启动延迟），1 秒后 `sh` 与 ADB TLS 连接干净关闭，不留常驻连接。
     */
    fun buildAppProcessLaunchCommand(
        apkPath: String,
        ctlPort: Int,
        pcmPort: Int,
        tokenHex: String,
    ): String {
        val safeApk = shellSingleQuote(apkPath)
        val safeToken = shellSingleQuote(tokenHex)
        return "(CLASSPATH=$safeApk setsid /system/bin/app_process /system/bin " +
            "com.miplay.sender.capture.shell.ShellAudioHelperMain " +
            "--ctl-port $ctlPort --pcm-port $pcmPort --token $safeToken </dev/null >/dev/null 2>&1 &); sleep 1"
    }

    private fun shellSingleQuote(raw: String): String =
        "'" + raw.replace("'", "'\\''") + "'"

    private val HEX = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
}
