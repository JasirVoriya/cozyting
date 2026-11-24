package com.cozyting.app.diag

import android.content.Context
import android.util.Log
import com.cozyting.app.NativeBridge
import java.io.File
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内统一日志入口：同时输出到 Android Logcat 与本地异步轮转文件（`AppLogStore`），
 * 并在初始化时将 `core.log` 路径同步给 Rust 核心层（`CozyTingCore`）。
 */
object AppLog {
    private val coreInitialized = AtomicBoolean(false)
    @Volatile
    private var storeRef: AppLogStore? = null

    fun init(context: Context): AppLogStore {
        val appCtx = context.applicationContext ?: context
        val existing = storeRef
        val store = if (existing != null) {
            existing
        } else {
            synchronized(this) {
                storeRef ?: AppLogStore(appCtx.filesDir).also { storeRef = it }
            }
        }
        if (coreInitialized.compareAndSet(false, true)) {
            val tzOffsetSecs = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
            runCatching {
                NativeBridge.initLogFile(
                    store.coreLogFile.absolutePath,
                    AppLogStore.DEFAULT_MAX_CORE_BYTES,
                    tzOffsetSecs,
                )
            }.onFailure {
                // 单测或非原生环境允许静默降级
                coreInitialized.set(false)
            }
        }
        return store
    }

    fun storeOrNull(): AppLogStore? = storeRef

    fun i(tag: String, msg: String) {
        runCatching { Log.i(tag, msg) }
        storeRef?.append('I', tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        runCatching { if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg) }
        val full = if (t != null) "$msg (${t.javaClass.simpleName}: ${t.message})" else msg
        storeRef?.append('W', tag, full)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        runCatching { if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg) }
        val full = if (t != null) "$msg (${t.javaClass.simpleName}: ${t.message})" else msg
        storeRef?.append('E', tag, full)
    }

    fun clearAll(context: Context) {
        val store = init(context)
        store.clearAll(onClearCore = {
            runCatching { NativeBridge.clearCoreLogs() }
        })
    }

    fun totalSizeBytes(context: Context): Long = init(context).totalSizeBytes()

    fun exportFile(context: Context, header: String): File {
        val appCtx = context.applicationContext ?: context
        val store = init(appCtx)
        return store.writeExportFile(appCtx.cacheDir, header)
    }
}
