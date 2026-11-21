package com.miplay.sender.capture.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCaptureProtocolTest {
    @Test
    fun tokenHexRoundTripAndConstantTimeValidation() {
        val token = ShellCaptureProtocol.generateRandomTokenBytes()
        assertEquals(ShellCaptureProtocol.TOKEN_BYTES, token.size)

        val hex = ShellCaptureProtocol.toHex(token)
        assertEquals(64, hex.length)

        val decoded = ShellCaptureProtocol.fromHex(hex)
        assertNotNull(decoded)
        assertArrayEquals(token, decoded)
        assertTrue(ShellCaptureProtocol.constantTimeTokenEquals(token, decoded!!))

        val tampered = decoded.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()
        assertFalse(ShellCaptureProtocol.constantTimeTokenEquals(token, tampered))
        assertNull(ShellCaptureProtocol.fromHex("short"))
        assertNull(ShellCaptureProtocol.fromHex("z".repeat(64)))
    }

    @Test
    fun controlCommandAndResponseJsonRoundTrip() {
        val cmdLine = ShellCaptureProtocol.encodeCommand(
            cmd = ShellCaptureProtocol.CMD_START_SINGLE_UID,
            uid = 10234,
            muteLocal = true,
        )
        val parsed = ShellCaptureProtocol.parseCommand(cmdLine)!!
        assertEquals(ShellCaptureProtocol.CMD_START_SINGLE_UID, parsed.cmd)
        assertEquals(10234, parsed.uid)
        assertTrue(parsed.muteLocal)

        val respLine = ShellCaptureProtocol.encodeResponse(
            ok = true,
            muteHandling = "ISOLATED_APP_LOOPBACK",
            status = "ready",
        )
        val resp = ShellCaptureProtocol.parseResponse(respLine)!!
        assertTrue(resp.ok)
        assertEquals("ISOLATED_APP_LOOPBACK", resp.muteHandling)
        assertEquals("ready", resp.status)
    }

    @Test
    fun launchCommandQuotesApkPathSafely() {
        val cmd = ShellCaptureProtocol.buildAppProcessLaunchCommand(
            apkPath = "/data/app/it's/base.apk",
            socketPrefix = "miplay_cap_abcd",
            tokenHex = "a".repeat(64),
        )
        assertTrue(cmd.contains("CLASSPATH='/data/app/it'\\''s/base.apk'"))
        assertTrue(cmd.contains("com.miplay.sender.capture.shell.ShellAudioHelperMain"))
        assertTrue(cmd.contains("--socket 'miplay_cap_abcd'"))
    }
}
