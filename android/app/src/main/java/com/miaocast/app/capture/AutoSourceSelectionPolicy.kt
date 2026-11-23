package com.miaocast.app.capture

/**
 * 自动识别当前正在播放音源的纯逻辑策略：
 * 1. 严格限制在用户「常用音源名单」内，且该 App 的独立 `autoDetectEnabled` 开关必须为 true；
 * 2. 待机与投送中均可自动跟随：投送中仅在新音源主动进入播放态时触发热切换，绝不因暂停会话误切；
 * 3. 避免用户手动切换音源后立刻被后台仍在播放的旧 App 抢回；
 * 4. 命中后仍须经 [CaptureTargetResolver] 完成单 UID + 签名 fail-closed 校验，绝不退化全系统捕获。
 */
object AutoSourceSelectionPolicy {

    data class Input(
        val globalAutoDetectEnabled: Boolean,
        val isCasting: Boolean,
        val sourceMode: String,
        val currentSelectedPackage: String,
        val selfPackage: String,
        val nowPlayingPackage: String?,
        val nowPlayingIsPlaying: Boolean?,
        val favoriteSources: List<FavoriteAudioSource>,
        val manualOverrideBlockedPackage: String? = null,
    )

    sealed class Decision {
        data class AutoSelect(val packageName: String) : Decision()
        data class Skip(val reason: String) : Decision()
    }

    fun decide(input: Input): Decision {
        if (!input.globalAutoDetectEnabled) return Decision.Skip("global_disabled")
        if (input.sourceMode != "app") return Decision.Skip("non_app_source_mode")

        val pkg = input.nowPlayingPackage?.trim().orEmpty()
        if (pkg.isEmpty() || pkg == input.selfPackage) return Decision.Skip("invalid_package")

        val fav = input.favoriteSources.firstOrNull { it.packageName == pkg }
            ?: return Decision.Skip("not_in_favorites")
        if (!fav.autoDetectEnabled) return Decision.Skip("app_auto_detect_disabled")

        if (pkg == input.currentSelectedPackage) return Decision.Skip("already_selected")
        if (!input.manualOverrideBlockedPackage.isNullOrBlank() && pkg == input.manualOverrideBlockedPackage) {
            return Decision.Skip("manual_override_active")
        }

        val shouldFollow = input.nowPlayingIsPlaying == true ||
            (!input.isCasting && input.currentSelectedPackage.isBlank())
        if (!shouldFollow) return Decision.Skip("not_actively_playing")

        return Decision.AutoSelect(pkg)
    }

    /**
     * 当活跃媒体会话切到了新的包名时，解除旧的手动覆盖屏蔽标记。
     */
    fun nextManualOverrideBlockedPackage(
        currentBlockedPackage: String?,
        nowPlayingPackage: String?,
    ): String? {
        if (currentBlockedPackage.isNullOrBlank()) return null
        val active = nowPlayingPackage?.trim().orEmpty()
        return if (active.isNotEmpty() && active != currentBlockedPackage) null else currentBlockedPackage
    }
}
