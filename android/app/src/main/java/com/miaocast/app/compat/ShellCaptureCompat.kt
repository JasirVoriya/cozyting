package com.miaocast.app.compat

import android.app.PendingIntent
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioRecord
import android.net.ssl.SSLSockets
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import javax.net.ssl.SSLSocket

/**
 * 隔离 Android 11+ (API 30) / Android 12+ (API 31) / Android 13+ (API 33) 的内置无线调试与无感采集 API，
 * 保证 `minSdk = 29` 下 Android Lint `NewApi`（fatal）零告警。
 */
object ShellCaptureCompat {
    fun supportsWirelessDebug(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun supportsSingleAppAudioPolicy(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * 导出 TLS 1.3 Keying Material（RFC 5705），供 AOSP 无线调试 SPAKE2 配对绑定会话密钥。
     * - API 31+：调用公开 API `android.net.ssl.SSLSockets.exportKeyingMaterial`；
     * - API 30：反射系统内置 `com.android.org.conscrypt.Conscrypt.exportKeyingMaterial`。
     *
     * 注意：AOSP `tls_connection.cpp` 传入的 label 为 `"adb-label\u0000"`（含末尾 NUL，共 10 字节）。
     */
    fun exportTlsKeyingMaterial(
        socket: SSLSocket,
        label: String,
        context: ByteArray?,
        length: Int,
    ): ByteArray {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return exportKeyingMaterialApi31(socket, label, context, length)
                ?: error("SSLSockets.exportKeyingMaterial 返回 null")
        }
        return exportKeyingMaterialViaConscrypt(socket, label, context, length)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun exportKeyingMaterialApi31(
        socket: SSLSocket,
        label: String,
        context: ByteArray?,
        length: Int,
    ): ByteArray? = SSLSockets.exportKeyingMaterial(socket, label, context, length)

    private fun exportKeyingMaterialViaConscrypt(
        socket: SSLSocket,
        label: String,
        context: ByteArray?,
        length: Int,
    ): ByteArray {
        val candidates = listOf(
            "com.android.org.conscrypt.Conscrypt",
            "org.conscrypt.Conscrypt",
        )
        var lastError: Throwable? = null
        for (clsName in candidates) {
            try {
                val cls = Class.forName(clsName)
                val method = cls.getMethod(
                    "exportKeyingMaterial",
                    SSLSocket::class.java,
                    String::class.java,
                    ByteArray::class.java,
                    Int::class.javaPrimitiveType,
                )
                val out = method.invoke(null, socket, label, context, length) as? ByteArray
                if (out != null && out.size == length) return out
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IllegalStateException("无法从 TLS 套接字导出 Keying Material", lastError)
    }

    /** RemoteInput 通知回复需要可变 PendingIntent（API 31+ 必须显式指定 FLAG_MUTABLE）。 */
    fun mutablePendingIntentFlags(baseFlags: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            baseFlags or PendingIntent.FLAG_MUTABLE
        } else {
            baseFlags
        }

    fun immutablePendingIntentFlags(baseFlags: Int): Int =
        baseFlags or PendingIntent.FLAG_IMMUTABLE

    /**
     * 在 `app_process`（`uid=2000`）中构造归属包名为 `com.android.shell` 的 Context。
     *
     * Android 13–17 上 `ActivityThread.systemMain().getSystemContext()` 默认 `opPackageName = "android"`
     * （对应 `uid=1000`），直接传给 `AudioRecord` 会被 `AudioFlinger` 以 `EX_SECURITY (invalid attr uid=2000 packageName=android)` 拒绝。
     */
    fun wrapAsShellContext(systemContext: Context): Context {
        val base = runCatching {
            systemContext.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
        }.getOrDefault(systemContext)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wrapAsShellContextApi31(base)
        } else {
            object : ContextWrapper(base) {
                override fun getPackageName(): String = "com.android.shell"
                override fun getOpPackageName(): String = "com.android.shell"
                override fun getApplicationContext(): Context = this
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun wrapAsShellContextApi31(base: Context): Context {
        val shellAttr = AttributionSource.Builder(Process.myUid())
            .setPackageName("com.android.shell")
            .build()
        return object : ContextWrapper(base) {
            override fun getPackageName(): String = "com.android.shell"
            override fun getOpPackageName(): String = "com.android.shell"
            override fun getAttributionSource(): AttributionSource = shellAttr
            override fun getApplicationContext(): Context = this
        }
    }

    /** API 31+ 显式为 `AudioRecord.Builder` 绑定 `com.android.shell` Context（避免回落到 `ActivityThread.currentOpPackageName()`）。 */
    fun applyRecordContext(builder: AudioRecord.Builder, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyRecordContextApi31(builder, context)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyRecordContextApi31(builder: AudioRecord.Builder, context: Context) {
        builder.setContext(context)
    }
}
