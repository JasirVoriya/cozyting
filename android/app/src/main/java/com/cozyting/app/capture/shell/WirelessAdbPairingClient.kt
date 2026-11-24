package com.cozyting.app.capture.shell

import com.cozyting.app.compat.ShellCaptureCompat
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * 内置 Android 11+ 无线调试配对客户端（TLS 1.3 + RFC 5705 Keying Material + SPAKE2 + AES-128-GCM）。
 *
 * 与 AOSP `pairing_connection.cpp` / `pairing_auth.cpp` / `tls_connection.cpp` 逐字节对齐。
 */
object WirelessAdbPairingClient {
    /** AOSP `tls_connection.cpp` 使用 `sizeof("adb-label")`（含末尾 NUL，共 10 字节）。 */
    const val TLS_EXPORT_LABEL = "adb-label\u0000"
    const val TLS_EXPORT_LENGTH = 64

    /** AOSP `pairing_auth.cpp` 使用 `sizeof("adb pair client/server")`（含末尾 NUL）。 */
    val SPAKE2_CLIENT_NAME = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
    val SPAKE2_SERVER_NAME = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)

    sealed class PairResult {
        data class Success(val serverDeviceGuid: String) : PairResult()
        data class Failure(val messageZh: String, val cause: Throwable? = null) : PairResult()
    }

    fun pair(
        host: String,
        port: Int,
        pairingCode: String,
        identity: AdbCrypto.AdbIdentity,
        timeoutMs: Int = 10_000,
    ): PairResult {
        val pin = pairingCode.trim()
        if (!pin.matches(Regex("""\d{6}"""))) {
            return PairResult.Failure("配对码格式错误：请输入系统显示的 6 位数字配对码")
        }
        return try {
            val sslCtx = buildAdbTlsContext(identity)
            val raw = Socket()
            raw.connect(InetSocketAddress(host, port), timeoutMs)
            raw.soTimeout = timeoutMs
            val sslSocket = (sslCtx.socketFactory.createSocket(raw, host, port, true) as SSLSocket).apply {
                enabledProtocols = arrayOf("TLSv1.3")
                useClientMode = true
            }
            sslSocket.use { sock ->
                sock.startHandshake()
                val exported = ShellCaptureCompat.exportTlsKeyingMaterial(
                    socket = sock,
                    label = TLS_EXPORT_LABEL,
                    context = null,
                    length = TLS_EXPORT_LENGTH,
                )
                val password = pin.toByteArray(Charsets.US_ASCII) + exported
                val spake = Spake25519(
                    role = Spake25519.Role.ALICE,
                    myName = SPAKE2_CLIENT_NAME,
                    theirName = SPAKE2_SERVER_NAME,
                )
                val ourMsg = spake.generateMsg(password)

                val input = DataInputStream(sock.inputStream)
                val output = sock.outputStream

                // 1. 交换 SPAKE2 消息
                writePacket(output, AdbCrypto.PAIRING_PACKET_SPAKE2_MSG, ourMsg)
                val peerSpakeHeader = readHeader(input)
                if (peerSpakeHeader.type != AdbCrypto.PAIRING_PACKET_SPAKE2_MSG) {
                    return PairResult.Failure("配对协议错误：预期 SPAKE2_MSG，收到 type=${peerSpakeHeader.type}")
                }
                val peerSpakeMsg = ByteArray(peerSpakeHeader.payloadSize)
                input.readFully(peerSpakeMsg)

                val sharedKey = spake.processMsg(peerSpakeMsg)
                val cipher = AdbCrypto.AdbPairingCipher(sharedKey)

                // 2. 加密交换 PeerInfo（客户端发送 ADB_RSA_PUB_KEY）
                val peerInfo = AdbCrypto.buildPeerInfo(identity.encodeAndroidPublicKeyLine())
                val encPeerInfo = cipher.encrypt(peerInfo)
                writePacket(output, AdbCrypto.PAIRING_PACKET_PEER_INFO, encPeerInfo)

                val serverPeerHeader = readHeader(input)
                if (serverPeerHeader.type != AdbCrypto.PAIRING_PACKET_PEER_INFO) {
                    return PairResult.Failure("配对失败：对端拒绝或配对码不匹配（type=${serverPeerHeader.type}）")
                }
                val encServerPeerInfo = ByteArray(serverPeerHeader.payloadSize)
                input.readFully(encServerPeerInfo)

                val decServerPeerInfo = try {
                    cipher.decrypt(encServerPeerInfo)
                } catch (e: Exception) {
                    return PairResult.Failure("配对码验证失败，请核对 6 位配对码后重试", e)
                }
                if (decServerPeerInfo.size != AdbCrypto.PEER_INFO_SIZE) {
                    return PairResult.Failure("对端 PeerInfo 长度异常：${decServerPeerInfo.size}")
                }
                val guid = extractNullTerminatedAscii(decServerPeerInfo, 1)
                PairResult.Success(guid)
            }
        } catch (e: Exception) {
            PairResult.Failure("无线调试配对失败：${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    internal fun buildAdbTlsContext(identity: AdbCrypto.AdbIdentity): SSLContext {
        val km = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
                arrayOf("adb")
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String =
                "adb"
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
                emptyArray()
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
                null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> =
                arrayOf(identity.certificate)
            override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey
        }
        // AOSP pairing_connection.cpp / tls_connection.cpp 对本机 adbd 证书采用配对/双向验证，不查公网 CA
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLSv1.3").apply {
            init(arrayOf<KeyManager>(km), arrayOf<TrustManager>(tm), SecureRandom())
        }
    }

    private fun writePacket(out: OutputStream, type: Byte, payload: ByteArray) {
        val header = AdbCrypto.PairingHeader(
            version = AdbCrypto.PAIRING_HEADER_VERSION,
            type = type,
            payloadSize = payload.size,
        )
        out.write(header.encode())
        out.write(payload)
        out.flush()
    }

    private fun readHeader(input: DataInputStream): AdbCrypto.PairingHeader {
        val raw = ByteArray(AdbCrypto.PAIRING_HEADER_SIZE)
        input.readFully(raw)
        val h = AdbCrypto.PairingHeader.decode(raw)
        require(h.version == AdbCrypto.PAIRING_HEADER_VERSION) {
            "不支持的配对协议版本：${h.version}"
        }
        require(h.payloadSize in 1..(AdbCrypto.PEER_INFO_SIZE * 2)) {
            "非法配对载荷长度：${h.payloadSize}"
        }
        return h
    }

    private fun extractNullTerminatedAscii(bytes: ByteArray, offset: Int): String {
        var end = offset
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        return String(bytes, offset, end - offset, Charsets.US_ASCII)
    }
}
