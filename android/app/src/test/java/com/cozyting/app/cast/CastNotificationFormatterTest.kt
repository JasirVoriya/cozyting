package com.cozyting.app.cast

import com.cozyting.app.capture.CaptureScopeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastNotificationFormatterTest {

    private val names = mapOf(
        "192.0.2.10" to "客厅音箱",
        "192.0.2.11" to "卧室音箱",
        "192.0.2.12" to "书房音箱",
        "192.0.2.13" to "餐厅音箱",
        "192.0.2.14" to "阳台音箱",
        "192.0.2.15" to "次卧音箱",
    )

    @Test
    fun singleSpeakerPlaying_showsAppSourceAndSpeakerNameWithoutDebugJargon() {
        val stats = """{"receivers":[{"ip":"192.0.2.10","ui_state":"streaming","state":2}]}"""
        val copy = CastNotificationFormatter.format(
            source = "app",
            scopeMode = CaptureScopeMode.SINGLE_APP,
            targetLabel = "示例音乐",
            targetPackage = "com.example.music",
            paused = false,
            statsJson = stats,
            deviceNamesByIp = names,
        )

        assertEquals("正在投送 · 示例音乐", copy.title)
        assertEquals("客厅音箱 · 点击打开控制卡", copy.collapsedText)
        assertNull(copy.expandedText)
        assertFalse(copy.collapsedText.contains("E2E"))
        assertFalse(copy.collapsedText.contains("配置"))
    }

    @Test
    fun twoSpeakersPlaying_usesScheme2PureCountAndExpandedList() {
        val stats = """
            {"receivers":[
                {"ip":"192.0.2.10","ui_state":"streaming","state":2},
                {"ip":"192.0.2.11","ui_state":"streaming","state":2}
            ]}
        """.trimIndent()

        val copy = CastNotificationFormatter.format(
            source = "app",
            scopeMode = CaptureScopeMode.SINGLE_APP,
            targetLabel = "示例音乐",
            targetPackage = "com.example.music",
            paused = false,
            statsJson = stats,
            deviceNamesByIp = names,
        )

        assertEquals("正在投送 · 示例音乐", copy.title)
        assertEquals("2 台设备同步播放 · 点击打开控制卡", copy.collapsedText)
        assertEquals("• 客厅音箱 · 播放中\n• 卧室音箱 · 播放中", copy.expandedText)
    }

    @Test
    fun manySpeakersWithReconnectingAndDisconnected_prioritizesAbnormalAndCapsAtFive() {
        val stats = """
            {"receivers":[
                {"ip":"192.0.2.10","ui_state":"streaming","state":2},
                {"ip":"192.0.2.11","ui_state":"streaming","state":2},
                {"ip":"192.0.2.12","ui_state":"reconnecting","state":0},
                {"ip":"192.0.2.13","ui_state":"streaming","state":2},
                {"ip":"192.0.2.14","ui_state":"error","error":"timeout"},
                {"ip":"192.0.2.15","ui_state":"streaming","state":2}
            ]}
        """.trimIndent()

        val copy = CastNotificationFormatter.format(
            source = "app",
            scopeMode = CaptureScopeMode.SINGLE_APP,
            targetLabel = "示例音乐",
            targetPackage = "com.example.music",
            paused = false,
            statsJson = stats,
            deviceNamesByIp = names,
        )

        assertEquals("正在投送 · 示例音乐", copy.title)
        assertEquals("6 台设备 · 1 台重连中 · 1 台已断开", copy.collapsedText)
        val expanded = copy.expandedText
        assertNotNull(expanded)
        val lines = expanded!!.lines()
        assertEquals(6, lines.size) // 5 设备 + 1 折叠汇总行
        assertEquals("• 阳台音箱 · 已断开", lines[0])
        assertEquals("• 书房音箱 · 重连中", lines[1])
        assertEquals("…等共 6 台设备", lines.last())
    }

    @Test
    fun allConnectingAndFallbackIps_beforeFirstStats() {
        val copy = CastNotificationFormatter.format(
            source = "app",
            scopeMode = CaptureScopeMode.SINGLE_APP,
            targetLabel = "示例音乐",
            targetPackage = "com.example.music",
            paused = false,
            statsJson = "",
            fallbackIps = listOf("192.0.2.10", "192.0.2.11"),
            deviceNamesByIp = names,
        )

        assertEquals("正在连接 · 示例音乐", copy.title)
        assertEquals("正在连接 2 台设备 · 点击打开控制卡", copy.collapsedText)
        assertTrue(copy.expandedText!!.contains("• 客厅音箱 · 连接中"))
    }

    @Test
    fun globalMediaAndPausedAndSilentModes() {
        val stats = """
            {"receivers":[
                {"ip":"192.0.2.10","ui_state":"streaming","state":2},
                {"ip":"192.0.2.11","ui_state":"streaming","state":2}
            ]}
        """.trimIndent()

        val globalCopy = CastNotificationFormatter.format(
            source = "app",
            scopeMode = CaptureScopeMode.GLOBAL_MEDIA,
            targetLabel = "",
            targetPackage = "",
            paused = false,
            statsJson = stats,
            deviceNamesByIp = names,
        )
        assertEquals("正在投送 · 全局媒体", globalCopy.title)
        assertEquals("2 台设备同步播放 · 点击打开控制卡", globalCopy.collapsedText)

        val pausedCopy = CastNotificationFormatter.format(
            source = "app",
            scopeMode = CaptureScopeMode.SINGLE_APP,
            targetLabel = "示例音乐",
            targetPackage = "com.example.music",
            paused = true,
            statsJson = stats,
            deviceNamesByIp = names,
        )
        assertEquals("已暂停投送 · 示例音乐", pausedCopy.title)
        assertEquals("2 台设备 · 点击打开控制卡", pausedCopy.collapsedText)

        val silentCopy = CastNotificationFormatter.format(
            source = "silent",
            scopeMode = CaptureScopeMode.SINGLE_APP,
            targetLabel = "",
            targetPackage = "",
            paused = false,
            statsJson = """{"receivers":[{"ip":"192.0.2.99","ui_state":"streaming","state":2}]}""",
            deviceNamesByIp = names,
        )
        assertEquals("静音测试中", silentCopy.title)
        // 无缓存名时平滑回退为完整 IP，不展示裸末段
        assertEquals("192.0.2.99 · 点击打开控制卡", silentCopy.collapsedText)
    }
}
