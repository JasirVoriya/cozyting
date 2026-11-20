package com.miplay.sender.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    @Test
    fun parsesKnownModesAndDefaultsToSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromId("system"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromId("LIGHT"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromId(" dark "))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromId(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromId("unknown"))
    }

    @Test
    fun resolvesDarkFlagAcrossSystemLightAndDarkModes() {
        assertTrue(ThemeMode.resolveIsDark(ThemeMode.SYSTEM, systemNight = true))
        assertFalse(ThemeMode.resolveIsDark(ThemeMode.SYSTEM, systemNight = false))
        assertFalse(ThemeMode.resolveIsDark(ThemeMode.LIGHT, systemNight = true))
        assertTrue(ThemeMode.resolveIsDark(ThemeMode.DARK, systemNight = false))
    }

    @Test
    fun quickToggleSwitchesBetweenLightAndDark() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.quickToggleTarget(currentIsDark = true))
        assertEquals(ThemeMode.DARK, ThemeMode.quickToggleTarget(currentIsDark = false))
    }

    @Test
    fun palettesMaintainDistinctDarkAndLightStyleBContrast() {
        val dark = UiPalette.forMode(isDark = true)
        val light = UiPalette.forMode(isDark = false)
        assertTrue(dark.isDark)
        assertFalse(light.isDark)
        assertNotEquals(dark.bgCanvas, light.bgCanvas)
        assertNotEquals(dark.rowSelectedBg, light.rowSelectedBg)
        assertNotEquals(dark.rowSelectedBg, dark.onSelectedPrimary)
        assertNotEquals(light.rowSelectedBg, light.onSelectedPrimary)
    }
}
