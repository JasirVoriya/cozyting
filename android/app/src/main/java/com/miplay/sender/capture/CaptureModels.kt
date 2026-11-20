package com.miplay.sender.capture

/** 开始捕获前对目标包的身份快照（不持久化 UID）。 */
data class PackageIdentity(
    val packageName: String,
    val uid: Int,
    val sharedUserId: String?,
    val packagesSharingUid: List<String>,
    val sameUser: Boolean,
    val signingCertSha256: String,
)

interface PackageIdentityLookup {
    fun lookup(packageName: String): PackageIdentity?
}

enum class CaptureFailReason {
    NO_SELECTION,
    NOT_FOUND,
    SHARED_UID,
    CROSS_USER,
    IDENTITY_CHANGED,
}

sealed class CaptureTargetResult {
    data class Ok(
        val packageName: String,
        val uid: Int,
        val signingCertSha256: String,
    ) : CaptureTargetResult()

    data class Fail(
        val reason: CaptureFailReason,
        val messageZh: String,
    ) : CaptureTargetResult()
}

data class TargetAppSelection(
    val packageName: String,
    val label: String,
    val signingCertSha256: String,
)

interface TargetAppStore {
    fun saveSelection(selection: TargetAppSelection)
    fun loadSelection(): TargetAppSelection?
    fun clearSelection()
    fun loadRecentSelections(limit: Int = 6): List<TargetAppSelection> = emptyList()
    /** 明确不提供持久 UID。 */
    fun loadUid(): Int? = null
}

data class LaunchableApp(
    val packageName: String,
    val label: String,
    val hasMainLauncher: Boolean,
)
