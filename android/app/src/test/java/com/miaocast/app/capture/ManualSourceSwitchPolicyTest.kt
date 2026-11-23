package com.miaocast.app.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualSourceSwitchPolicyTest {
    @Test
    fun notCasting_persistsSelectionOnly() {
        val action = ManualSourceSwitchPolicy.decide(
            ManualSourceSwitchPolicy.Input(
                serviceRunning = false,
                activeServiceSource = "app",
                activeScopeMode = CaptureScopeMode.SINGLE_APP,
                activeTargetPackage = "com.example.music.a",
                pickedPackage = "com.example.music.b",
            ),
        )
        assertEquals(ManualSourceSwitchPolicy.Action.PERSIST_SELECTION_ONLY, action)
    }

    @Test
    fun silentCast_persistsSelectionOnly() {
        val action = ManualSourceSwitchPolicy.decide(
            ManualSourceSwitchPolicy.Input(
                serviceRunning = true,
                activeServiceSource = "silent",
                activeScopeMode = CaptureScopeMode.SINGLE_APP,
                activeTargetPackage = "",
                pickedPackage = "com.example.music.b",
            ),
        )
        assertEquals(ManualSourceSwitchPolicy.Action.PERSIST_SELECTION_ONLY, action)
    }

    @Test
    fun globalMediaCast_persistsSelectionOnly() {
        val action = ManualSourceSwitchPolicy.decide(
            ManualSourceSwitchPolicy.Input(
                serviceRunning = true,
                activeServiceSource = "app",
                activeScopeMode = CaptureScopeMode.GLOBAL_MEDIA,
                activeTargetPackage = "",
                pickedPackage = "com.example.music.b",
            ),
        )
        assertEquals(ManualSourceSwitchPolicy.Action.PERSIST_SELECTION_ONLY, action)
    }

    @Test
    fun singleAppCast_differentPackage_hotSwitchesWithoutPrePersisting() {
        val action = ManualSourceSwitchPolicy.decide(
            ManualSourceSwitchPolicy.Input(
                serviceRunning = true,
                activeServiceSource = "app",
                activeScopeMode = CaptureScopeMode.SINGLE_APP,
                activeTargetPackage = "com.example.music.a",
                pickedPackage = "com.example.music.b",
            ),
        )
        assertEquals(ManualSourceSwitchPolicy.Action.HOT_SWITCH_IN_CAST, action)
    }

    @Test
    fun singleAppCast_samePackage_keepsActiveTargetWithoutRebuild() {
        val action = ManualSourceSwitchPolicy.decide(
            ManualSourceSwitchPolicy.Input(
                serviceRunning = true,
                activeServiceSource = "app",
                activeScopeMode = CaptureScopeMode.SINGLE_APP,
                activeTargetPackage = "com.example.music.a",
                pickedPackage = " com.example.music.a ",
            ),
        )
        assertEquals(ManualSourceSwitchPolicy.Action.KEEP_ACTIVE_TARGET, action)
    }

    @Test
    fun singleAppCast_blankActiveTarget_hotSwitchesToPickedPackage() {
        val action = ManualSourceSwitchPolicy.decide(
            ManualSourceSwitchPolicy.Input(
                serviceRunning = true,
                activeServiceSource = "app",
                activeScopeMode = CaptureScopeMode.SINGLE_APP,
                activeTargetPackage = "",
                pickedPackage = "com.example.music.a",
            ),
        )
        assertEquals(ManualSourceSwitchPolicy.Action.HOT_SWITCH_IN_CAST, action)
    }
}
