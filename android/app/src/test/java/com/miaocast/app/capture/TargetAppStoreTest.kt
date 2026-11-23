package com.miaocast.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只持久化包名与最近使用历史，不永久保存 UID。 */
class TargetAppStoreTest {
    @Test
    fun persistsPackageNameOnly_notUid() {
        val mem = InMemoryTargetAppStore()
        mem.saveSelection(
            TargetAppSelection(
                packageName = "com.example.player",
                label = "示例播放器",
                signingCertSha256 = "abc",
            ),
        )
        val raw = mem.debugRawEntries()
        assertTrue(raw.keys.none { it.contains("uid", ignoreCase = true) })
        assertEquals("com.example.player", mem.loadSelection()?.packageName)
        assertNull(mem.loadUid()) // API 明确不提供持久 UID
    }

    @Test
    fun recentSelections_maintainsMruOrderAndDeduplicates() {
        val mem = InMemoryTargetAppStore()
        val qq = TargetAppSelection("com.tencent.qqmusic", "QQ音乐", "c1")
        val netease = TargetAppSelection("com.netease.cloudmusic", "网易云音乐", "c2")
        val huawei = TargetAppSelection("com.huawei.music", "华为音乐", "c3")
        mem.saveSelection(qq)
        mem.saveSelection(netease)
        mem.saveSelection(huawei)
        mem.saveSelection(qq)
        assertEquals(
            listOf("com.tencent.qqmusic", "com.huawei.music", "com.netease.cloudmusic"),
            mem.loadRecentSelections(4).map { it.packageName },
        )
    }

    @Test
    fun clearSelection_removesPackage() {
        val mem = InMemoryTargetAppStore()
        mem.saveSelection(
            TargetAppSelection(
                packageName = "com.example.player",
                label = "示例播放器",
                signingCertSha256 = "abc",
            ),
        )
        mem.clearSelection()
        assertNull(mem.loadSelection())
        assertTrue(mem.loadRecentSelections().isEmpty())
    }
}
