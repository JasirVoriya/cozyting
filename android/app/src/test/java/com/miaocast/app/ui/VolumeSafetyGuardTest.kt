package com.miaocast.app.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.miaocast.app.CastService
import com.miaocast.app.MainActivity
import com.miaocast.app.R
import com.miaocast.app.SettingsActivity
import com.miaocast.app.cast.CastProtocol
import com.miaocast.app.cast.CastRouteSelection
import com.miaocast.app.cast.CastRouteSelection.Kind
import com.miaocast.app.cast.NowPlayingInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VolumeSafetyGuardTest {

    @Before
    fun setUp() {
        CastService.running.set(false)
        CastService.lastFailMessage = ""
        CastService.lastStats = ""
    }

    @After
    fun tearDown() {
        CastService.running.set(false)
        CastService.lastFailMessage = ""
        CastService.lastStats = ""
    }

    @Test
    fun purePolicyFollowsCrossingThresholdRule() {
        val policy = VolumeSafetyGuard.Policy(enabled = true, safeLimit = 60)

        // 安全区内自由调节
        assertEquals(
            VolumeSafetyGuard.Decision.Allow(60),
            VolumeSafetyGuard.evaluateSingle(policy, 30, 60),
        )
        // 从安全区跨过上限线 -> 必须弹窗确认
        assertEquals(
            VolumeSafetyGuard.Decision.RequireConfirm(30, 80, 60),
            VolumeSafetyGuard.evaluateSingle(policy, 30, 80),
        )
        // 已在高音量区内微调（升/降） -> 直接放行不重复弹
        assertEquals(
            VolumeSafetyGuard.Decision.Allow(90),
            VolumeSafetyGuard.evaluateSingle(policy, 80, 90),
        )
        assertEquals(
            VolumeSafetyGuard.Decision.Allow(70),
            VolumeSafetyGuard.evaluateSingle(policy, 90, 70),
        )
        // 降回安全区 -> 直接放行
        assertEquals(
            VolumeSafetyGuard.Decision.Allow(45),
            VolumeSafetyGuard.evaluateSingle(policy, 70, 45),
        )
        // 降回安全区后再次跨线 -> 重新确认
        assertEquals(
            VolumeSafetyGuard.Decision.RequireConfirm(45, 75, 60),
            VolumeSafetyGuard.evaluateSingle(policy, 45, 75),
        )
        // 关闭保护 -> 始终直接放行
        val disabled = VolumeSafetyGuard.Policy(enabled = false, safeLimit = 60)
        assertEquals(
            VolumeSafetyGuard.Decision.Allow(100),
            VolumeSafetyGuard.evaluateSingle(disabled, 10, 100),
        )
    }

    @Test
    fun groupPolicyRequiresConfirmWhenAnySelectedSpeakerCrossesLimit() {
        val policy = VolumeSafetyGuard.Policy(enabled = true, safeLimit = 60)
        // 音箱 A 已在 75%，音箱 B 在 30%，总音量从 75 调到 85 -> 音箱 B 会跨过 60%，应触发确认且回显总音量 75
        assertEquals(
            VolumeSafetyGuard.Decision.RequireConfirm(75, 85, 60),
            VolumeSafetyGuard.evaluateGroup(policy, masterCurrent = 75, speakerCurrents = listOf(75, 30), targetVolume = 85),
        )
        // 所有受影响音箱均已在高音量区 -> 不弹窗
        assertEquals(
            VolumeSafetyGuard.Decision.Allow(85),
            VolumeSafetyGuard.evaluateGroup(policy, masterCurrent = 75, speakerCurrents = listOf(75, 70), targetVolume = 85),
        )
    }

    @Test
    fun settingsStoreDefaultsAndClampsSafeLimit() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = PrefsCastSettingsStore(ctx)
        assertTrue(store.volumeGuardEnabled)
        assertEquals(60, store.volumeGuardSafeLimit)

        store.volumeGuardSafeLimit = 10
        assertEquals(30, store.volumeGuardSafeLimit)

        store.volumeGuardSafeLimit = 99
        assertEquals(90, store.volumeGuardSafeLimit)

        store.volumeGuardEnabled = false
        assertFalse(store.volumeGuardEnabled)
    }

    @Test
    fun mainActivityCrossingThresholdConfirmsThenAllowsInHighZoneAndRearmsAfterSafeDrop() {
        val activity = createCastingMainActivity(
            masterVol = 30,
            speakerVols = mapOf("192.0.2.10" to 30),
        )
        val shadowApp = shadowOf(RuntimeEnvironment.getApplication())
        shadowApp.clearStartedServices()

        // 1. 跨过安全上限 30 -> 80：拦截不发服务、先保持 30、弹确认框
        val d1 = activity.triggerMasterVolumeChangeForTest(80)
        assertNotNull("crossing safe limit must show confirm dialog", d1)
        assertTrue(d1!!.isShowing)
        assertEquals(30, activity.currentMasterVolumeForTest())
        assertEquals(30, activity.currentSpeakerVolumeForTest("192.0.2.10"))
        assertNull("must not send ACTION_VOLUME before confirmation", nextVolumeIntent(shadowApp))

        // 2. 点击「保持原音量（30%）」-> 关闭弹窗且音量保持 30
        findTextView(d1.window!!.decorView, activity.getString(R.string.volume_guard_keep, 30))!!.performClick()
        assertFalse(d1.isShowing)
        assertEquals(30, activity.currentMasterVolumeForTest())
        assertNull(nextVolumeIntent(shadowApp))

        // 3. 再次 30 -> 80 并点击「确认调至 80%」-> 提交 80 并带 confirmed=true 发给服务
        val d2 = activity.triggerMasterVolumeChangeForTest(80)
        assertNotNull(d2)
        findTextView(d2!!.window!!.decorView, activity.getString(R.string.volume_guard_confirm, 80))!!.performClick()
        assertEquals(80, activity.currentMasterVolumeForTest())
        assertEquals(80, activity.currentSpeakerVolumeForTest("192.0.2.10"))
        val sentConfirmed = nextVolumeIntent(shadowApp)
        assertNotNull(sentConfirmed)
        assertEquals(80, sentConfirmed!!.getIntExtra(CastService.EXTRA_VOLUME, -1))
        assertTrue(sentConfirmed.getBooleanExtra(CastService.EXTRA_VOLUME_CONFIRMED, false))

        // 4. 已在高音量区内微调 80 -> 90 和 90 -> 70 -> 不弹窗，直接生效
        assertNull(activity.triggerMasterVolumeChangeForTest(90))
        assertEquals(90, activity.currentMasterVolumeForTest())
        val sent90 = nextVolumeIntent(shadowApp)
        assertEquals(90, sent90!!.getIntExtra(CastService.EXTRA_VOLUME, -1))

        assertNull(activity.triggerMasterVolumeChangeForTest(70))
        assertEquals(70, activity.currentMasterVolumeForTest())
        assertEquals(70, nextVolumeIntent(shadowApp)!!.getIntExtra(CastService.EXTRA_VOLUME, -1))

        // 5. 降回安全区 70 -> 45 -> 不弹窗，直接生效
        assertNull(activity.triggerMasterVolumeChangeForTest(45))
        assertEquals(45, activity.currentMasterVolumeForTest())
        assertEquals(45, nextVolumeIntent(shadowApp)!!.getIntExtra(CastService.EXTRA_VOLUME, -1))

        // 6. 降回安全区后再拉过上限 45 -> 75 -> 重新弹出二次确认
        val d3 = activity.triggerMasterVolumeChangeForTest(75)
        assertNotNull("must re-arm confirmation after dropping back to safe zone", d3)
        assertEquals(45, activity.currentMasterVolumeForTest())
        assertNull(nextVolumeIntent(shadowApp))
        d3!!.dismiss()
    }

    @Test
    fun mainActivitySingleSpeakerAndMultiSpeakerCancelRestoreExactPreviousVolumes() {
        val activity = createCastingMainActivity(
            masterVol = 75,
            speakerVols = mapOf("192.0.2.10" to 75, "192.0.2.11" to 30),
        )
        val shadowApp = shadowOf(RuntimeEnvironment.getApplication())
        shadowApp.clearStartedServices()

        // 总音量拉到 85：因 192.0.2.11 仍在 30%，应弹窗确认；点取消后两台各自保持 75 / 30
        val groupDialog = activity.triggerMasterVolumeChangeForTest(85)
        assertNotNull(groupDialog)
        findTextView(groupDialog!!.window!!.decorView, activity.getString(R.string.volume_guard_keep, 75))!!.performClick()
        assertEquals(75, activity.currentMasterVolumeForTest())
        assertEquals(75, activity.currentSpeakerVolumeForTest("192.0.2.10"))
        assertEquals(30, activity.currentSpeakerVolumeForTest("192.0.2.11"))
        assertNull(nextVolumeIntent(shadowApp))

        // 单音箱 192.0.2.11 从 30 -> 82 弹窗，确认后仅针对该 IP 下发 confirmed=true
        val singleDialog = activity.triggerSpeakerVolumeChangeForTest("192.0.2.11", "卧室音箱", 82)
        assertNotNull(singleDialog)
        findTextView(singleDialog!!.window!!.decorView, activity.getString(R.string.volume_guard_confirm, 82))!!.performClick()
        assertEquals(82, activity.currentSpeakerVolumeForTest("192.0.2.11"))
        val sentSingle = nextVolumeIntent(shadowApp)
        assertNotNull(sentSingle)
        assertEquals("192.0.2.11", sentSingle!!.getStringExtra(CastService.EXTRA_IP))
        assertEquals(82, sentSingle.getIntExtra(CastService.EXTRA_VOLUME, -1))
        assertTrue(sentSingle.getBooleanExtra(CastService.EXTRA_VOLUME_CONFIRMED, false))
    }

    @Test
    fun settingsActivityDisplaysVolumeGuardControls() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.window.decorView
        assertNotNull(findTextView(root, activity.getString(R.string.settings_volume_guard_title)))
        assertNotNull(findTextView(root, activity.getString(R.string.settings_volume_limit_title)))
        assertNotNull(findTextView(root, "60%"))
    }

    private fun createCastingMainActivity(
        masterVol: Int,
        speakerVols: Map<String, Int>,
    ): MainActivity {
        val intent = Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java)
            .putExtra("ui_preview", true)
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()
        val devices = listOf(
            CastRouteSelection.Device("this", "本机播放", Kind.THIS_DEVICE, true),
        ) + speakerVols.keys.mapIndexed { idx, ip ->
            CastRouteSelection.Device(
                id = ip,
                name = if (idx == 0) "客厅音箱" else "卧室音箱",
                kind = Kind.SPEAKER,
                castable = true,
                protocol = CastProtocol.MIPLAY,
            )
        }
        activity.applyUiPreviewStateForTest(
            isDark = true,
            previewNowPlaying = NowPlayingInfo.unavailable("preview"),
            previewDevices = devices,
            previewSelectedSpeakerIds = speakerVols.keys,
            previewMasterVolume = masterVol,
            previewSpeakerVolumes = speakerVols,
            previewCasting = true,
        )
        return activity
    }

    private fun nextVolumeIntent(shadowApp: org.robolectric.shadows.ShadowApplication): Intent? {
        while (true) {
            val next = shadowApp.nextStartedService ?: return null
            if (next.action == CastService.ACTION_VOLUME) return next
        }
    }

    private fun findTextView(root: View, exactText: String): TextView? {
        if (root is TextView && root.text?.toString() == exactText) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findTextView(root.getChildAt(i), exactText)
                if (found != null) return found
            }
        }
        return null
    }
}
