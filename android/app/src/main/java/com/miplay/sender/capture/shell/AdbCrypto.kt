package com.miplay.sender.capture.shell

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 纯 Kotlin 实现的 AOSP 无线 ADB 加密原语：
 * - 2048-bit RSA 密钥对 + 纯 ASN.1 DER 自签 X.509 v3 证书（无 BouncyCastle 依赖）；
 * - AOSP `android_pubkey_encode`（524 字节小端 Montgomery 参数）+ Base64 公钥字符串；
 * - AOSP `Aes128Gcm`（HKDF-SHA256 + 序列号 12 字节 Nonce + AES-128-GCM 16B Tag）；
 * - `PairingPacketHeader`（6 字节）与 `PeerInfo`（8192 字节）序列化。
 */
object AdbCrypto {
    const val ANDROID_PUBKEY_MODULUS_SIZE = 256
    const val ANDROID_PUBKEY_MODULUS_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4
    const val ANDROID_PUBKEY_ENCODED_SIZE = 4 + 4 + ANDROID_PUBKEY_MODULUS_SIZE + ANDROID_PUBKEY_MODULUS_SIZE + 4 // 524

    const val PEER_INFO_SIZE = 8192
    const val PEER_INFO_TYPE_RSA_PUB_KEY: Byte = 0
    const val PEER_INFO_TYPE_DEVICE_GUID: Byte = 1

    const val PAIRING_HEADER_SIZE = 6
    const val PAIRING_HEADER_VERSION: Byte = 1
    const val PAIRING_PACKET_SPAKE2_MSG: Byte = 0
    const val PAIRING_PACKET_PEER_INFO: Byte = 1

    private val HKDF_INFO_AES_128_GCM = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)

    data class AdbIdentity(
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
    ) {
        fun encodeAndroidPublicKeyLine(userHost: String = "miplay@android"): ByteArray {
            val rsaPub = certificate.publicKey as RSAPublicKey
            val raw = encodeAndroidRsaPublicKey(rsaPub)
            val b64 = Base64.getEncoder().encodeToString(raw)
            return "$b64 $userHost\u0000".toByteArray(Charsets.US_ASCII)
        }
    }

    fun generateIdentity(commonName: String = "MiPlaySender"): AdbIdentity {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()
        val certDer = buildSelfSignedX509Der(kp, commonName)
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
        return AdbIdentity(kp.private, cert)
    }

    /**
     * 按 AOSP `libcrypto_utils/android_pubkey.cpp` 编码 524 字节小端 `RSAPublicKey` 结构。
     */
    fun encodeAndroidRsaPublicKey(pubKey: RSAPublicKey): ByteArray {
        val n = pubKey.modulus
        val e = pubKey.publicExponent
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0Inv = r32.subtract(n.mod(r32).modInverse(r32)).mod(r32).toLong()
        val rr = BigInteger.ONE.shiftLeft(ANDROID_PUBKEY_MODULUS_SIZE * 8 * 2).mod(n)

        val buf = ByteBuffer.allocate(ANDROID_PUBKEY_ENCODED_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(ANDROID_PUBKEY_MODULUS_WORDS)
        buf.putInt(n0Inv.toInt())
        buf.put(bigIntToLittleEndian(n, ANDROID_PUBKEY_MODULUS_SIZE))
        buf.put(bigIntToLittleEndian(rr, ANDROID_PUBKEY_MODULUS_SIZE))
        buf.putInt(e.toInt())
        return buf.array()
    }

    fun buildPeerInfo(rsaPublicKeyBytes: ByteArray): ByteArray {
        require(rsaPublicKeyBytes.size <= PEER_INFO_SIZE - 1) { "RSA 公钥长度超出 PeerInfo 容量" }
        val out = ByteArray(PEER_INFO_SIZE)
        out[0] = PEER_INFO_TYPE_RSA_PUB_KEY
        System.arraycopy(rsaPublicKeyBytes, 0, out, 1, rsaPublicKeyBytes.size)
        return out
    }

    data class PairingHeader(
        val version: Byte,
        val type: Byte,
        val payloadSize: Int,
    ) {
        fun encode(): ByteArray = ByteBuffer.allocate(PAIRING_HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(version)
            .put(type)
            .putInt(payloadSize)
            .array()

        companion object {
            fun decode(bytes: ByteArray): PairingHeader {
                require(bytes.size == PAIRING_HEADER_SIZE) { "PairingHeader 长度必须为 6 字节" }
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                return PairingHeader(
                    version = bb.get(),
                    type = bb.get(),
                    payloadSize = bb.int,
                )
            }
        }
    }

    /**
     * 与 AOSP `pairing_auth/aes_128_gcm.cpp` 完全一致的 HKDF-SHA256 + AES-128-GCM 序列号加解密器。
     */
    class AdbPairingCipher(keyMaterial: ByteArray) {
        private val aesKey = SecretKeySpec(
            hkdfSha256(keyMaterial, salt = ByteArray(0), info = HKDF_INFO_AES_128_GCM, length = 16),
            "AES",
        )
        private var encSeq: Long = 0L
        private var decSeq: Long = 0L

        fun encrypt(plaintext: ByteArray): ByteArray {
            val nonce = sequenceNonce(encSeq)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, nonce))
            val out = cipher.doFinal(plaintext)
            encSeq += 1L
            return out
        }

        fun decrypt(ciphertext: ByteArray): ByteArray {
            val nonce = sequenceNonce(decSeq)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, nonce))
            val out = cipher.doFinal(ciphertext)
            decSeq += 1L
            return out
        }

        private fun sequenceNonce(seq: Long): ByteArray = ByteBuffer.allocate(12)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(seq)
            .putInt(0)
            .array()
    }

    /** RFC 5869 HKDF-SHA256（BoringSSL `HKDF` 在 salt=nullptr 时使用 32 字节全 0 salt）。 */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmacSha256(actualSalt, ikm)
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun bigIntToLittleEndian(value: BigInteger, size: Int): ByteArray {
        val out = ByteArray(size)
        val be = value.toByteArray()
        var outIdx = 0
        for (i in be.size - 1 downTo 0) {
            if (outIdx >= size) break
            out[outIdx++] = be[i]
        }
        return out
    }

    // —— 纯 ASN.1 DER 自签 X.509 v3 证书构造（SHA256withRSA）——

    internal fun buildSelfSignedX509Der(kp: KeyPair, commonName: String): ByteArray {
        val serial = BigInteger(63, SecureRandom()).abs().add(BigInteger.ONE)
        val sigAlg = derSequence(
            derOid(intArrayOf(1, 2, 840, 113549, 1, 1, 11)), // sha256WithRSAEncryption
            derNull(),
        )
        val dn = derSequence(
            derSet(
                derSequence(
                    derOid(intArrayOf(2, 5, 4, 3)), // CN
                    derUtf8String(commonName),
                ),
            ),
        )
        val validity = derSequence(
            derUtcTime("240101000000Z"),
            derGeneralizedTime("20500101000000Z"),
        )
        val tbs = derSequence(
            derExplicit(0, derInteger(BigInteger.valueOf(2))), // v3
            derInteger(serial),
            sigAlg,
            dn,
            validity,
            dn,
            kp.public.encoded, // SubjectPublicKeyInfo (已为合法 DER)
        )
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(kp.private)
        signer.update(tbs)
        val sigBytes = signer.sign()
        return derSequence(tbs, sigAlg, derBitString(sigBytes))
    }

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        when {
            len < 0x80 -> out.write(len)
            len <= 0xff -> { out.write(0x81); out.write(len) }
            len <= 0xffff -> { out.write(0x82); out.write((len ushr 8) and 0xff); out.write(len and 0xff) }
            else -> {
                out.write(0x83)
                out.write((len ushr 16) and 0xff)
                out.write((len ushr 8) and 0xff)
                out.write(len and 0xff)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun derSequence(vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        parts.forEach { body.write(it) }
        return derTag(0x30, body.toByteArray())
    }

    private fun derSet(vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        parts.forEach { body.write(it) }
        return derTag(0x31, body.toByteArray())
    }

    private fun derExplicit(tagNum: Int, inner: ByteArray): ByteArray = derTag(0xA0 or tagNum, inner)
    private fun derInteger(v: BigInteger): ByteArray = derTag(0x02, v.toByteArray())
    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)
    private fun derUtf8String(s: String): ByteArray = derTag(0x0C, s.toByteArray(Charsets.UTF_8))
    private fun derUtcTime(s: String): ByteArray = derTag(0x17, s.toByteArray(Charsets.US_ASCII))
    private fun derGeneralizedTime(s: String): ByteArray = derTag(0x18, s.toByteArray(Charsets.US_ASCII))
    private fun derBitString(bytes: ByteArray): ByteArray = derTag(0x03, byteArrayOf(0x00) + bytes)

    private fun derOid(arcs: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(arcs[0] * 40 + arcs[1])
        for (i in 2 until arcs.size) {
            var v = arcs[i]
            val tmp = ByteArray(5)
            var idx = 4
            tmp[idx--] = (v and 0x7f).toByte()
            v = v ushr 7
            while (v > 0) {
                tmp[idx--] = ((v and 0x7f) or 0x80).toByte()
                v = v ushr 7
            }
            out.write(tmp, idx + 1, 4 - idx)
        }
        return derTag(0x06, out.toByteArray())
    }
}

/**
 * 本地无线调试密钥与配对状态持久化仓库（仅存 App 私有目录，绝不备份或上传）。
 */
class WirelessAdbKeyStore(private val dir: File) {
    private val keyFile get() = File(dir, "adb_client.pk8")
    private val certFile get() = File(dir, "adb_client.der")
    private val pairedFlagFile get() = File(dir, "paired.ok")

    @Synchronized
    fun getOrCreateIdentity(): AdbCrypto.AdbIdentity {
        if (keyFile.exists() && certFile.exists()) {
            val loaded = runCatching {
                val priv = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
                AdbCrypto.AdbIdentity(priv, cert)
            }.getOrNull()
            if (loaded != null) return loaded
        }
        dir.mkdirs()
        val created = AdbCrypto.generateIdentity()
        writeOwnerOnly(keyFile, created.privateKey.encoded)
        writeOwnerOnly(certFile, created.certificate.encoded)
        return created
    }

    fun isPaired(): Boolean = pairedFlagFile.exists() && keyFile.exists() && certFile.exists()

    @Synchronized
    fun markPaired() {
        dir.mkdirs()
        writeOwnerOnly(pairedFlagFile, "paired".toByteArray(Charsets.US_ASCII))
    }

    @Synchronized
    fun clearPairing() {
        runCatching { pairedFlagFile.delete() }
        runCatching { keyFile.delete() }
        runCatching { certFile.delete() }
    }

    private fun writeOwnerOnly(file: File, bytes: ByteArray) {
        file.writeBytes(bytes)
        runCatching {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
    }
}
