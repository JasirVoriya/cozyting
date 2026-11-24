package com.cozyting.app.capture.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey

class AdbCryptoTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun generatedIdentityHasValidSelfSignedX509And524ByteAndroidPubKey() {
        val id = AdbCrypto.generateIdentity("MiPlayTest")
        id.certificate.verify(id.certificate.publicKey)

        val rsaPub = id.certificate.publicKey as RSAPublicKey
        val encoded = AdbCrypto.encodeAndroidRsaPublicKey(rsaPub)
        assertEquals(AdbCrypto.ANDROID_PUBKEY_ENCODED_SIZE, encoded.size)

        val bb = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(AdbCrypto.ANDROID_PUBKEY_MODULUS_WORDS, bb.int)
        val n0inv = bb.int.toLong() and 0xffffffffL
        val modLow = ByteBuffer.wrap(encoded, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        // 验证 Montgomery 参数: n0inv * n[0] ≡ -1 (mod 2^32)
        val prod = (n0inv * modLow) and 0xffffffffL
        assertEquals(0xffffffffL, prod)

        val exp = ByteBuffer.wrap(encoded, 520, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(rsaPub.publicExponent.toInt(), exp)

        val peerInfo = AdbCrypto.buildPeerInfo(id.encodeAndroidPublicKeyLine())
        assertEquals(AdbCrypto.PEER_INFO_SIZE, peerInfo.size)
        assertEquals(AdbCrypto.PEER_INFO_TYPE_RSA_PUB_KEY, peerInfo[0])
    }

    @Test
    fun pairingCipherEncryptsDecryptsSequentiallyAndRejectsTampering() {
        val shared = ByteArray(64) { (it * 5 + 3).toByte() }
        val sender = AdbCrypto.AdbPairingCipher(shared)
        val receiver = AdbCrypto.AdbPairingCipher(shared)

        val p1 = "hello-peer-info-1".toByteArray()
        val p2 = "hello-peer-info-2".toByteArray()
        val c1 = sender.encrypt(p1)
        val c2 = sender.encrypt(p2)
        assertEquals(p1.size + 16, c1.size)
        assertArrayEquals(p1, receiver.decrypt(c1))
        assertArrayEquals(p2, receiver.decrypt(c2))

        val c3 = sender.encrypt("tamper-check".toByteArray())
        c3[0] = (c3[0].toInt() xor 0x01).toByte()
        var failed = false
        try {
            receiver.decrypt(c3)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun pairingHeaderRoundTripAndKeyStorePersistence() {
        val hdr = AdbCrypto.PairingHeader(
            version = AdbCrypto.PAIRING_HEADER_VERSION,
            type = AdbCrypto.PAIRING_PACKET_PEER_INFO,
            payloadSize = 8208,
        )
        val decoded = AdbCrypto.PairingHeader.decode(hdr.encode())
        assertEquals(hdr, decoded)

        val store = WirelessAdbKeyStore(tempFolder.newFolder("adb_keys"))
        assertFalse(store.isPaired())
        val first = store.getOrCreateIdentity()
        store.markPaired()
        assertTrue(store.isPaired())
        val second = store.getOrCreateIdentity()
        assertArrayEquals(first.certificate.encoded, second.certificate.encoded)

        store.clearPairing()
        assertFalse(store.isPaired())
    }
}
