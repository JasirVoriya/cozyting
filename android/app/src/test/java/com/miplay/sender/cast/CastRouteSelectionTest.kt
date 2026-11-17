package com.miplay.sender.cast

import com.miplay.sender.cast.CastRouteSelection.Effect
import com.miplay.sender.cast.CastRouteSelection.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastRouteSelectionTest {
    private fun seed(): CastRouteSelection.State {
        return CastRouteSelection.initial(
            listOf(
                CastRouteSelection.Device("this", "This device", Kind.THIS_DEVICE, true),
                CastRouteSelection.Device("s1", "Sound Pro", Kind.SPEAKER, true, protocol = CastProtocol.MIPLAY),
                CastRouteSelection.Device("s2", "小爱音箱", Kind.SPEAKER, true, protocol = CastProtocol.MIPLAY),
                CastRouteSelection.Device(
                    "tv",
                    "伍学明的REDMI G Pro 27U",
                    Kind.TV,
                    castable = true,
                    protocol = CastProtocol.MIPLAY,
                ),
            ),
        )
    }

    @Test
    fun initialSelectsThisDeviceOnly() {
        val s = seed()
        assertTrue(s.thisDeviceSelected)
        assertTrue(s.selectedSpeakerIds.isEmpty())
        assertTrue(s.devices.first { it.kind == Kind.THIS_DEVICE }.selected)
        assertTrue(s.devices.filter { it.isCastSink }.none { it.selected })
    }

    @Test
    fun selectingSpeakerClearsThisDeviceAndStartsCast() {
        val t = CastRouteSelection.setSpeakerChecked(seed(), "s1", true)
        assertFalse(t.state.thisDeviceSelected)
        assertEquals(setOf("s1"), t.state.selectedSpeakerIds)
        assertTrue(t.state.devices.first { it.id == "s1" }.selected)
        assertFalse(t.state.devices.first { it.kind == Kind.THIS_DEVICE }.selected)
        assertEquals(listOf(Effect.StartCast(setOf("s1"))), t.effects)
    }

    @Test
    fun tvIsLegalCastTargetLikeSpeaker() {
        val t = CastRouteSelection.setSpeakerChecked(seed(), "tv", true)
        assertFalse(t.state.thisDeviceSelected)
        assertEquals(setOf("tv"), t.state.selectedSpeakerIds)
        assertTrue(t.state.devices.first { it.id == "tv" }.selected)
        assertEquals(listOf(Effect.StartCast(setOf("tv"))), t.effects)
        assertFalse(t.effects.any { it is Effect.RejectNonCastable })
    }

    @Test
    fun tvAndSpeakersCanMultiSelect() {
        var s = CastRouteSelection.setSpeakerChecked(seed(), "s1", true).state
        s = CastRouteSelection.setSpeakerChecked(s, "tv", true).state
        val t = CastRouteSelection.setSpeakerChecked(s, "s2", true)
        assertEquals(setOf("s1", "tv", "s2"), t.state.selectedSpeakerIds)
        assertTrue(t.state.devices.first { it.id == "tv" }.selected)
        // 会话层支持多接收端；无假装失败
        assertEquals(listOf(Effect.AddSpeaker("s2")), t.effects)
    }

    @Test
    fun uncheckLastIncludingTvReturnsThisDevice() {
        val casting = CastRouteSelection.setSpeakerChecked(seed(), "tv", true).state
        val t = CastRouteSelection.setSpeakerChecked(casting, "tv", false)
        assertTrue(t.state.thisDeviceSelected)
        assertTrue(t.state.selectedSpeakerIds.isEmpty())
        assertEquals(listOf(Effect.StopCastToThisDevice), t.effects)
    }

    @Test
    fun unimplementedProtocolCannotPretendConnected() {
        val s = CastRouteSelection.initial(
            listOf(
                CastRouteSelection.Device("this", "This device", Kind.THIS_DEVICE, true),
                CastRouteSelection.Device(
                    "dlna1",
                    "某 DLNA",
                    Kind.SPEAKER,
                    castable = false,
                    detail = "DLNA 协议尚未在本 App 实现，不能假装投送成功",
                    protocol = CastProtocol.DLNA,
                ),
            ),
        )
        val t = CastRouteSelection.setSpeakerChecked(s, "dlna1", true)
        assertTrue(t.state.thisDeviceSelected)
        assertTrue(t.effects.any { it is Effect.RejectNonCastable })
        assertFalse(t.state.devices.first { it.id == "dlna1" }.selected)
    }

    @Test
    fun looksLikeTvHeuristic() {
        assertTrue(CastRouteSelection.looksLikeTv("伍学明的REDMI G Pro 27U", ""))
        assertTrue(CastRouteSelection.looksLikeTv("客厅电视", "MiTV"))
        assertFalse(CastRouteSelection.looksLikeTv("Sound Pro-1905", "LX06"))
    }

    @Test
    fun selectThisDeviceStopsCast() {
        var s = CastRouteSelection.setSpeakerChecked(seed(), "s1", true).state
        s = CastRouteSelection.setSpeakerChecked(s, "tv", true).state
        val t = CastRouteSelection.selectThisDevice(s)
        assertTrue(t.state.thisDeviceSelected)
        assertTrue(t.state.selectedSpeakerIds.isEmpty())
        assertEquals(listOf(Effect.StopCastToThisDevice), t.effects)
    }
}
