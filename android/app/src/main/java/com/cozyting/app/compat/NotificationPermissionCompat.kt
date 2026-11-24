package com.cozyting.app.compat

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build

/** POST_NOTIFICATIONS 仅 API33+ 需要运行时申请；更低版本清单声明可保留、勿强求授权。 */
object NotificationPermissionCompat {
    /** 字面量避免 InlinedApi 对 Manifest.permission.POST_NOTIFICATIONS（API33）的告警。 */
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    fun shouldRequest(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= 33

    fun requestIfNeeded(activity: Activity, requestCode: Int) {
        if (!shouldRequest()) return
        if (activity.checkSelfPermission(POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        activity.requestPermissions(arrayOf(POST_NOTIFICATIONS), requestCode)
    }

    fun runtimePermissionsForColdStart(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> {
        return if (shouldRequest(sdkInt)) arrayOf(POST_NOTIFICATIONS) else emptyArray()
    }
}
