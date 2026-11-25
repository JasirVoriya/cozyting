package com.cozyting.app.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HardwareVolumeKeyControllerTest {

    private class FakeScheduler : HardwareVolumeKeyController.Scheduler {
        var pending: Runnable? = null
        var lastDelayMs: Long = -1L

        override fun postDelayed(runnable: Runnable, delayMs: Long) {
            pending = runnable
            lastDelayMs = delayMs
        }

        override fun removeCallbacks(runnable: Runnable) {
            if (pending === runnable) {
                pending = null
            }
        }

        fun runPending() {
            val r = pending
            pending = null
            r?.run()
        }
    }

    @Test
    fun passesThroughNonVolumeKeysAndLocalPlayback() {
        val scheduler = FakeScheduler()
        var volume = 30
        var casting = false
        val previews = mutableListOf<Int>()
        val commits = mutableListOf<Int>()

        val controller = HardwareVolumeKeyController(
            scheduler = scheduler,
            shouldIntercept = { casting },
            currentVolume = { volume },
            onPreview = { previews += it },
            onCommit = {
                commits += it
                volume = it
            },
        )

        assertFalse(controller.onDispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
        assertFalse(controller.onDispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP)))
        assertTrue(previews.isEmpty())
        assertTrue(commits.isEmpty())

        casting = true
        assertFalse(controller.onDispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
    }

    @Test
    fun interceptsVolumeUpAndDownWhenCastingWithStepClampAndCoalescedCommit() {
        val scheduler = FakeScheduler()
        var volume = 35
        val previews = mutableListOf<Int>()
        val commits = mutableListOf<Int>()

        val controller = HardwareVolumeKeyController(
            stepPercent = 5,
            commitDebounceMs = 100L,
            scheduler = scheduler,
            shouldIntercept = { true },
            currentVolume = { volume },
            onPreview = { previews += it },
            onCommit = {
                commits += it
                volume = it
            },
        )

        // Rapid hold: 3 volume-up repeats before release
        assertTrue(controller.onDispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
        assertTrue(controller.onDispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
        assertTrue(controller.onDispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
        assertEquals(listOf(40, 45, 50), previews)
        assertTrue(commits.isEmpty())
        assertEquals(100L, scheduler.lastDelayMs)

        // Release flushes immediately once
        assertTrue(controller.onDispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP)))
        assertEquals(listOf(50), commits)
        assertEquals(50, volume)

        // Clamp at 0% on volume down
        volume = 3
        previews.clear()
        commits.clear()
        assertTrue(controller.onDispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
        assertEquals(listOf(0), previews)
        scheduler.runPending()
        assertEquals(listOf(0), commits)
        // Subsequent UP still consumed without duplicate commit
        assertTrue(controller.onDispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN)))
        assertEquals(listOf(0), commits)

        // Clamp at 100% on volume up
        volume = 98
        previews.clear()
        commits.clear()
        assertTrue(controller.onDispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
        assertTrue(controller.onDispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP)))
        assertEquals(listOf(100), previews)
        assertEquals(listOf(100), commits)
    }

    @Test
    fun cancelDropsPendingDebouncedCommit() {
        val scheduler = FakeScheduler()
        val commits = mutableListOf<Int>()
        val controller = HardwareVolumeKeyController(
            scheduler = scheduler,
            shouldIntercept = { true },
            currentVolume = { 40 },
            onPreview = {},
            onCommit = { commits += it },
        )

        assertTrue(controller.onDispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
        controller.cancel()
        scheduler.runPending()
        assertTrue(commits.isEmpty())
    }
}
