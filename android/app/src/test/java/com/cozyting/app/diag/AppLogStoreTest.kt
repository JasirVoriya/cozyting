package com.cozyting.app.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppLogStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun appendRotatesRuntimeLogAndMergesWithCoreLogChronologically() {
        val dir = tempFolder.newFolder("files")
        val store = AppLogStore(dir, maxRuntimeBytes = 80L)

        store.append('I', "CozyTing", "first kotlin message with padding 012345678901234567890123456789", nowMs = 1_700_000_001_000L)
        store.append('W', "CozyTing", "second kotlin warn", nowMs = 1_700_000_003_000L)
        store.flush()

        assertTrue(store.runtimeLogFile.exists())
        assertTrue(store.runtimeBackupFile.exists())

        // 模拟 Rust 核心层按相同本地时间戳格式写入 core.log（时间位于两条 Kotlin 日志之间）
        val tsMid = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(1_700_000_002_000L))
        store.coreLogFile.writeText("$tsMid I/CozyTingCore: middle core event\n", Charsets.UTF_8)

        val merged = store.readMergedRuntimeLines()
        assertEquals(3, merged.size)
        assertTrue(merged[0].contains("first kotlin message"))
        assertTrue(merged[1].contains("I/CozyTingCore: middle core event"))
        assertTrue(merged[2].contains("W/CozyTing: second kotlin warn"))

        val warnOnly = store.readFilteredLines(AppLogStore.Filter.WARN_ERROR)
        assertEquals(1, warnOnly.size)
        assertTrue(warnOnly[0].contains("second kotlin warn"))

        val coreOnly = store.readFilteredLines(AppLogStore.Filter.CORE)
        assertEquals(1, coreOnly.size)
        assertTrue(coreOnly[0].contains("CozyTingCore"))
    }

    @Test
    fun exportIncludesHeaderRuntimeAndOpsAndClearRemovesAll() {
        val filesDir = tempFolder.newFolder("files2")
        val cacheDir = tempFolder.newFolder("cache2")
        val store = AppLogStore(filesDir)

        store.append('I', "CozyTing", "startup ok", nowMs = 1_700_000_010_000L)
        store.flush()
        store.opsLogFile.writeText("12:00:00.000 开始投送\n", Charsets.UTF_8)

        assertTrue(store.totalSizeBytes() > 0L)
        assertEquals(1, store.readFilteredLines(AppLogStore.Filter.OPS).size)

        val exported = store.writeExportFile(cacheDir, "=== Header ===\napp_version: 0.9.9-rc.1", nowMs = 1_700_000_020_000L)
        assertTrue(exported.exists())
        val content = exported.readText(Charsets.UTF_8)
        assertTrue(content.contains("app_version: 0.9.9-rc.1"))
        assertTrue(content.contains("startup ok"))
        assertTrue(content.contains("开始投送"))

        // 再次导出应清理旧的临时快照，避免缓存堆积
        val exported2 = store.writeExportFile(cacheDir, "=== Header 2 ===", nowMs = 1_700_000_025_000L)
        val remaining = File(cacheDir, "logs").listFiles()?.map { it.name }.orEmpty()
        assertEquals(listOf(exported2.name), remaining)

        var coreCleared = false
        store.clearAll(onClearCore = { coreCleared = true })
        assertTrue(coreCleared)
        assertEquals(0L, store.totalSizeBytes())
        assertFalse(store.runtimeLogFile.exists())
        assertFalse(store.opsLogFile.exists())
    }

    @Test
    fun formatSizeFormatsBytesKbAndMb() {
        assertEquals("0 B", AppLogStore.formatSize(0L))
        assertEquals("512 B", AppLogStore.formatSize(512L))
        assertEquals("1.5 KB", AppLogStore.formatSize(1536L))
        assertEquals("1.00 MB", AppLogStore.formatSize(1024L * 1024L))
    }
}
