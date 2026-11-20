package com.miplay.sender.cast

import com.miplay.sender.cast.CastRouteSelection.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolBadgeTest {
    @Test
    fun thisDeviceHasNoProtocolBadge() {
        assertNull(ProtocolBadge.forDiscoveredDevice(Kind.THIS_DEVICE, discoveredViaMiPlayStack = true))
    }

    @Test
    fun miPlayStackMapsSpeakerAndTvToMiPlayOnly() {
        assertEquals(CastProtocol.MIPLAY, ProtocolBadge.forDiscoveredDevice(Kind.SPEAKER, true))
        assertEquals(CastProtocol.MIPLAY, ProtocolBadge.forDiscoveredDevice(Kind.TV, true))
        // 禁止给 MiPlay 发现栈设备标成其它协议
        assertNotEquals(CastProtocol.DLNA, ProtocolBadge.forDiscoveredDevice(Kind.TV, true))
        assertNotEquals(CastProtocol.AIRPLAY, ProtocolBadge.forDiscoveredDevice(Kind.SPEAKER, true))
        assertNotEquals(CastProtocol.GOOGLE_CAST, ProtocolBadge.forDiscoveredDevice(Kind.TV, true))
    }

    @Test
    fun drawableMappingCoversAllProtocols() {
        CastProtocol.legendOrder().forEach { p ->
            assertTrue("missing drawable for $p", ProtocolBadge.drawableRes(p) != 0)
            assertTrue(ProtocolBadge.contentDescription(p).contains(p.labelZh))
        }
    }

    @Test
    fun onlyMiPlayIsImplementedForCast() {
        assertTrue(CastProtocol.MIPLAY.castImplemented)
        assertFalse(CastProtocol.DLNA.castImplemented)
        assertFalse(CastProtocol.AIRPLAY.castImplemented)
        assertFalse(CastProtocol.GOOGLE_CAST.castImplemented)
    }

    @Test
    fun fromId() {
        assertEquals(CastProtocol.MIPLAY, CastProtocol.fromId("miplay"))
        assertEquals(CastProtocol.GOOGLE_CAST, CastProtocol.fromId("gcast"))
        assertNull(CastProtocol.fromId("unknown"))
    }

    @Test
    fun contentDescriptionMarksUnimplemented() {
        assertFalse(ProtocolBadge.contentDescription(CastProtocol.MIPLAY).contains("尚未实现"))
        assertTrue(ProtocolBadge.contentDescription(CastProtocol.DLNA).contains("尚未实现"))
        assertNotNull(ProtocolBadge.contentDescription(CastProtocol.AIRPLAY))
    }
}
