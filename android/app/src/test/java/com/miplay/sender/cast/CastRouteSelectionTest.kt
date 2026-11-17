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
                CastRouteSelection.Device("s1", "Sound Pro", Kind.SPEAKER, true),
                CastRouteSelection.Device("s2", "小爱音箱", Kind.SPEAKER, true),
                CastRouteSelection.Device("tv", "REDMI G Pro", Kind.OTHER, false, detail = "未接入 MiPlay"),
            ),
        )
    }

    @Test
    fun initialSelectsThisDeviceOnly() {
        val s = seed()
        assertTrue(s.thisDeviceSelected)
        assertTrue(s.selectedSpeakerIds.isEmpty())
        assertTrue(s.devices.first { it.kind == Kind.THIS_DEVICE }.selected)
        assertTrue(s.devices.filter { it.kind == Kind.SPEAKER }.none { it.selected })
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
    fun multiSpeakerAddWhileCasting() {
        val after1 = CastRouteSelection.setSpeakerChecked(seed(), "s1", true).state
        val t = CastRouteSelection.setSpeakerChecked(after1, "s2", true)
        assertEquals(setOf("s1", "s2"), t.state.selectedSpeakerIds)
        assertEquals(listOf(Effect.AddSpeaker("s2")), t.effects)
    }

    @Test
    fun uncheckLastSpeakerReturnsThisDeviceAndStops() {
        val casting = CastRouteSelection.setSpeakerChecked(seed(), "s1", true).state
        val t = CastRouteSelection.setSpeakerChecked(casting, "s1", false)
        assertTrue(t.state.thisDeviceSelected)
        assertTrue(t.state.selectedSpeakerIds.isEmpty())
        assertEquals(listOf(Effect.StopCastToThisDevice), t.effects)
    }

    @Test
    fun selectThisDeviceStopsCast() {
        var s = CastRouteSelection.setSpeakerChecked(seed(), "s1", true).state
        s = CastRouteSelection.setSpeakerChecked(s, "s2", true).state
        val t = CastRouteSelection.selectThisDevice(s)
        assertTrue(t.state.thisDeviceSelected)
        assertTrue(t.state.selectedSpeakerIds.isEmpty())
        assertEquals(listOf(Effect.StopCastToThisDevice), t.effects)
    }

    @Test
    fun nonCastableTvRejected() {
        val t = CastRouteSelection.setSpeakerChecked(seed(), "tv", true)
        assertTrue(t.state.thisDeviceSelected)
        assertTrue(t.effects.any { it is Effect.RejectNonCastable })
        assertFalse(t.state.devices.first { it.id == "tv" }.selected)
    }
}
