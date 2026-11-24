package com.cozyting.app.diag

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 本地运行日志异步轮转存储与导出工具（纯本地沙箱，绝不自动上传）。
 *
 * 磁盘文件布局（全部位于 `filesDir`）：
 * - `runtime.log` / `runtime.log.1`：Kotlin 侧全量业务日志（单文件 256 KB，总上限 512 KB）
 * - `core.log` / `core.log.1`：Rust `CozyTingCore` 核心日志（单文件 256 KB，总上限 512 KB）
 * - `cast_ops.log` / `cast_ops.log.1`：投送操作流水（单文件 64 KB，总上限 128 KB）
 */
class AppLogStore(
    private val filesDir: File,
    private val maxRuntimeBytes: Long = DEFAULT_MAX_RUNTIME_BYTES,
) {
    enum class Filter {
        ALL,
        WARN_ERROR,
        CORE,
        OPS,
    }

    companion object {
        const val DEFAULT_MAX_RUNTIME_BYTES: Long = 256 * 1024L
        const val DEFAULT_MAX_CORE_BYTES: Long = 256 * 1024L
        const val RUNTIME_FILE_NAME = "runtime.log"
        const val CORE_FILE_NAME = "core.log"
        const val OPS_FILE_NAME = "cast_ops.log"
        private const val DEFAULT_MAX_DISPLAY_LINES = 1500
        private val TIMESTAMP_REGEX = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\b""")

        fun formatSize(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            if (bytes < 1024L) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
            return String.format(Locale.US, "%.2f MB", kb / 1024.0)
        }
    }

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cozyting-applog").apply { isDaemon = true }
    }

    val runtimeLogFile: File get() = File(filesDir, RUNTIME_FILE_NAME)
    val runtimeBackupFile: File get() = File(filesDir, "$RUNTIME_FILE_NAME.1")
    val coreLogFile: File get() = File(filesDir, CORE_FILE_NAME)
    val coreBackupFile: File get() = File(filesDir, "$CORE_FILE_NAME.1")
    val opsLogFile: File get() = File(filesDir, OPS_FILE_NAME)
    val opsBackupFile: File get() = File(filesDir, "$OPS_FILE_NAME.1")

    fun append(level: Char, tag: String, message: String, nowMs: Long = System.currentTimeMillis()) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(nowMs))
        val singleLine = message.replace('\r', ' ').replace('\n', ' ')
        val entry = "$ts $level/$tag: $singleLine"
        runCatching {
            writer.execute {
                writeRotatingLine(runtimeLogFile, runtimeBackupFile, maxRuntimeBytes, entry)
            }
        }
    }

    /** 等待当前队列中的所有异步写入落盘（单测与清空前同步用）。 */
    fun flush(timeoutMs: Long = 1000L) {
        runCatching {
            writer.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    fun totalSizeBytes(): Long = listOf(
        runtimeLogFile,
        runtimeBackupFile,
        coreLogFile,
        coreBackupFile,
        opsLogFile,
        opsBackupFile,
    ).sumOf { if (it.exists()) it.length() else 0L }

    fun readMergedRuntimeLines(maxLines: Int = DEFAULT_MAX_DISPLAY_LINES): List<String> {
        flush(300L)
        val kotlinLines = readFileLines(runtimeBackupFile) + readFileLines(runtimeLogFile)
        val coreLines = readFileLines(coreBackupFile) + readFileLines(coreLogFile)
        if (kotlinLines.isEmpty() && coreLines.isEmpty()) return emptyList()

        val combined = ArrayList<Pair<String?, String>>(kotlinLines.size + coreLines.size)
        kotlinLines.forEach { combined.add(extractTimestampKey(it) to it) }
        coreLines.forEach { combined.add(extractTimestampKey(it) to it) }

        // 按 yyyy-MM-dd HH:mm:ss.SSS 字典序稳定合并，保持 Kotlin 与 Rust 日志时间线一致
        combined.sortWith(compareBy<Pair<String?, String>> { it.first ?: "\uFFFF" })
        val all = combined.map { it.second }
        return if (all.size > maxLines) all.takeLast(maxLines) else all
    }

    fun readCastOpsLines(maxLines: Int = 500): List<String> {
        val lines = readFileLines(opsBackupFile) + readFileLines(opsLogFile)
        return if (lines.size > maxLines) lines.takeLast(maxLines) else lines
    }

    fun readFilteredLines(filter: Filter, maxLines: Int = DEFAULT_MAX_DISPLAY_LINES): List<String> {
        return when (filter) {
            Filter.ALL -> readMergedRuntimeLines(maxLines)
            Filter.WARN_ERROR -> readMergedRuntimeLines(maxLines * 2)
                .filter { line ->
                    line.contains(" W/") || line.contains(" E/")
                }
                .takeLast(maxLines)
            Filter.CORE -> readMergedRuntimeLines(maxLines * 2)
                .filter { line -> line.contains("/CozyTingCore:") }
                .takeLast(maxLines)
            Filter.OPS -> readCastOpsLines(maxLines)
        }
    }

    fun clearAll(onClearCore: () -> Unit = {}) {
        flush(500L)
        runCatching { onClearCore() }
        runCatching {
            writer.submit {
                listOf(
                    runtimeLogFile,
                    runtimeBackupFile,
                    coreLogFile,
                    coreBackupFile,
                    opsLogFile,
                    opsBackupFile,
                ).forEach { runCatching { if (it.exists()) it.delete() } }
            }.get(1000L, TimeUnit.MILLISECONDS)
        }
    }

    fun buildExportText(header: String): String {
        val runtime = readMergedRuntimeLines(maxLines = 4000)
        val ops = readCastOpsLines(maxLines = 1000)
        return buildString {
            appendLine(header.trimEnd())
            appendLine()
            appendLine("=== Runtime & Core Logs (${runtime.size} lines) ===")
            if (runtime.isEmpty()) {
                appendLine("(empty)")
            } else {
                runtime.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("=== Cast Operations (cast_ops.log, ${ops.size} lines) ===")
            if (ops.isEmpty()) {
                appendLine("(empty)")
            } else {
                ops.forEach { appendLine(it) }
            }
        }
    }

    fun writeExportFile(cacheDir: File, header: String, nowMs: Long = System.currentTimeMillis()): File {
        val exportDir = File(cacheDir, "logs").apply { mkdirs() }
        // 清理之前的临时导出文件，避免缓存堆积
        exportDir.listFiles()?.forEach { old ->
            if (old.isFile && old.name.startsWith("cozyting-logs-")) {
                runCatching { old.delete() }
            }
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
        val target = File(exportDir, "cozyting-logs-$stamp.txt")
        target.writeText(buildExportText(header), Charsets.UTF_8)
        return target
    }

    private fun extractTimestampKey(line: String): String? {
        val m = TIMESTAMP_REGEX.find(line) ?: return null
        return m.value
    }

    private fun readFileLines(file: File): List<String> {
        if (!file.exists() || !file.isFile) return emptyList()
        return runCatching {
            file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun writeRotatingLine(file: File, backup: File, maxBytes: Long, line: String) {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() >= maxBytes) {
                file.copyTo(backup, overwrite = true)
                file.writeText("", Charsets.UTF_8)
            }
            file.appendText("$line\n", Charsets.UTF_8)
        }
    }
}
