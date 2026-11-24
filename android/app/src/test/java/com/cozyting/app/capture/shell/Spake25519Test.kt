package com.cozyting.app.capture.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class Spake25519Test {
    @Test
    fun boringSslMandNPointsMatchExactCanonicalEncoding() {
        val mHex = ShellCaptureProtocol.toHex(Spake25519.Edwards25519.encode(Spake25519.Edwards25519.M))
        val nHex = ShellCaptureProtocol.toHex(Spake25519.Edwards25519.encode(Spake25519.Edwards25519.N))
        assertEquals("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e", mHex)
        assertEquals("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778", nHex)

        // 解码再编码往返一致
        val mDecoded = Spake25519.Edwards25519.decode(ShellCaptureProtocol.fromHex(mHex)!!)!!
        assertEquals(mHex, ShellCaptureProtocol.toHex(Spake25519.Edwards25519.encode(mDecoded)))
    }

    @Test
    fun boringSslPasswordScalarIsAlwaysMultipleOf8AndCongruentModL() {
        for (seed in 0..15) {
            val hash64 = ByteArray(64) { idx -> ((seed * 31 + idx * 17) and 0xff).toByte() }
            val scalar = Spake25519.computeBoringSslPasswordScalar(hash64)
            assertEquals(BigInteger.ZERO, scalar.and(BigInteger.valueOf(7)))
            assertEquals(Spake25519.leBytesToBigInt(hash64).mod(Spake25519.L), scalar.mod(Spake25519.L))
        }
    }

    @Test
    fun aliceAndBobDeriveIdentical64ByteKeyWithAospAdbNames() {
        val password = "123456".toByteArray(Charsets.US_ASCII) + ByteArray(64) { (it + 7).toByte() }
        val alice = Spake25519(
            role = Spake25519.Role.ALICE,
            myName = WirelessAdbPairingClient.SPAKE2_CLIENT_NAME,
            theirName = WirelessAdbPairingClient.SPAKE2_SERVER_NAME,
        )
        val bob = Spake25519(
            role = Spake25519.Role.BOB,
            myName = WirelessAdbPairingClient.SPAKE2_SERVER_NAME,
            theirName = WirelessAdbPairingClient.SPAKE2_CLIENT_NAME,
        )

        val aliceMsg = alice.generateMsg(password, ByteArray(64) { (it + 1).toByte() })
        val bobMsg = bob.generateMsg(password, ByteArray(64) { (it + 99).toByte() })
        assertEquals(32, aliceMsg.size)
        assertEquals(32, bobMsg.size)

        val aliceKey = alice.processMsg(bobMsg)
        val bobKey = bob.processMsg(aliceMsg)
        assertEquals(64, aliceKey.size)
        assertArrayEquals(aliceKey, bobKey)
    }

    @Test
    fun mismatchedPasswordProducesDifferentSharedKeys() {
        val alice = Spake25519(
            role = Spake25519.Role.ALICE,
            myName = WirelessAdbPairingClient.SPAKE2_CLIENT_NAME,
            theirName = WirelessAdbPairingClient.SPAKE2_SERVER_NAME,
        )
        val bob = Spake25519(
            role = Spake25519.Role.BOB,
            myName = WirelessAdbPairingClient.SPAKE2_SERVER_NAME,
            theirName = WirelessAdbPairingClient.SPAKE2_CLIENT_NAME,
        )
        val aliceMsg = alice.generateMsg("123456".toByteArray())
        val bobMsg = bob.generateMsg("654321".toByteArray())

        val aliceKey = alice.processMsg(bobMsg)
        val bobKey = bob.processMsg(aliceMsg)
        assertFalse(aliceKey.contentEquals(bobKey))
    }

    @Test
    fun aospTrailingNulConstantsHaveExpectedLengths() {
        assertEquals(16, WirelessAdbPairingClient.SPAKE2_CLIENT_NAME.size)
        assertEquals(16, WirelessAdbPairingClient.SPAKE2_SERVER_NAME.size)
        assertEquals(10, WirelessAdbPairingClient.TLS_EXPORT_LABEL.length)
        assertTrue(WirelessAdbPairingClient.TLS_EXPORT_LABEL.endsWith("\u0000"))
    }
}
