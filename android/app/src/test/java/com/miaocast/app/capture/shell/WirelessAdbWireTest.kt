package com.miaocast.app.capture.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class WirelessAdbWireTest {
    @Test
    fun adbPacketEncodesAndDecodesHeaderMagicAndPayload() {
        val payload = "host::features=shell_v2,cmd\u0000".toByteArray(Charsets.US_ASCII)
        val pkt = WirelessAdbShellClient.AdbPacket(
            command = WirelessAdbShellClient.A_CNXN,
            arg0 = WirelessAdbShellClient.A_VERSION,
            arg1 = WirelessAdbShellClient.MAX_DATA,
            payload = payload,
        )
        val raw = pkt.encode()
        assertEquals(WirelessAdbShellClient.HEADER_SIZE + payload.size, raw.size)

        val parsed = WirelessAdbShellClient.AdbPacket.readFrom(ByteArrayInputStream(raw))
        assertEquals(WirelessAdbShellClient.A_CNXN, parsed.command)
        assertEquals(WirelessAdbShellClient.A_VERSION, parsed.arg0)
        assertEquals(WirelessAdbShellClient.MAX_DATA, parsed.arg1)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun adbPacketRejectsCorruptedMagic() {
        val pkt = WirelessAdbShellClient.AdbPacket(
            command = WirelessAdbShellClient.A_STLS,
            arg0 = WirelessAdbShellClient.A_STLS_VERSION,
            arg1 = 0,
        )
        val raw = pkt.encode()
        raw[20] = (raw[20].toInt() xor 0xff).toByte()
        var threw = false
        try {
            WirelessAdbShellClient.AdbPacket.readFrom(ByteArrayInputStream(raw))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun buildServiceDestinationDefaultsToNonPtyExec() {
        assertEquals("exec:id\u0000", WirelessAdbShellClient.buildServiceDestination("id"))
        assertEquals("shell:id\u0000", WirelessAdbShellClient.buildServiceDestination("id", useExec = false))
    }
}
