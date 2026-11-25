package com.cozyting.app.capture.shell

import android.content.Context
import android.os.SystemClock
import com.cozyting.app.R
import com.cozyting.app.capture.AudioCaptureBackend
import com.cozyting.app.capture.CaptureScopeMode
import com.cozyting.app.diag.AppLog
import com.cozyting.app.ui.AppLocaleManager
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.DataInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
 * 4. 启动 `500ms` 心跳线程（容忍短暂后台调度超时）与 `MAX_PRIORITY` PCM 读取线程，运行期支持 `update_uid`
 *    零弹窗热切源；若 Helper 因系统省电冻结或内存回收意外退出，在限频窗口内自动尝试静默重建，保持音箱会话不断。
 */
class ShellCaptureBackend(
    private val context: Context,
    private val isSessionRunning: () -> Boolean,
    private val onEvent: (String) -> Unit,
) : AudioCaptureBackend {
    companion object {
        private const val TAG = "ShellCaptureBackend"
    }

    private sealed class CommandOutcome {
        data class Ok(val response: ShellCaptureProtocol.ParsedResponse) : CommandOutcome()
        data class Rejected(val response: ShellCaptureProtocol.ParsedResponse) : CommandOutcome()
        data object Timeout : CommandOutcome()
        data class Disconnected(val detail: String) : CommandOutcome()
    }

    private sealed class ConnectResult {
        data class Ok(
            val ctlSocket: Socket,
            val pcmSocket: Socket,
            val ctlWriter: BufferedWriter,
            val ctlReader: BufferedReader,
            val muteHandling: AudioCaptureBackend.LocalMuteHandling,
            val statusLine: String,
        ) : ConnectResult()

        data class Failed(val message: String) : ConnectResult()
    }

    private val active = AtomicBoolean(false)
    private val sessionGen = AtomicLong(0L)
    private val controlLock = Any()
    private val respawnLock = Any()
    private val heartbeatPolicy = HelperHeartbeatPolicy()

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
    @Volatile private var activePcmCallback: ((ByteArray, Int) -> Unit)? = null
    @Volatile private var activeFatalCallback: ((String) -> Unit)? = null

    override fun start(
        request: AudioCaptureBackend.CaptureRequest,
        onPcmFrame: (pcm: ByteArray, length: Int) -> Unit,
        onFatalError: (reasonZh: String) -> Unit,
    ): AudioCaptureBackend.StartResult {
        heartbeatPolicy.resetAll()
        activePcmCallback = onPcmFrame
        activeFatalCallback = onFatalError
        currentScope = request.scopeMode
        currentUid = request.targetUid
        currentKeepLocal = request.keepLocalOutput

        return when (val connected = connectAndStartHelper(request, isRespawn = false)) {
            is ConnectResult.Ok -> {
                val gen = sessionGen.incrementAndGet()
                installTransport(connected)
                active.set(true)
                startHeartbeatThread(gen, onFatalError)
                startPcmReaderThread(gen, connected.pcmSocket, onPcmFrame, onFatalError)
                onEvent(connected.statusLine)
                AudioCaptureBackend.StartResult.Started(connected.muteHandling, connected.statusLine)
            }
            is ConnectResult.Failed -> {
                stop()
                AudioCaptureBackend.StartResult.Failed(connected.message)
            }
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
        val outcome = sendCommandBlocking(cmd)
        if (outcome is CommandOutcome.Ok) {
            val resp = outcome.response
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
        val errMsg = (outcome as? CommandOutcome.Rejected)?.response?.error?.ifBlank { null }
        return AudioCaptureBackend.StartResult.Failed(errMsg ?: "无感采集热切换音源失败")
    }

    override fun stop() {
        val wasActive = active.getAndSet(false)
        sessionGen.incrementAndGet()
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

        closeTransportAndServers()
        activePcmCallback = null
        activeFatalCallback = null

        if (pt != null && pt !== Thread.currentThread()) runCatching { pt.join(400L) }
        if (hb != null && hb !== Thread.currentThread()) runCatching { hb.join(300L) }
    }

    private fun connectAndStartHelper(
        request: AudioCaptureBackend.CaptureRequest,
        isRespawn: Boolean,
    ): ConnectResult {
        val keyStore = PairingNotificationGuide.keyStore(context)
        if (!keyStore.isPaired()) {
            return ConnectResult.Failed(locStr(R.string.shell_err_not_paired))
        }
        val endpoint = WirelessAdbDiscovery(context)
            .discoverOnce(WirelessAdbDiscovery.SERVICE_CONNECT, timeoutMs = 3_500L)
            ?: return ConnectResult.Failed(locStr(R.string.shell_err_no_wireless_service))

        val tokenBytes = ShellCaptureProtocol.generateRandomTokenBytes()
        val tokenHex = ShellCaptureProtocol.toHex(tokenBytes)

        var localCtlSrv: ServerSocket? = null
        var localPcmSrv: ServerSocket? = null
        var localCtl: Socket? = null
        var localPcm: Socket? = null
        return try {
            val loopback = InetAddress.getByName(ShellCaptureProtocol.LOOPBACK_HOST)
            val ctlSrv = ServerSocket(0, 2, loopback).apply { soTimeout = 7_000 }
            val pcmSrv = ServerSocket(0, 2, loopback).apply { soTimeout = 7_000 }
            localCtlSrv = ctlSrv
            localPcmSrv = pcmSrv
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
                        errRef.compareAndSet(null, locStr(R.string.shell_err_helper_handshake))
                    } else {
                        ctlRef.set(accepted)
                    }
                } catch (e: Exception) {
                    errRef.compareAndSet(null, "${locStr(R.string.shell_err_helper_handshake)} (${e.message})")
                } finally {
                    readyLatch.countDown()
                }
            }, "shell-accept-ctl").start()

            Thread({
                try {
                    val accepted = acceptVerifiedPcmSocket(pcmSrv, tokenBytes)
                    if (accepted == null) {
                        errRef.compareAndSet(null, locStr(R.string.shell_err_helper_handshake))
                    } else {
                        pcmRef.set(accepted)
                    }
                } catch (e: Exception) {
                    errRef.compareAndSet(null, "${locStr(R.string.shell_err_helper_handshake)} (${e.message})")
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
            if (!isRespawn) {
                onEvent("正在经内置无线调试（127.0.0.1:${endpoint.port}）启动无感采集引擎…")
            }
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
                closeQuietly(localCtlSrv, localPcmSrv, localCtl, localPcm)
                return ConnectResult.Failed(launchErr ?: locStr(R.string.shell_err_helper_timeout))
            }
            val launchErr = launchErrRef.get()
            val acceptErr = errRef.get()
            val ctlPair = ctlRef.get()
            val pcm = pcmRef.get()
            if (launchErr != null) {
                closeQuietly(localCtlSrv, localPcmSrv, ctlPair?.first, pcm)
                return ConnectResult.Failed(launchErr)
            }
            if (acceptErr != null || ctlPair == null || pcm == null) {
                closeQuietly(localCtlSrv, localPcmSrv, ctlPair?.first, pcm)
                return ConnectResult.Failed(acceptErr ?: locStr(R.string.shell_err_helper_handshake))
            }
            handshakeDone.set(true)
            val (ctl, reader) = ctlPair
            localCtl = ctl
            localPcm = pcm

            // 关闭监听句柄，避免后续任何额外连接
            runCatching { ctlSrv.close() }
            runCatching { pcmSrv.close() }
            if (ctlServer === ctlSrv) ctlServer = null
            if (pcmServer === pcmSrv) pcmServer = null

            val writer = BufferedWriter(OutputStreamWriter(ctl.outputStream, Charsets.UTF_8))

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
            val startOutcome = sendCommandOnStreams(writer, reader, startCmd)
            val resp = when (startOutcome) {
                is CommandOutcome.Ok -> startOutcome.response
                is CommandOutcome.Rejected -> {
                    closeQuietly(null, null, ctl, pcm)
                    return ConnectResult.Failed(
                        startOutcome.response.error.ifBlank { locStr(R.string.shell_err_helper_start_failed) },
                    )
                }
                is CommandOutcome.Timeout, is CommandOutcome.Disconnected -> {
                    closeQuietly(null, null, ctl, pcm)
                    return ConnectResult.Failed(locStr(R.string.shell_err_helper_no_response))
                }
            }

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
            ConnectResult.Ok(
                ctlSocket = ctl,
                pcmSocket = pcm,
                ctlWriter = writer,
                ctlReader = reader,
                muteHandling = muteHandling,
                statusLine = statusLine,
            )
        } catch (e: Exception) {
            closeQuietly(localCtlSrv, localPcmSrv, localCtl, localPcm)
            ConnectResult.Failed(
                "${locStr(R.string.shell_err_helper_start_failed)}: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private fun installTransport(connected: ConnectResult.Ok) {
        synchronized(controlLock) {
            ctlSocket = connected.ctlSocket
            pcmSocket = connected.pcmSocket
            ctlWriter = connected.ctlWriter
            ctlReader = connected.ctlReader
        }
    }

    private fun closeTransportAndServers() {
        val oldPcm: Socket?
        val oldCtl: Socket?
        synchronized(controlLock) {
            oldPcm = pcmSocket
            oldCtl = ctlSocket
            pcmSocket = null
            ctlSocket = null
            ctlWriter = null
            ctlReader = null
        }
        val oldPcmSrv = pcmServer
        val oldCtlSrv = ctlServer
        pcmServer = null
        ctlServer = null
        closeQuietly(oldCtlSrv, oldPcmSrv, oldCtl, oldPcm)
    }

    private fun startHeartbeatThread(gen: Long, onFatalError: (String) -> Unit) {
        heartbeatThread = Thread({
            val pingJson = ShellCaptureProtocol.encodeCommand(ShellCaptureProtocol.CMD_PING)
            while (active.get() && isSessionRunning() && sessionGen.get() == gen) {
                try {
                    Thread.sleep(ShellCaptureProtocol.HEARTBEAT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (!active.get() || !isSessionRunning() || sessionGen.get() != gen) break
                when (val outcome = sendCommandBlocking(pingJson)) {
                    is CommandOutcome.Ok -> heartbeatPolicy.onHeartbeatSuccess()
                    is CommandOutcome.Timeout -> {
                        val canRetry = heartbeatPolicy.onHeartbeatTimeout()
                        val count = heartbeatPolicy.consecutiveTimeoutCount()
                        AppLog.w(TAG, "helper ping timeout #$count (canRetry=$canRetry)")
                        if (!canRetry) {
                            handleUnexpectedDisconnect(
                                failedGen = gen,
                                isPcmStreamError = false,
                                detail = "ping timeout x$count",
                                onFatalError = onFatalError,
                            )
                            break
                        }
                    }
                    is CommandOutcome.Disconnected -> {
                        AppLog.w(TAG, "helper heartbeat disconnected: ${outcome.detail}")
                        handleUnexpectedDisconnect(
                            failedGen = gen,
                            isPcmStreamError = false,
                            detail = outcome.detail,
                            onFatalError = onFatalError,
                        )
                        break
                    }
                    is CommandOutcome.Rejected -> {
                        val err = outcome.response.error.ifBlank { "ping rejected" }
                        AppLog.w(TAG, "helper ping rejected: $err")
                        handleUnexpectedDisconnect(
                            failedGen = gen,
                            isPcmStreamError = false,
                            detail = err,
                            onFatalError = onFatalError,
                        )
                        break
                    }
                }
            }
        }, "shell-capture-hb-$gen").apply {
            isDaemon = true
            start()
        }
    }

    private fun startPcmReaderThread(
        gen: Long,
        pcm: Socket,
        onPcmFrame: (ByteArray, Int) -> Unit,
        onFatalError: (String) -> Unit,
    ) {
        pcmThread = Thread({
            val chunk = AudioCaptureBackend.BYTES_PER_FRAME
            val buf = ByteArray(chunk)
            val dis = DataInputStream(pcm.inputStream)
            while (active.get() && isSessionRunning() && sessionGen.get() == gen) {
                try {
                    dis.readFully(buf, 0, chunk)
                } catch (e: Exception) {
                    if (active.get() && isSessionRunning() && sessionGen.get() == gen) {
                        val detail = e.message ?: "EOF"
                        AppLog.w(TAG, "helper PCM stream interrupted: $detail")
                        handleUnexpectedDisconnect(
                            failedGen = gen,
                            isPcmStreamError = true,
                            detail = detail,
                            onFatalError = onFatalError,
                        )
                    }
                    break
                }
                if (!active.get() || sessionGen.get() != gen) break
                onPcmFrame(buf, chunk)
            }
        }, "shell-pcm-read-$gen").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun handleUnexpectedDisconnect(
        failedGen: Long,
        isPcmStreamError: Boolean,
        detail: String,
        onFatalError: (String) -> Unit,
    ) {
        synchronized(respawnLock) {
            if (!active.get() || !isSessionRunning() || sessionGen.get() != failedGen) {
                return
            }

            // 递增代数并释放旧连接，确保另一条伴生线程（PCM 或心跳）立即安静退出，不重复触发重建
            val nextGen = sessionGen.incrementAndGet()
            closeTransportAndServers()

            val pcmCb = activePcmCallback
            val canRespawn = pcmCb != null && heartbeatPolicy.tryAcquireRespawnBudget(SystemClock.elapsedRealtime())
            if (canRespawn) {
                AppLog.i(TAG, "attempting silent Helper auto-respawn (reason=$detail, uid=$currentUid)")
                onEvent(locStr(R.string.shell_event_helper_reconnecting))
                val req = AudioCaptureBackend.CaptureRequest(
                    scopeMode = currentScope,
                    targetUid = currentUid,
                    keepLocalOutput = currentKeepLocal,
                )
                val reconnected = connectAndStartHelper(req, isRespawn = true)
                if (reconnected is ConnectResult.Ok && active.get() && isSessionRunning() && sessionGen.get() == nextGen) {
                    heartbeatPolicy.resetTimeouts()
                    installTransport(reconnected)
                    startHeartbeatThread(nextGen, onFatalError)
                    startPcmReaderThread(nextGen, reconnected.pcmSocket, pcmCb!!, onFatalError)
                    AppLog.i(TAG, "silent Helper auto-respawn succeeded (gen=$nextGen)")
                    onEvent(locStr(R.string.shell_event_helper_recovered))
                    return
                }
                if (reconnected is ConnectResult.Ok) {
                    closeQuietly(null, null, reconnected.ctlSocket, reconnected.pcmSocket)
                    return
                }
                val failMsg = (reconnected as? ConnectResult.Failed)?.message ?: "unknown"
                AppLog.w(TAG, "silent Helper auto-respawn failed: $failMsg")
            } else {
                AppLog.w(TAG, "Helper auto-respawn budget exhausted (reason=$detail)")
            }

            if (active.getAndSet(false) && isSessionRunning()) {
                closeTransportAndServers()
                val reason = if (isPcmStreamError) {
                    locStr(R.string.shell_err_pcm_stream_lost, detail)
                } else {
                    locStr(R.string.shell_err_helper_heartbeat_lost)
                }
                onFatalError(reason)
            }
        }
    }

    private fun sendCommandBlocking(jsonLine: String): CommandOutcome =
        synchronized(controlLock) {
            val w = ctlWriter ?: return CommandOutcome.Disconnected("control writer closed")
            val r = ctlReader ?: return CommandOutcome.Disconnected("control reader closed")
            sendCommandOnStreams(w, r, jsonLine)
        }

    private fun sendCommandOnStreams(
        writer: BufferedWriter,
        reader: BufferedReader,
        jsonLine: String,
    ): CommandOutcome = try {
        writer.write(jsonLine)
        writer.newLine()
        writer.flush()
        val line = reader.readLine()
            ?: return CommandOutcome.Disconnected("EOF")
        val parsed = ShellCaptureProtocol.parseResponse(line)
            ?: return CommandOutcome.Disconnected("malformed response")
        if (parsed.ok) CommandOutcome.Ok(parsed) else CommandOutcome.Rejected(parsed)
    } catch (_: SocketTimeoutException) {
        CommandOutcome.Timeout
    } catch (e: Exception) {
        CommandOutcome.Disconnected(e.message ?: e.javaClass.simpleName)
    }

    private fun acceptVerifiedControlSocket(
        server: ServerSocket,
        expectedToken: ByteArray,
    ): Pair<Socket, BufferedReader>? {
        for (attempt in 0 until 4) {
            val sock = server.accept()
            sock.tcpNoDelay = true
            sock.soTimeout = ShellCaptureProtocol.CONTROL_SOCKET_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
            val firstLine = runCatching { reader.readLine() }.getOrNull()
            val tokenHex = ShellCaptureProtocol.parseAuthTokenHex(firstLine)
            val actualBytes = tokenHex?.let { ShellCaptureProtocol.fromHex(it) }
            if (actualBytes != null && ShellCaptureProtocol.constantTimeTokenEquals(expectedToken, actualBytes)) {
                sock.soTimeout = ShellCaptureProtocol.CONTROL_SOCKET_TIMEOUT_MS
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
            sock.soTimeout = ShellCaptureProtocol.CONTROL_SOCKET_TIMEOUT_MS
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

    private fun closeQuietly(
        ctlSrv: ServerSocket?,
        pcmSrv: ServerSocket?,
        ctl: Socket?,
        pcm: Socket?,
    ) {
        runCatching { pcm?.close() }
        runCatching { ctl?.close() }
        runCatching { pcmSrv?.close() }
        runCatching { ctlSrv?.close() }
    }

    private fun locStr(resId: Int, vararg args: Any): String = runCatching {
        val locCtx = AppLocaleManager.localizedContext(context)
        if (args.isEmpty()) locCtx.getString(resId) else locCtx.getString(resId, *args)
    }.getOrElse {
        if (args.isEmpty()) context.getString(resId) else context.getString(resId, *args)
    }
}
