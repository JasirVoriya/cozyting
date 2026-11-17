package com.miplay.sender.capture

/** 可启动 App 列表规则：MAIN/LAUNCHER，不申请 QUERY_ALL_PACKAGES。 */
object LaunchableAppCatalog {
    fun filterLaunchable(
        apps: List<LaunchableApp>,
        selfPackage: String? = null,
    ): List<LaunchableApp> =
        apps.filter { it.hasMainLauncher && (selfPackage == null || it.packageName != selfPackage) }
            .sortedBy { it.label.lowercase() }

    fun requiredManifestQueriesActions(): List<String> =
        listOf("android.intent.action.MAIN", "android.intent.category.LAUNCHER")

    fun requestsQueryAllPackages(): Boolean = false
}
