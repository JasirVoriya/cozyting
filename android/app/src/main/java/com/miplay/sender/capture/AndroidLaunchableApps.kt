package com.miplay.sender.capture

import android.content.Intent
import android.content.pm.PackageManager
import android.content.Context

object AndroidLaunchableApps {
    fun list(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val apps = resolved.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            val label = ri.loadLabel(pm)?.toString() ?: pkg
            LaunchableApp(packageName = pkg, label = label, hasMainLauncher = true)
        }.distinctBy { it.packageName }
        return LaunchableAppCatalog.filterLaunchable(apps, selfPackage = context.packageName)
    }
}
