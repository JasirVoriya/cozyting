package com.miplay.sender.capture.shell

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

/**
 * 极简 AOSP 无线 ADB TLS 客户端（仅覆盖连接认证与单次 `shell:` 点火命令，零额外 native 库）。
 */
object WirelessAdbShellClient {
    const val A_CNXN = 0x4E584E43 // "CNXN"
    const val A_AUTH = 0x48545541 // "AUTH"
    const val A_OPEN = 0x4E45504F // "OPEN"
    const val A_OKAY = 0x59414B4F // "OKAY"
    const val A_CLSE = 0x45534C43 // "CLSE"
    const val A_WRTE = 0x45545257 // "WRTE"
    const val A_STLS = 0x534C5453 // "STLS"

    const val A_VERSION = 0x01000001
    const val A_STLS_VERSION = 0x01000000
    const val MAX_DATA = 1024 * 1024
    const val HEADER_SIZE = 24

    data class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray = ByteArray(0),
    ) {
        fun encode(): ByteArray {
            val bb = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(command)
            bb.putInt(arg0)
            bb.putInt(arg1)
            bb.putInt(payload.size)
            bb.putInt(checksum(payload))
            bb.putInt(command xor -1)
            bb.put(payload)
            return bb.array()
        }

        companion object {
            fun checksum(data: ByteArray): Int {
                var sum = 0
                for (b in data) sum += b.toInt() and 0xff
                return sum
            }

            fun readFrom(input: InputStream): AdbPacket {
                val dis = DataInputStream(input)
                val hdr = ByteArray(HEADER_SIZE)
                dis.readFully(hdr)
                val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                val cmd = bb.int
                val arg0 = bb.int
                val arg1 = bb.int
                val len = bb.int
                bb.int // checksum（A_VERSION >= 0x01000001 允许省略，保持宽容解析）
                val magic = bb.int
                require(magic == (cmd xor -1)) {
                    "ADB 包 magic 校验失败：cmd=0x${cmd.toUInt().toString(16)}"
                }
                require(len in 0..MAX_DATA) { "非法 ADB 载荷长度：$len" }
                val data = ByteArray(len)
                if (len > 0) dis.readFully(data)
                return AdbPacket(cmd, arg0, arg1, data)
            }
        }
    }

    sealed class ShellResult {
        data class Ok(val output: String) : ShellResult()
        data class Error(val messageZh: String, val cause: Throwable? = null) : ShellResult()
    }

    /**
     * 连接本机无线调试端口，通过 `A_STLS` 升级 TLS 1.3 并执行一条 shell 命令。
     * 用于连接测试（如 `id`）或点火拉起 `app_process` 后台采集 Helper。
     */
    fun runShellCommand(
        host: String,
        port: Int,
        identity: AdbCrypto.AdbIdentity,
        command: String,
        timeoutMs: Int = 6_000,
    ): ShellResult {
        var rawSocket: Socket? = null
        var tlsSocket: SSLSocket? = null
        return try {
            val sock = Socket()
            rawSocket = sock
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            sock.soTimeout = timeoutMs

            var input: InputStream = sock.inputStream
            var output: OutputStream = sock.outputStream

            // 1. 发送初始 CNXN
            val cnxnBanner = "host::features=shell_v2,cmd\u0000".toByteArray(Charsets.US_ASCII)
            writePacket(output, AdbPacket(A_CNXN, A_VERSION, MAX_DATA, cnxnBanner))

            // 2. 处理 A_STLS 升级或直接收到 A_CNXN
            var connected = false
            for (step in 0 until 4) {
                val pkt = AdbPacket.readFrom(input)
                when (pkt.command) {
                    A_STLS -> {
                        writePacket(output, AdbPacket(A_STLS, A_STLS_VERSION, 0))
                        val sslCtx = WirelessAdbPairingClient.buildAdbTlsContext(identity)
                        val upgraded = (sslCtx.socketFactory.createSocket(sock, host, port, true) as SSLSocket).apply {
                            enabledProtocols = arrayOf("TLSv1.3")
                            useClientMode = true
                            soTimeout = timeoutMs
                        }
                        tlsSocket = upgraded
                        upgraded.startHandshake()
                        input = upgraded.inputStream
                        output = upgraded.outputStream
                    }
                    A_CNXN -> {
                        connected = true
                        break
                    }
                    A_AUTH -> {
                        return ShellResult.Error("设备未信任当前配对证书，请在设置中重新配对无线调试")
                    }
                }
            }
            if (!connected) {
                return ShellResult.Error("无线 ADB 握手未完成，请确认「无线调试」已开启")
            }

            // 3. 打开 shell:<command> 流
            val localId = 1
            val dest = "shell:$command\u0000".toByteArray(Charsets.UTF_8)
            writePacket(output, AdbPacket(A_OPEN, localId, 0, dest))

            var remoteId = 0
            val outBuf = ByteArrayOutputStream()
            while (true) {
                val pkt = AdbPacket.readFrom(input)
                when (pkt.command) {
                    A_OKAY -> {
                        if (pkt.arg1 == localId) remoteId = pkt.arg0
                    }
                    A_WRTE -> {
                        if (pkt.arg1 == localId) {
                            if (remoteId == 0) remoteId = pkt.arg0
                            outBuf.write(pkt.payload)
                            writePacket(output, AdbPacket(A_OKAY, localId, remoteId))
                        }
                    }
                    A_CLSE -> {
                        if (pkt.arg1 == localId) {
                            if (remoteId == 0) remoteId = pkt.arg0
                            runCatching { writePacket(output, AdbPacket(A_CLSE, localId, remoteId)) }
                            break
                        }
                    }
                }
            }
            ShellResult.Ok(outBuf.toString("UTF-8").trim())
        } catch (e: Exception) {
            ShellResult.Error("无线 ADB 连接失败：${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            runCatching { tlsSocket?.close() }
            runCatching { rawSocket?.close() }
        }
    }

    private fun writePacket(out: OutputStream, pkt: AdbPacket) {
        out.write(pkt.encode())
        out.flush()
    }
}
