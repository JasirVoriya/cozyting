package com.miplay.sender.compat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * APK 安装与「允许安装未知应用」权限兼容层（minSdk=29 全覆盖，零 API33+ 独占调用）。
 */
object ApkInstallerCompat {
    const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    fun fileProviderAuthority(packageName: String): String =
        "$packageName.update.fileprovider"

    /** API 26+ 均支持 canRequestPackageInstalls（minSdk=29 直接安全）。 */
    fun canRequestPackageInstalls(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    fun buildUnknownSourcesSettingsIntent(packageName: String): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun buildInstallIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            fileProviderAuthority(context.packageName),
            apkFile,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
