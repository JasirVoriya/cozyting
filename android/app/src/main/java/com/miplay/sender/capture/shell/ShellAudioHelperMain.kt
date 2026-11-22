package com.miplay.sender.capture.shell

import android.content.Context
import android.os.Looper
import android.util.Log
import com.miplay.sender.capture.AudioCaptureBackend
import com.miplay.sender.capture.CaptureScopeMode
import com.miplay.sender.compat.ShellCaptureCompat
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

/**
 * 运行于 `com.android.shell`（`uid=2000`）上下文的内置极简音频采集 Helper 进程。
 *
 * 启动方式：由主 App 经本机无线调试执行 `/system/bin/app_process` 点火（开发期亦可经 `adb shell` 直测）。
 * 严守三重防卡死清理协议：
 * 1. 正常收到 `stop` 指令：停采集 → 释放 AudioRecord → `unregisterAudioPolicy` → 关连接 → 退出；
 * 2. 控制口 `EOF` 或 PCM 写通道 `IOException`/`EPIPE`：立即触发 `cleanupAndExit()`；
 * 3. 心跳看门狗（`1.5s` 无 `ping` 或写阻塞超 `2.0s`）：立即反注册策略并退出，防止目标音乐 App 持续无声。
 */
object ShellAudioHelperMain {
    private const val TAG = "MiPlayShellHelper"
    private val exiting = AtomicBoolean(false)
    private val captureLock = Any()
    private val captureGen = AtomicLong(0L)
    private val lastHeartbeatMs = AtomicLong(0L)
    private val writeStartMs = AtomicLong(0L)

    @Volatile private var activeHandle: AudioPolicyReflector.CaptureHandle? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var controlSocket: Socket? = null
    @Volatile private var pcmSocket: Socket? = null
    @Volatile private var pcmOut: OutputStream? = null

    @JvmStatic
    fun main(args: Array<String>) {
        var ctlPort = 0
        var pcmPort = 0
        var tokenHex = ""
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--ctl-port" -> if (i + 1 < args.size) ctlPort = args[++i].toIntOrNull() ?: 0
                "--pcm-port" -> if (i + 1 < args.size) pcmPort = args[++i].toIntOrNull() ?: 0
                "--token" -> if (i + 1 < args.size) tokenHex = args[++i]
            }
            i++
        }
        val tokenBytes = ShellCaptureProtocol.fromHex(tokenHex)
        if (ctlPort !in 1..65535 || pcmPort !in 1..65535 || tokenBytes == null) {
            System.err.println("ShellAudioHelperMain: invalid args")
            exitProcess(2)
        }

        Thread.setDefaultUncaughtExceptionHandler { _, t ->
            logError("uncaught: ${t.message}", t)
            cleanupAndExit(1)
        }
        Runtime.getRuntime().addShutdownHook(Thread { cleanupResourcesOnly() })

        try {
            logInfo("starting ctlPort=$ctlPort pcmPort=$pcmPort")
            AudioPolicyReflector.ensureHiddenApiBypass()
            val systemContext = bootstrapSystemContext()
            connectAndServe(systemContext, ctlPort, pcmPort, tokenHex, tokenBytes)
        } catch (t: Throwable) {
            logError("fatal: ${t.message}", t)
            cleanupAndExit(1)
        }
    }

    private fun bootstrapSystemContext(): Context {
        @Suppress("DEPRECATION")
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }
        val atCls = Class.forName("android.app.ActivityThread")
        val systemMain = atCls.getMethod("systemMain").invoke(null)
        val rawSystemCtx = atCls.getMethod("getSystemContext").invoke(systemMain) as Context
        return ShellCaptureCompat.wrapAsShellContext(rawSystemCtx)
    }

    private fun connectAndServe(
        context: Context,
        ctlPort: Int,
        pcmPort: Int,
        tokenHex: String,
        tokenBytes: ByteArray,
    ) {
        val ctl = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(ShellCaptureProtocol.LOOPBACK_HOST, ctlPort), 3_000)
        }
        controlSocket = ctl

        val pcm = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(ShellCaptureProtocol.LOOPBACK_HOST, pcmPort), 3_000)
        }
        pcmSocket = pcm
        pcmOut = pcm.outputStream

        // 1. 立即完成双通道 256-bit 随机 Token 握手
        val ctlWriter = BufferedWriter(OutputStreamWriter(ctl.outputStream, Charsets.UTF_8))
        val ctlReader = BufferedReader(InputStreamReader(ctl.inputStream, Charsets.UTF_8))
        ctlWriter.write(ShellCaptureProtocol.encodeAuthLine(tokenHex))
        ctlWriter.newLine()
        ctlWriter.flush()

        pcm.outputStream.write(tokenBytes)
        pcm.outputStream.flush()

        lastHeartbeatMs.set(System.currentTimeMillis())

        // 2. 启动心跳与写阻塞看门狗（第三重防卡死兜底）
        Thread({
            while (!exiting.get()) {
                try {
                    Thread.sleep(250L)
                } catch (_: InterruptedException) {
                    break
                }
                val now = System.currentTimeMillis()
                if (now - lastHeartbeatMs.get() > ShellCaptureProtocol.HEARTBEAT_TIMEOUT_MS) {
                    logError("heartbeat timeout, cleaning up AudioPolicy")
                    cleanupAndExit(0)
                    return@Thread
                }
                val ws = writeStartMs.get()
                if (ws > 0L && now - ws > ShellCaptureProtocol.WRITE_STALL_TIMEOUT_MS) {
                    logError("PCM pipe write stall, cleaning up AudioPolicy")
                    cleanupAndExit(0)
                    return@Thread
                }
            }
        }, "shell-helper-watchdog").apply {
            isDaemon = true
            start()
        }

        // 3. 控制指令主循环（读到 EOF 即主进程死亡/断连 → 立即清理退出）
        while (!exiting.get()) {
            val line = ctlReader.readLine() ?: break
            lastHeartbeatMs.set(System.currentTimeMillis())
            val parsed = ShellCaptureProtocol.parseCommand(line) ?: continue
            val resp = when (parsed.cmd) {
                ShellCaptureProtocol.CMD_PING ->
                    ShellCaptureProtocol.encodeResponse(ok = true, pong = true)

                ShellCaptureProtocol.CMD_START_SINGLE_UID ->
                    handleStartOrSwitch(context, CaptureScopeMode.SINGLE_APP, parsed.uid, parsed.muteLocal, isHotSwitch = false)

                ShellCaptureProtocol.CMD_START_GLOBAL_MEDIA ->
                    handleStartOrSwitch(context, CaptureScopeMode.GLOBAL_MEDIA, -1, parsed.muteLocal, isHotSwitch = false)

                ShellCaptureProtocol.CMD_UPDATE_UID ->
                    handleStartOrSwitch(context, CaptureScopeMode.SINGLE_APP, parsed.uid, parsed.muteLocal, isHotSwitch = true)

                ShellCaptureProtocol.CMD_STOP -> {
                    synchronized(captureLock) { stopActiveCaptureLocked() }
                    runCatching {
                        ctlWriter.write(ShellCaptureProtocol.encodeResponse(ok = true, status = "stopped"))
                        ctlWriter.newLine()
                        ctlWriter.flush()
                    }
                    cleanupAndExit(0)
                    return
                }

                else -> ShellCaptureProtocol.encodeResponse(ok = false, error = "未知控制命令：${parsed.cmd}")
            }
            lastHeartbeatMs.set(System.currentTimeMillis())
            ctlWriter.write(resp)
            ctlWriter.newLine()
            ctlWriter.flush()
        }

        cleanupAndExit(0)
    }

    private fun handleStartOrSwitch(
        context: Context,
        scopeMode: CaptureScopeMode,
        uid: Int,
        muteLocal: Boolean,
        isHotSwitch: Boolean,
    ): String = synchronized(captureLock) {
        stopActiveCaptureLocked()
        val retryDelays = if (isHotSwitch) longArrayOf(0L, 60L, 150L) else longArrayOf(0L)
        var lastErr = "无法启动音频捕获"
        for (delayMs in retryDelays) {
            if (exiting.get()) return ShellCaptureProtocol.encodeResponse(ok = false, error = "Helper 正在退出")
            if (delayMs > 0L) runCatching { Thread.sleep(delayMs) }
            when (val opened = AudioPolicyReflector.openCapture(context, scopeMode, uid, muteLocal)) {
                is AudioPolicyReflector.OpenResult.Ok -> {
                    startPcmPumpLocked(opened.handle)
                    return ShellCaptureProtocol.encodeResponse(
                        ok = true,
                        muteHandling = opened.handle.muteHandling.name,
                        status = opened.handle.statusLine,
                    )
                }
                is AudioPolicyReflector.OpenResult.Error -> {
                    lastErr = opened.messageZh
                }
            }
        }
        ShellCaptureProtocol.encodeResponse(ok = false, error = lastErr)
    }

    private fun startPcmPumpLocked(handle: AudioPolicyReflector.CaptureHandle) {
        activeHandle = handle
        val myGen = captureGen.incrementAndGet()
        handle.record.startRecording()
        captureThread = Thread({
            val chunk = AudioCaptureBackend.BYTES_PER_FRAME
            val buf = ByteArray(chunk)
            val out = pcmOut
            if (out == null) {
                cleanupAndExit(1)
                return@Thread
            }
            while (!exiting.get() && captureGen.get() == myGen) {
                var off = 0
                while (off < chunk && !exiting.get() && captureGen.get() == myGen) {
                    val n = handle.record.read(buf, off, chunk - off)
                    if (n <= 0) {
                        if (!exiting.get() && captureGen.get() == myGen) {
                            System.err.println("ShellAudioHelperMain: AudioRecord.read returned $n")
                        }
                        return@Thread
                    }
                    off += n
                }
                if (exiting.get() || captureGen.get() != myGen) break
                try {
                    writeStartMs.set(System.currentTimeMillis())
                    out.write(buf, 0, chunk)
                    writeStartMs.set(0L)
                } catch (e: Exception) {
                    writeStartMs.set(0L)
                    if (!exiting.get()) {
                        System.err.println("ShellAudioHelperMain: PCM write failed (${e.message}), cleaning up")
                        cleanupAndExit(0)
                    }
                    return@Thread
                }
            }
        }, "shell-pcm-pump-$myGen").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun stopActiveCaptureLocked() {
        captureGen.incrementAndGet()
        val oldHandle = activeHandle
        activeHandle = null
        val oldThread = captureThread
        captureThread = null
        runCatching { oldHandle?.release() }
        oldThread?.interrupt()
        if (oldThread != null && oldThread !== Thread.currentThread()) {
            runCatching { oldThread.join(400L) }
        }
    }

    private fun cleanupResourcesOnly() {
        synchronized(captureLock) {
            stopActiveCaptureLocked()
        }
        runCatching { pcmSocket?.close() }
        runCatching { controlSocket?.close() }
        pcmSocket = null
        controlSocket = null
        pcmOut = null
    }

    private fun cleanupAndExit(code: Int) {
        if (!exiting.compareAndSet(false, true)) return
        cleanupResourcesOnly()
        exitProcess(code)
    }

    private fun logInfo(msg: String) {
        runCatching { Log.i(TAG, msg) }
    }

    private fun logError(msg: String, t: Throwable? = null) {
        System.err.println("ShellAudioHelperMain $msg")
        runCatching {
            if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
        }
    }
}
