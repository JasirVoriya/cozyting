package com.miplay.sender.capture.shell

import android.content.Context
import com.miplay.sender.capture.AudioCaptureBackend
import com.miplay.sender.capture.CaptureScopeMode
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.DataInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 内置无线调试音频采集后端（API 30+，零外部 App 依赖）。
 *
 * 生命周期：
 * 1. 仅在本机环回 `127.0.0.1` 打开两个临时随机端口（`.ctl` + `.pcm`）并生成 256-bit 随机 Token
 *    （规避 Android 11–17 SELinux MLS 对 `shell` 连接 `untrusted_app` 抽象 `LocalSocket` 的拦截）；
 * 2. 发现本机 `_adb-tls-connect._tcp.` 端口，通过已保存的无线调试证书经 `WirelessAdbShellClient` 点火拉起
 *    `uid=2000` 的 `ShellAudioHelperMain`；
 * 3. 双通道完成常量时间 Token 校验后立即关闭监听端口，下发 `start_single_uid` 或 `start_global_media`；
 * 4. 启动 `500ms` 心跳线程与 `MAX_PRIORITY` PCM 读取线程，运行期支持 `update_uid` 零弹窗热切源。
 */
class ShellCaptureBackend(
    private val context: Context,
    private val isSessionRunning: () -> Boolean,
    private val onEvent: (String) -> Unit,
) : AudioCaptureBackend {
    private val active = AtomicBoolean(false)
    private val controlLock = Any()

    @Volatile private var ctlServer: ServerSocket? = null
    @Volatile private var pcmServer: ServerSocket? = null
    @Volatile private var ctlSocket: Socket? = null
    @Volatile private var pcmSocket: Socket? = null
    @Volatile private var ctlWriter: BufferedWriter? = null
    @Volatile private var ctlReader: BufferedReader? = null
    @Volatile private var heartbeatThread: Thread? = null
    @Volatile private var pcmThread: Thread? = null
    @Volatile private var currentUid: Int = -1
    @Volatile private var currentKeepLocal: Boolean = false
    @Volatile private var currentScope: CaptureScopeMode = CaptureScopeMode.SINGLE_APP

    override fun start(
        request: AudioCaptureBackend.CaptureRequest,
        onPcmFrame: (pcm: ByteArray, length: Int) -> Unit,
        onFatalError: (reasonZh: String) -> Unit,
    ): AudioCaptureBackend.StartResult {
        val keyStore = PairingNotificationGuide.keyStore(context)
        if (!keyStore.isPaired()) {
            return AudioCaptureBackend.StartResult.Failed(
                "无线调试尚未配对，请前往「设置 → 采集引擎」完成 6 位配对码首次配对",
            )
        }
        val endpoint = WirelessAdbDiscovery(context)
            .discoverOnce(WirelessAdbDiscovery.SERVICE_CONNECT, timeoutMs = 3_500L)
            ?: return AudioCaptureBackend.StartResult.Failed(
                "未检测到本机无线调试服务，请确认系统「开发者选项 → 无线调试」已开启",
            )

        val tokenBytes = ShellCaptureProtocol.generateRandomTokenBytes()
        val tokenHex = ShellCaptureProtocol.toHex(tokenBytes)

        return try {
            val loopback = InetAddress.getByName(ShellCaptureProtocol.LOOPBACK_HOST)
            val ctlSrv = ServerSocket(0, 2, loopback).apply { soTimeout = 7_000 }
            val pcmSrv = ServerSocket(0, 2, loopback).apply { soTimeout = 7_000 }
            val ctlPort = ctlSrv.localPort
            val pcmPort = pcmSrv.localPort
            ctlServer = ctlSrv
            pcmServer = pcmSrv

            val ctlRef = AtomicReference<Pair<Socket, BufferedReader>?>(null)
            val pcmRef = AtomicReference<Socket?>(null)
            val errRef = AtomicReference<String?>(null)
            val launchErrRef = AtomicReference<String?>(null)
            val handshakeDone = AtomicBoolean(false)
            val readyLatch = CountDownLatch(2)

            Thread({
                try {
                    val accepted = acceptVerifiedControlSocket(ctlSrv, tokenBytes)
                    if (accepted == null) {
                        errRef.compareAndSet(null, "采集 Helper 控制通道 Token 校验失败")
                    } else {
                        ctlRef.set(accepted)
                    }
                } catch (e: Exception) {
                    errRef.compareAndSet(null, "等待采集 Helper 控制通道异常：${e.message}")
                } finally {
                    readyLatch.countDown()
                }
            }, "shell-accept-ctl").start()

            Thread({
                try {
                    val accepted = acceptVerifiedPcmSocket(pcmSrv, tokenBytes)
                    if (accepted == null) {
                        errRef.compareAndSet(null, "采集 Helper 数据通道 Token 校验失败")
                    } else {
                        pcmRef.set(accepted)
                    }
                } catch (e: Exception) {
                    errRef.compareAndSet(null, "等待采集 Helper 数据通道异常：${e.message}")
                } finally {
                    readyLatch.countDown()
                }
            }, "shell-accept-pcm").start()

            // 在独立守护线程并发通过本机无线 ADB 发射点火命令：
            // 命令末尾保留 `sleep 1` 保护 setsid 脱离窗口，并发等待 readyLatch 让回连（通常 ~250ms）立即推进，
            // 不叠加 1 秒启动延迟；若无线 ADB 自身报错（证书未信任/端口拒绝）则立刻关闭监听以快速报错。
            val apkPath = context.applicationInfo.sourceDir
            val cmd = ShellCaptureProtocol.buildAppProcessLaunchCommand(apkPath, ctlPort, pcmPort, tokenHex)
            val identity = keyStore.getOrCreateIdentity()
            onEvent("正在经内置无线调试（127.0.0.1:${endpoint.port}）启动无感采集引擎…")
            Thread({
                when (val launch = WirelessAdbShellClient.runShellCommand(endpoint.host, endpoint.port, identity, cmd)) {
                    is WirelessAdbShellClient.ShellResult.Error -> {
                        if (!handshakeDone.get()) {
                            launchErrRef.compareAndSet(null, launch.messageZh)
                            runCatching { ctlSrv.close() }
                            runCatching { pcmSrv.close() }
                        }
                    }
                    is WirelessAdbShellClient.ShellResult.Ok -> {
                        val out = launch.output.trim()
                        if (out.isNotEmpty() && !handshakeDone.get()) {
                            onEvent("无线调试点火输出：${out.take(120)}")
                        }
                    }
                }
            }, "shell-adb-ignite").apply {
                isDaemon = true
                start()
            }

            if (!readyLatch.await(6_000L, TimeUnit.MILLISECONDS)) {
                val launchErr = launchErrRef.get()
                stop()
                return AudioCaptureBackend.StartResult.Failed(
                    launchErr ?: "等待无感采集 Helper 回连超时，请检查无线调试状态或尝试重新配对",
                )
            }
            val launchErr = launchErrRef.get()
            val acceptErr = errRef.get()
            val ctlPair = ctlRef.get()
            val pcm = pcmRef.get()
            if (launchErr != null) {
                stop()
                return AudioCaptureBackend.StartResult.Failed(launchErr)
            }
            if (acceptErr != null || ctlPair == null || pcm == null) {
                stop()
                return AudioCaptureBackend.StartResult.Failed(acceptErr ?: "无感采集 Helper 握手失败")
            }
            handshakeDone.set(true)
            val (ctl, reader) = ctlPair

            ctlSocket = ctl
            pcmSocket = pcm
            // 关闭监听句柄，避免后续任何额外连接
            runCatching { ctlSrv.close() }
            runCatching { pcmSrv.close() }
            ctlServer = null
            pcmServer = null

            val writer = BufferedWriter(OutputStreamWriter(ctl.outputStream, Charsets.UTF_8))
            ctlWriter = writer
            ctlReader = reader

            val startCmd = if (request.scopeMode == CaptureScopeMode.SINGLE_APP) {
                ShellCaptureProtocol.encodeCommand(
                    cmd = ShellCaptureProtocol.CMD_START_SINGLE_UID,
                    uid = request.targetUid,
                    muteLocal = !request.keepLocalOutput,
                )
            } else {
                ShellCaptureProtocol.encodeCommand(
                    cmd = ShellCaptureProtocol.CMD_START_GLOBAL_MEDIA,
                    uid = -1,
                    muteLocal = !request.keepLocalOutput,
                )
            }
            val resp = sendCommandBlocking(startCmd)
                ?: return AudioCaptureBackend.StartResult.Failed("无感采集 Helper 无响应").also { stop() }
            if (!resp.ok) {
                stop()
                return AudioCaptureBackend.StartResult.Failed(
                    resp.error.ifBlank { "无感采集引擎启动失败" },
                )
            }

            currentScope = request.scopeMode
            currentUid = request.targetUid
            currentKeepLocal = request.keepLocalOutput
            active.set(true)

            startHeartbeatThread(onFatalError)
            startPcmReaderThread(pcm, onPcmFrame, onFatalError)

            val muteHandling = runCatching {
                AudioCaptureBackend.LocalMuteHandling.valueOf(resp.muteHandling)
            }.getOrDefault(
                if (request.keepLocalOutput) {
                    AudioCaptureBackend.LocalMuteHandling.KEEP_LOCAL_OUTPUT
                } else {
                    AudioCaptureBackend.LocalMuteHandling.SYSTEM_ROUTE_LOOPBACK
                },
            )
            val statusLine = resp.status.ifBlank { "内置无线调试无感采集已启动" }
            onEvent(statusLine)
            AudioCaptureBackend.StartResult.Started(muteHandling, statusLine)
        } catch (e: Exception) {
            stop()
            AudioCaptureBackend.StartResult.Failed("无感采集启动异常：${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun hotSwitchUid(newUid: Int, keepLocalOutput: Boolean): AudioCaptureBackend.StartResult {
        if (!active.get()) {
            return AudioCaptureBackend.StartResult.Failed("无感采集未处于运行状态")
        }
        if (currentScope != CaptureScopeMode.SINGLE_APP) {
            return AudioCaptureBackend.StartResult.Failed("当前为全局媒体模式，无需切换单 App UID")
        }
        if (newUid < 0) {
            return AudioCaptureBackend.StartResult.Failed("目标 UID 无效")
        }
        val prevUid = currentUid
        val prevKeep = currentKeepLocal
        val cmd = ShellCaptureProtocol.encodeCommand(
            cmd = ShellCaptureProtocol.CMD_UPDATE_UID,
            uid = newUid,
            muteLocal = !keepLocalOutput,
        )
        val resp = sendCommandBlocking(cmd)
        if (resp != null && resp.ok) {
            currentUid = newUid
            currentKeepLocal = keepLocalOutput
            val muteHandling = runCatching {
                AudioCaptureBackend.LocalMuteHandling.valueOf(resp.muteHandling)
            }.getOrDefault(
                if (keepLocalOutput) {
                    AudioCaptureBackend.LocalMuteHandling.KEEP_LOCAL_OUTPUT
                } else {
                    AudioCaptureBackend.LocalMuteHandling.ISOLATED_APP_LOOPBACK
                },
            )
            return AudioCaptureBackend.StartResult.Started(
                muteHandling,
                resp.status.ifBlank { "已零弹窗热切换单 App 捕获（uid=$newUid）" },
            )
        }
        // Fail-Closed：尝试恢复原 UID
        if (prevUid >= 0 && isSessionRunning()) {
            val restoreCmd = ShellCaptureProtocol.encodeCommand(
                cmd = ShellCaptureProtocol.CMD_UPDATE_UID,
                uid = prevUid,
                muteLocal = !prevKeep,
            )
            sendCommandBlocking(restoreCmd)
        }
        return AudioCaptureBackend.StartResult.Failed(
            resp?.error?.ifBlank { null } ?: "无感采集热切换音源失败",
        )
    }

    override fun stop() {
        val wasActive = active.getAndSet(false)
        if (wasActive) {
            runCatching {
                sendCommandBlocking(ShellCaptureProtocol.encodeCommand(ShellCaptureProtocol.CMD_STOP))
            }
        }
        val hb = heartbeatThread
        heartbeatThread = null
        hb?.interrupt()
        val pt = pcmThread
        pcmThread = null
        pt?.interrupt()

        runCatching { pcmSocket?.close() }
        runCatching { ctlSocket?.close() }
        runCatching { pcmServer?.close() }
        runCatching { ctlServer?.close() }
        pcmSocket = null
        ctlSocket = null
        pcmServer = null
        ctlServer = null
        ctlWriter = null
        ctlReader = null

        if (pt != null && pt !== Thread.currentThread()) runCatching { pt.join(400L) }
        if (hb != null && hb !== Thread.currentThread()) runCatching { hb.join(300L) }
    }

    private fun startHeartbeatThread(onFatalError: (String) -> Unit) {
        heartbeatThread = Thread({
            val pingJson = ShellCaptureProtocol.encodeCommand(ShellCaptureProtocol.CMD_PING)
            while (active.get() && isSessionRunning()) {
                try {
                    Thread.sleep(ShellCaptureProtocol.HEARTBEAT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (!active.get() || !isSessionRunning()) break
                val resp = sendCommandBlocking(pingJson)
                if (resp == null || !resp.ok) {
                    if (active.getAndSet(false) && isSessionRunning()) {
                        onFatalError("无感采集 Helper 心跳断开，已安全停止投送")
                    }
                    break
                }
            }
        }, "shell-capture-hb").apply {
            isDaemon = true
            start()
        }
    }

    private fun startPcmReaderThread(
        pcm: Socket,
        onPcmFrame: (ByteArray, Int) -> Unit,
        onFatalError: (String) -> Unit,
    ) {
        pcmThread = Thread({
            val chunk = AudioCaptureBackend.BYTES_PER_FRAME
            val buf = ByteArray(chunk)
            val dis = DataInputStream(pcm.inputStream)
            while (active.get() && isSessionRunning()) {
                try {
                    dis.readFully(buf, 0, chunk)
                } catch (e: Exception) {
                    if (active.getAndSet(false) && isSessionRunning()) {
                        onFatalError("无感采集音频流意外中断（${e.message ?: "EOF"}）")
                    }
                    break
                }
                if (!active.get()) break
                onPcmFrame(buf, chunk)
            }
        }, "shell-pcm-read").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun sendCommandBlocking(jsonLine: String): ShellCaptureProtocol.ParsedResponse? =
        synchronized(controlLock) {
            val w = ctlWriter ?: return null
            val r = ctlReader ?: return null
            runCatching {
                w.write(jsonLine)
                w.newLine()
                w.flush()
                ShellCaptureProtocol.parseResponse(r.readLine())
            }.getOrNull()
        }

    private fun acceptVerifiedControlSocket(
        server: ServerSocket,
        expectedToken: ByteArray,
    ): Pair<Socket, BufferedReader>? {
        for (attempt in 0 until 4) {
            val sock = server.accept()
            sock.tcpNoDelay = true
            sock.soTimeout = 3_000
            val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
            val firstLine = runCatching { reader.readLine() }.getOrNull()
            val tokenHex = ShellCaptureProtocol.parseAuthTokenHex(firstLine)
            val actualBytes = tokenHex?.let { ShellCaptureProtocol.fromHex(it) }
            if (actualBytes != null && ShellCaptureProtocol.constantTimeTokenEquals(expectedToken, actualBytes)) {
                sock.soTimeout = 3_000
                return sock to reader
            }
            runCatching { sock.close() }
        }
        return null
    }

    private fun acceptVerifiedPcmSocket(
        server: ServerSocket,
        expectedToken: ByteArray,
    ): Socket? {
        for (attempt in 0 until 4) {
            val sock = server.accept()
            sock.tcpNoDelay = true
            sock.soTimeout = 3_000
            val buf = ByteArray(ShellCaptureProtocol.TOKEN_BYTES)
            val ok = runCatching {
                DataInputStream(sock.inputStream).readFully(buf)
                ShellCaptureProtocol.constantTimeTokenEquals(expectedToken, buf)
            }.getOrDefault(false)
            if (ok) {
                sock.soTimeout = 0
                return sock
            }
            runCatching { sock.close() }
        }
        return null
    }
}
