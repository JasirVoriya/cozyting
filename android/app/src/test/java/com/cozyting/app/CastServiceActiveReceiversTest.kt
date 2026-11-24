package com.cozyting.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastServiceActiveReceiversTest {

    @After
    fun tearDown() {
        CastService.running.set(false)
        CastService.lastStats = ""
        CastService.clearActiveReceiverIps()
    }

    @Test
    fun extractReceiverIpsFromStats_handlesValidBlankAndMalformedJson() {
        val stats = """{"receivers":[{"ip":"192.0.2.10","phase":"streaming"},{"ip":" 192.0.2.11 ","phase":"connecting"}]}"""
        assertEquals(setOf("192.0.2.10", "192.0.2.11"), CastService.extractReceiverIpsFromStats(stats))
        assertTrue(CastService.extractReceiverIpsFromStats("").isEmpty())
        assertTrue(CastService.extractReceiverIpsFromStats("not-json").isEmpty())
    }

    @Test
    fun activeReceiverIps_isEmptyWhenNotRunning() {
        CastService.running.set(false)
        CastService.setActiveReceiverIps(listOf("192.0.2.10"))
        CastService.lastStats = """{"receivers":[{"ip":"192.0.2.10","phase":"streaming"}]}"""
        assertTrue(CastService.activeReceiverIps().isEmpty())
    }

    @Test
    fun activeReceiverIps_prefersStatsAndFallsBackToMemorySet() {
        CastService.running.set(true)
        CastService.setActiveReceiverIps(listOf("192.0.2.10"))
        CastService.lastStats = ""
        assertEquals(setOf("192.0.2.10"), CastService.activeReceiverIps())

        CastService.lastStats = """{"receivers":[{"ip":"192.0.2.10","phase":"streaming"},{"ip":"192.0.2.11","phase":"connecting"}]}"""
        assertEquals(setOf("192.0.2.10", "192.0.2.11"), CastService.activeReceiverIps())
    }

    @Test
    fun primeConnectingAndRemovingKeepStatsAndActiveSetInSync() {
        CastService.running.set(true)
        CastService.lastStats = ""
        CastService.clearActiveReceiverIps()

        CastService.primeConnectingReceivers(listOf("192.0.2.10", "192.0.2.11"))
        assertEquals(setOf("192.0.2.10", "192.0.2.11"), CastService.activeReceiverIps())

        CastService.primeRemovingReceiver("192.0.2.10")
        assertEquals(setOf("192.0.2.11"), CastService.activeReceiverIps())

        CastService.primeRemovingReceiver("192.0.2.11")
        assertTrue(CastService.activeReceiverIps().isEmpty())
    }
}
