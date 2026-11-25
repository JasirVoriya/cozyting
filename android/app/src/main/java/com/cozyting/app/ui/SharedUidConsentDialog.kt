package com.cozyting.app.ui

import android.app.Activity
import android.app.Dialog
import com.cozyting.app.R

/**
 * 第三方应用共享 UID 显式知情同意弹窗（仅普通多包同 UID 场景触发，系统级特权 UID 始终硬拒绝）。
 */
object SharedUidConsentDialog {

    fun resolvePeerLabels(
        activity: Activity,
        targetPackage: String,
        sharedPackages: List<String>,
    ): List<String> {
        val pm = activity.packageManager
        val peers = sharedPackages
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != targetPackage }
            .distinct()
        return peers.map { pkg ->
            runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim()
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: pkg
        }
    }

    fun show(
        activity: Activity,
        palette: UiPalette,
        targetLabel: String,
        targetPackage: String,
        sharedPackages: List<String>,
        onAllow: () -> Unit,
        onDismissed: () -> Unit = {},
    ): Dialog? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val peerLabels = resolvePeerLabels(activity, targetPackage, sharedPackages)
        val peersText = peerLabels.joinToString("、").ifBlank { targetPackage }
        val message = activity.getString(R.string.shared_uid_consent_msg, targetLabel, peersText)
        return UiComponents.showStyleBDialog(
            activity = activity,
            palette = palette,
            title = activity.getString(R.string.shared_uid_consent_title),
            message = message,
            primaryLabel = activity.getString(R.string.shared_uid_consent_cancel),
            onPrimary = {},
            secondaryLabel = activity.getString(R.string.shared_uid_consent_allow),
            onSecondary = onAllow,
            onDismissed = onDismissed,
        )
    }
}
