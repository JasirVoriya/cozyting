package com.miplay.sender.capture.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DualZoneAudioFocusRouterTest {
    private val musicUid = 10379
    private val videoAppAUid = 10418
    private val videoAppBUid = 10520

    @Test
    fun scrollingVideosInLocalZoneNeverPausesCastMusicAndStillReplacesPreviousVideo() {
        val router = DualZoneAudioFocusRouter(castTargetUid = musicUid)

        // 1. 音箱侧音乐 App 获取焦点
        val a1 = router.onFocusRequest(
            DualZoneAudioFocusRouter.FocusRequest(
                clientId = "music-1",
                uid = musicUid,
                packageName = "com.example.music",
                gainRequest = DualZoneAudioFocusRouter.AUDIOFOCUS_GAIN,
            ),
        )
        assertEquals(listOf(DualZoneAudioFocusRouter.Action.Grant("music-1")), a1)

        // 2. 手机刷第 1 个短视频：授予本机区焦点，绝不向 music-1 下发 LOSS
        val a2 = router.onFocusRequest(
            DualZoneAudioFocusRouter.FocusRequest(
                clientId = "video-1",
                uid = videoAppAUid,
                packageName = "com.example.video",
                gainRequest = DualZoneAudioFocusRouter.AUDIOFOCUS_GAIN,
            ),
        )
        assertEquals(listOf(DualZoneAudioFocusRouter.Action.Grant("video-1")), a2)

        // 3. 手机刷到第 2 个短视频：本机区内正常替换 video-1，但 music-1 依然零中断
        val a3 = router.onFocusRequest(
            DualZoneAudioFocusRouter.FocusRequest(
                clientId = "video-2",
                uid = videoAppAUid,
                packageName = "com.example.video",
                gainRequest = DualZoneAudioFocusRouter.AUDIOFOCUS_GAIN,
            ),
        )
        assertEquals(
            listOf(
                DualZoneAudioFocusRouter.Action.Grant("video-2"),
                DualZoneAudioFocusRouter.Action.DispatchChange("video-1", DualZoneAudioFocusRouter.AUDIOFOCUS_LOSS),
            ),
            a3,
        )
        assertEquals(1, router.castZoneSize())
        assertEquals(1, router.localZoneSize())
    }

    @Test
    fun resumingCastMusicNeverInterruptsOrBrieflyMutesLocalVideo() {
        val router = DualZoneAudioFocusRouter(castTargetUid = musicUid)

        router.onFocusRequest(
            DualZoneAudioFocusRouter.FocusRequest("video-1", videoAppAUid, "com.example.video", DualZoneAudioFocusRouter.AUDIOFOCUS_GAIN),
        )

        // 音乐 App 切歌或手动点击播放重新申请 AUDIOFOCUS_GAIN：绝不向本机视频派发 LOSS
        val actions = router.onFocusRequest(
            DualZoneAudioFocusRouter.FocusRequest("music-2", musicUid, "com.example.music", DualZoneAudioFocusRouter.AUDIOFOCUS_GAIN),
        )
        assertEquals(listOf(DualZoneAudioFocusRouter.Action.Grant("music-2")), actions)
    }

    @Test
    fun transientFocusInLocalZonePausesAndRegainsLocalVideoWithoutTouchingCastMusic() {
        val router = DualZoneAudioFocusRouter(castTargetUid = musicUid)

        router.onFocusRequest(
            DualZoneAudioFocusRouter.FocusRequest("music-1", musicUid, "com.example.music", DualZoneAudioFocusRouter.AUDIOFOCUS_GAIN),
        )
        router.onFocusRequest(
            DualZoneAudioFocusRouter.FocusRequest("video-1", videoAppAUid, "com.example.video", DualZoneAudioFocusRouter.AUDIOFOCUS_GAIN),
        )

        // 本机区另一个 App 请求临时闪避焦点（如导航语音）：仅压低本机视频，不影响音箱音乐
        val navReq = router.onFocusRequest(
            DualZoneAudioFocusRouter.FocusRequest(
                clientId = "nav-1",
                uid = videoAppBUid,
                packageName = "com.example.nav",
                gainRequest = DualZoneAudioFocusRouter.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            ),
        )
        assertEquals(
            listOf(
                DualZoneAudioFocusRouter.Action.Grant("nav-1"),
                DualZoneAudioFocusRouter.Action.DispatchChange("video-1", DualZoneAudioFocusRouter.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK),
            ),
            navReq,
        )

        // 导航播报结束放弃焦点：仅恢复本机视频，不重复打扰音箱音乐
        val navDone = router.onFocusAbandon("nav-1", videoAppBUid)
        assertEquals(
            listOf(DualZoneAudioFocusRouter.Action.DispatchChange("video-1", DualZoneAudioFocusRouter.AUDIOFOCUS_GAIN)),
            navDone,
        )
    }

    @Test
    fun cellularPhoneCallInterruptsBothZonesTransientlyAndRestoresBothOnHangup() {
        val router = DualZoneAudioFocusRouter(castTargetUid = musicUid)

        router.onFocusRequest(
            DualZoneAudioFocusRouter.FocusRequest("music-1", musicUid, "com.example.music", DualZoneAudioFocusRouter.AUDIOFOCUS_GAIN),
        )
        router.onFocusRequest(
            DualZoneAudioFocusRouter.FocusRequest("video-1", videoAppAUid, "com.example.video", DualZoneAudioFocusRouter.AUDIOFOCUS_GAIN),
        )

        val callStart = router.onFocusRequest(
            DualZoneAudioFocusRouter.FocusRequest(
                clientId = DualZoneAudioFocusRouter.PHONE_CALL_CLIENT_ID,
                uid = 1000,
                packageName = "android",
                gainRequest = DualZoneAudioFocusRouter.AUDIOFOCUS_GAIN_TRANSIENT,
            ),
        )
        assertTrue(callStart.contains(DualZoneAudioFocusRouter.Action.Grant(DualZoneAudioFocusRouter.PHONE_CALL_CLIENT_ID)))
        assertTrue(callStart.contains(DualZoneAudioFocusRouter.Action.DispatchChange("video-1", DualZoneAudioFocusRouter.AUDIOFOCUS_LOSS_TRANSIENT)))
        assertTrue(callStart.contains(DualZoneAudioFocusRouter.Action.DispatchChange("music-1", DualZoneAudioFocusRouter.AUDIOFOCUS_LOSS_TRANSIENT)))

        val callEnd = router.onFocusAbandon(DualZoneAudioFocusRouter.PHONE_CALL_CLIENT_ID, 1000)
        assertTrue(callEnd.contains(DualZoneAudioFocusRouter.Action.DispatchChange("video-1", DualZoneAudioFocusRouter.AUDIOFOCUS_GAIN)))
        assertTrue(callEnd.contains(DualZoneAudioFocusRouter.Action.DispatchChange("music-1", DualZoneAudioFocusRouter.AUDIOFOCUS_GAIN)))
    }
}
