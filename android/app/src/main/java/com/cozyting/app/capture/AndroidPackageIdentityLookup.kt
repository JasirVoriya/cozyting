package com.cozyting.app.capture

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.cozyting.app.compat.PackageSigningCompat
import java.security.MessageDigest

/** 基于 PackageManager 的身份解析：同用户、共享 UID、签名摘要。 */
class AndroidPackageIdentityLookup(
    private val context: Context,
) : PackageIdentityLookup {
    override fun lookup(packageName: String): PackageIdentity? {
        val pm = context.packageManager
        val info = try {
            pm.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val myUid = Process.myUid()
        val sameUser = UserHandleCompat.isSameAppUser(myUid, info.uid)
        val sharedUserId = try {
            val pi = pm.getPackageInfo(packageName, 0)
            pi.sharedUserId
        } catch (_: Exception) {
            null
        }
        val sharing = packagesForUid(pm, info.uid)
        val cert = signingCertSha256(pm, packageName) ?: return null
        return PackageIdentity(
            packageName = packageName,
            uid = info.uid,
            sharedUserId = sharedUserId,
            packagesSharingUid = sharing,
            sameUser = sameUser,
            signingCertSha256 = cert,
        )
    }

    private fun packagesForUid(pm: PackageManager, uid: Int): List<String> =
        pm.getPackagesForUid(uid)?.toList().orEmpty()

    companion object {
        fun signingCertSha256(pm: PackageManager, packageName: String): String? {
            return try {
                // API28+ GET_SIGNING_CERTIFICATES + 旧 getPackageInfo(String,int)；勿用 API33-only Flags 重载
                val pi = PackageSigningCompat.packageInfoForSigning(pm, packageName)
                val sigs = PackageSigningCompat.apkContentsSigners(pi) ?: return null
                if (sigs.isEmpty()) return null
                val md = MessageDigest.getInstance("SHA-256")
                val dig = md.digest(sigs[0].toByteArray())
                dig.joinToString("") { b -> "%02x".format(b) }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** 避免直接依赖 hidden API：用 uid 的 userId 比较。 */
internal object UserHandleCompat {
    fun isSameAppUser(uidA: Int, uidB: Int): Boolean = (uidA / 100000) == (uidB / 100000)
}
