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
    fun receiverIndicatorShowsMillisecondsOnlyForSelectedLiveReceiver() {
        val stats = JSONObject(
            """{"receivers":[{"ip":"192.168.1.10","latency_e2e_est_ms":1080}]}""",
        )

        assertEquals(
            LatencyDisplay.ReceiverIndicator.Value(1080),
            LatencyDisplay.receiverIndicator(stats, "192.168.1.10", selected = true, casting = true),
        )
        assertEquals("1080 ms", LatencyDisplay.formatReceiverValue(1080))
        assertEquals(
            LatencyDisplay.ReceiverIndicator.Hidden,
            LatencyDisplay.receiverIndicator(stats, "192.168.1.10", selected = false, casting = true),
        )
        assertEquals(
            LatencyDisplay.ReceiverIndicator.Hidden,
            LatencyDisplay.receiverIndicator(stats, "192.168.1.10", selected = true, casting = false),
        )
        assertEquals(
            LatencyDisplay.ReceiverIndicator.Hidden,
            LatencyDisplay.receiverIndicator(
                stats,
                "192.168.1.10",
                selected = true,
                casting = true,
                failed = true,
            ),
        )
    }

    @Test
    fun receiverIndicatorLoadsForMissingValueButHidesMissingOrMalformedStats() {
        val missingValue = JSONObject("""{"receivers":[{"ip":"192.168.1.10","latency_e2e_est_ms":null}]}""")
        assertEquals(
            LatencyDisplay.ReceiverIndicator.Loading,
            LatencyDisplay.receiverIndicator(missingValue, "192.168.1.10", selected = true, casting = true),
        )
        assertEquals(
            LatencyDisplay.ReceiverIndicator.Hidden,
            LatencyDisplay.receiverIndicator(null, "192.168.1.10", selected = true, casting = true),
        )
        assertEquals(
            LatencyDisplay.ReceiverIndicator.Hidden,
            LatencyDisplay.receiverIndicator(
                JSONObject("""{"receivers":[{"ip":"192.168.1.11","latency_e2e_est_ms":null}]}"""),
                "192.168.1.10",
                selected = true,
                casting = true,
            ),
        )
        assertEquals(
            LatencyDisplay.ReceiverIndicator.Hidden,
            LatencyDisplay.receiverIndicator(
                JSONObject("""{"receivers":[{"ip":"192.168.1.10","latency_e2e_est_ms":"bad"}]}"""),
                "192.168.1.10",
                selected = true,
                casting = true,
            ),
        )
        assertEquals(
            LatencyDisplay.ReceiverIndicator.Hidden,
            LatencyDisplay.receiverIndicator(
                JSONObject("""{"receivers":[{"ip":"192.168.1.10","latency_e2e_est_ms":-1}]}"""),
                "192.168.1.10",
                selected = true,
                casting = true,
            ),
        )
    }

    @Test
    fun receiverIndicatorHidesFailedOrDisconnectedReceiver() {
        val stats = JSONObject("""{"receivers":[{"ip":"192.168.1.10","error":"offline","latency_e2e_est_ms":1080}]}""")
        assertEquals(
            LatencyDisplay.ReceiverIndicator.Hidden,
            LatencyDisplay.receiverIndicator(stats, "192.168.1.10", selected = true, casting = true),
        )
    }

    @Test
    fun mergeConnectingReceiversAddsLoadingPlaceholderWithoutOverwritingActiveSpeaker() {
        val initial = """{"receivers":[{"ip":"192.168.1.10","phase":"streaming","latency_e2e_est_ms":925}]}"""
        val mergedStr = CastService.mergeConnectingReceivers(
            initial,
            setOf("192.168.1.10", "192.168.1.11"),
        )
        val merged = JSONObject(mergedStr)
        assertEquals(
            LatencyDisplay.ReceiverIndicator.Value(925),
            LatencyDisplay.receiverIndicator(merged, "192.168.1.10", selected = true, casting = true),
        )
        assertEquals(
            LatencyDisplay.ReceiverIndicator.Loading,
            LatencyDisplay.receiverIndicator(merged, "192.168.1.11", selected = true, casting = true),
        )

        val afterRemove = JSONObject(CastService.removeReceiverFromStatsJson(mergedStr, "192.168.1.11"))
        assertEquals(
            LatencyDisplay.ReceiverIndicator.Value(925),
            LatencyDisplay.receiverIndicator(afterRemove, "192.168.1.10", selected = true, casting = true),
        )
        assertEquals(
            LatencyDisplay.ReceiverIndicator.Hidden,
            LatencyDisplay.receiverIndicator(afterRemove, "192.168.1.11", selected = true, casting = true),
        )
    }

    @Test
    fun recordOperationEventRetainsBoundedRecentHistory() {
        CastService.clearRecentEventsForTest()
        CastService.recordOperationEvent("连接音箱 192.168.1.10")
        CastService.recordOperationEvent("排队加入 192.168.1.11（等待首台音箱连接完成）")
        val snap = CastService.recentEventsSnapshot()
        assertEquals(2, snap.size)
        assertTrue(snap[0].contains("连接音箱 192.168.1.10"))
        assertTrue(snap[1].contains("排队加入 192.168.1.11"))
        CastService.clearRecentEventsForTest()
    }

    @Test
    fun receiverIndicatorConsumesCoreNormalizedUiStateAndDisplayLatency() {
        val streaming = JSONObject(
            """{"receivers":[{"ip":"192.0.2.10","ui_state":"streaming","display_latency_ms":912}]}""",
        )
        assertEquals(
            LatencyDisplay.ReceiverIndicator.Value(912),
            LatencyDisplay.receiverIndicator(streaming, "192.0.2.10", selected = true, casting = true),
        )
        val connecting = JSONObject(
            """{"receivers":[{"ip":"192.0.2.10","ui_state":"connecting","display_latency_ms":null}]}""",
        )
        assertEquals(
            LatencyDisplay.ReceiverIndicator.Loading,
            LatencyDisplay.receiverIndicator(connecting, "192.0.2.10", selected = true, casting = true),
        )
        val reconnecting = JSONObject(
            """{"receivers":[{"ip":"192.0.2.10","ui_state":"reconnecting","display_latency_ms":null,"error":"timeout"}]}""",
        )
        assertEquals(
            LatencyDisplay.ReceiverIndicator.Loading,
            LatencyDisplay.receiverIndicator(reconnecting, "192.0.2.10", selected = true, casting = true),
        )
        val failed = JSONObject(
            """{"receivers":[{"ip":"192.0.2.10","ui_state":"error","display_latency_ms":null,"error":"auth failed"}]}""",
        )
        assertEquals(
            LatencyDisplay.ReceiverIndicator.Hidden,
            LatencyDisplay.receiverIndicator(failed, "192.0.2.10", selected = true, casting = true),
        )
    }
}
