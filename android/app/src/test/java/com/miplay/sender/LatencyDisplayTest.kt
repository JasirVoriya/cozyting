package com.miplay.sender

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyDisplayTest {
    @Test
    fun unknownIsDashNotZero() {
        assertEquals("—", LatencyDisplay.formatMs(null))
        assertEquals("—", LatencyDisplay.UNKNOWN)
        assertFalse(LatencyDisplay.formatMs(null).contains("0"))
    }

    @Test
    fun formatsMsWithSeconds() {
        val s = LatencyDisplay.formatMs(850)
        assertTrue(s.startsWith("850ms(≈"))
        assertTrue(s.endsWith("s)"))
        assertEquals("1200ms", LatencyDisplay.formatMs(1200, withSeconds = false))
    }

    @Test
    fun optLongNullWhenMissing() {
        val o = JSONObject("""{"e2e_est_ms":null,"schedule_ahead_ms":800}""")
        assertEquals(null, LatencyDisplay.optLongOrNull(o, "e2e_est_ms"))
        assertEquals(800L, LatencyDisplay.optLongOrNull(o, "schedule_ahead_ms"))
        assertEquals(null, LatencyDisplay.optLongOrNull(o, "missing"))
        assertEquals(null, LatencyDisplay.optLongOrNull(null, "e2e_est_ms"))
    }

    @Test
    fun formatBlockPerReceiverAndDefinition() {
        val stats = JSONObject(
            """
            {
              "latency": {
                "kind": "estimate",
                "definition": "estimate: capture_to_play_est_ms=... Not protocol RTT.",
                "playback_delay_ms": 800,
                "e2e_est_ms": 920,
                "schedule_ahead_ms": 780,
                "feed_flushed_aus_at_start": 40,
                "feed_flushed_est_ms": 853
              },
              "receivers": [
                {"ip":"192.168.1.10","latency_e2e_est_ms":920,"latency_schedule_ahead_ms":780,"device_channel":"center"},
                {"ip":"192.168.1.11","latency_e2e_est_ms":null,"latency_schedule_ahead_ms":null}
              ]
            }
            """.trimIndent(),
        )
        val block = LatencyDisplay.formatBlock(stats)
        assertTrue(block.contains("总体E2E 920ms"))
        assertTrue(block.contains("192.168.1.10"))
        assertTrue(block.contains("192.168.1.11"))
        assertTrue(block.contains("E2E —"))
        assertTrue(block.contains("Not protocol RTT"))
        assertTrue(block.contains("estimate"))
        assertFalse(block.lines().any { it.contains("192.168.1.11") && it.contains("E2E 0ms") })
    }

    @Test
    fun appendReceiverLatency() {
        val r = JSONObject("""{"latency_e2e_est_ms":900}""")
        assertEquals("播放中  延迟900ms", LatencyDisplay.appendReceiverLatency("播放中", r))
        val missing = JSONObject("""{}""")
        assertEquals("播放中  延迟—", LatencyDisplay.appendReceiverLatency("播放中", missing))
    }
}
