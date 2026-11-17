package com.miplay.sender.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只持久化包名，不永久保存 UID。 */
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
    }
}
