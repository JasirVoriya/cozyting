package com.cozyting.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureEngineModeTest {
    @Test
    fun defaultEngineSelectsWirelessDebugOnApi33PlusAndStandardOnApi29To32() {
        assertEquals(CaptureEngineMode.STANDARD, CaptureEngineMode.defaultForSdkInt(29))
        assertEquals(CaptureEngineMode.STANDARD, CaptureEngineMode.defaultForSdkInt(30))
        assertEquals(CaptureEngineMode.STANDARD, CaptureEngineMode.defaultForSdkInt(32))
        assertEquals(CaptureEngineMode.WIRELESS_DEBUG, CaptureEngineMode.defaultForSdkInt(33))
        assertEquals(CaptureEngineMode.WIRELESS_DEBUG, CaptureEngineMode.defaultForSdkInt(34))
        assertEquals(CaptureEngineMode.WIRELESS_DEBUG, CaptureEngineMode.defaultForSdkInt(36))
    }

    @Test
    fun api29RejectsWirelessDebugAndAllowsStandard() {
        val caps = CaptureEngineCapabilities.forSdkInt(29)
        assertFalse(caps.supportsWirelessDebugEngine)
        assertFalse(caps.supportsShellSingleAppPolicy)
        assertFalse(caps.supportsTrueSingleAppLocalMute)

        val std = caps.validate(
            requestedEngine = CaptureEngineMode.STANDARD,
            requestedScope = CaptureScopeMode.SINGLE_APP,
            hasPairedCertificate = false,
            keepLocalOutput = false,
        )
        assertTrue(std is CaptureEngineCapabilities.ValidationResult.Allowed)

        val wireless = caps.validate(
            requestedEngine = CaptureEngineMode.WIRELESS_DEBUG,
            requestedScope = CaptureScopeMode.SINGLE_APP,
            hasPairedCertificate = true,
            keepLocalOutput = false,
        )
        assertTrue(wireless is CaptureEngineCapabilities.ValidationResult.Rejected)
    }

    @Test
    fun api30To32FailClosedRefusesSingleAppAndAllowsExplicitGlobalMedia() {
        val caps = CaptureEngineCapabilities.forSdkInt(31)
        assertTrue(caps.supportsWirelessDebugEngine)
        assertFalse(caps.supportsShellSingleAppPolicy)
        assertTrue(caps.supportsShellGlobalSubmix)

        val singleApp = caps.validate(
            requestedEngine = CaptureEngineMode.WIRELESS_DEBUG,
            requestedScope = CaptureScopeMode.SINGLE_APP,
            hasPairedCertificate = true,
            keepLocalOutput = false,
        )
        assertTrue(singleApp is CaptureEngineCapabilities.ValidationResult.Rejected)
        assertTrue((singleApp as CaptureEngineCapabilities.ValidationResult.Rejected).messageZh.contains("Fail-Closed"))

        val globalMedia = caps.validate(
            requestedEngine = CaptureEngineMode.WIRELESS_DEBUG,
            requestedScope = CaptureScopeMode.GLOBAL_MEDIA,
            hasPairedCertificate = true,
            keepLocalOutput = false,
        )
        assertTrue(globalMedia is CaptureEngineCapabilities.ValidationResult.Allowed)
        val allowed = globalMedia as CaptureEngineCapabilities.ValidationResult.Allowed
        assertEquals(CaptureScopeMode.GLOBAL_MEDIA, allowed.effectiveScope)
        assertFalse(allowed.usesTrueSingleAppMute)
    }

    @Test
    fun api33RequiresPairingAndEnablesTrueSingleAppMute() {
        val caps = CaptureEngineCapabilities.forSdkInt(34)
        assertTrue(caps.supportsShellSingleAppPolicy)
        assertTrue(caps.supportsTrueSingleAppLocalMute)

        val unpaired = caps.validate(
            requestedEngine = CaptureEngineMode.WIRELESS_DEBUG,
            requestedScope = CaptureScopeMode.SINGLE_APP,
            hasPairedCertificate = false,
            keepLocalOutput = false,
        )
        assertTrue(unpaired is CaptureEngineCapabilities.ValidationResult.Rejected)

        val pairedMuted = caps.validate(
            requestedEngine = CaptureEngineMode.WIRELESS_DEBUG,
            requestedScope = CaptureScopeMode.SINGLE_APP,
            hasPairedCertificate = true,
            keepLocalOutput = false,
        ) as CaptureEngineCapabilities.ValidationResult.Allowed
        assertTrue(pairedMuted.usesTrueSingleAppMute)

        val pairedKeepLocal = caps.validate(
            requestedEngine = CaptureEngineMode.WIRELESS_DEBUG,
            requestedScope = CaptureScopeMode.SINGLE_APP,
            hasPairedCertificate = true,
            keepLocalOutput = true,
        ) as CaptureEngineCapabilities.ValidationResult.Allowed
        assertFalse(pairedKeepLocal.usesTrueSingleAppMute)
    }
}
