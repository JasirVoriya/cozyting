package com.miaocast.app.compat

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build

/**
 * 签名读取：使用 API28+ 的 GET_SIGNING_CERTIFICATES + 旧版 getPackageInfo(String,int) 重载。
 * 禁止仅调用 API33+ PackageInfoFlags 重载（在 API29 上方法不存在）。
 */
object PackageSigningCompat {
    const val SIGNING_CERTS_SINCE = 28
    const val usesLegacyGetPackageInfoFlags = true

    fun packageInfoForSigning(pm: PackageManager, packageName: String): PackageInfo {
        @Suppress("DEPRECATION")
        return pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    fun apkContentsSigners(pi: PackageInfo): Array<Signature>? {
        val info = pi.signingInfo ?: return null
        return info.apkContentsSigners
    }

    /** 文档/测试用：当前运行时是否具备签名证书 API。 */
    fun signingApisAvailable(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= SIGNING_CERTS_SINCE
}
