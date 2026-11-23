package com.miaocast.app.capture.shell

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
            ctlPort = 41234,
            pcmPort = 41235,
            tokenHex = "a".repeat(64),
        )
        assertTrue(cmd.startsWith("(CLASSPATH='/data/app/it'\\''s/base.apk' setsid /system/bin/app_process"))
        assertTrue(cmd.contains("com.miaocast.app.capture.shell.ShellAudioHelperMain"))
        assertTrue(cmd.contains("--ctl-port 41234"))
        assertTrue(cmd.contains("--pcm-port 41235"))
        assertTrue(cmd.endsWith("</dev/null >/dev/null 2>&1 &); sleep 1"))
    }
}
