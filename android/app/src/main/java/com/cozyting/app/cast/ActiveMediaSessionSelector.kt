package com.cozyting.app.cast

import com.cozyting.app.capture.FavoriteAudioSource

/**
 * 纯逻辑：从多个活跃 MediaSession 中选出：
 * 1. UI 展示与播控对应的活跃会话（优先当前正在播放且最新开始播放的会话；均暂停时优先当前投送目标）；
 * 2. 自动识别 / 投送中热切换的候选会话（严格限制在常用音源且开启独立自动识别开关的 App 内）。
 */
object ActiveMediaSessionSelector {

    data class SessionSnapshot(
        val tokenKey: String,
        val packageName: String,
        val isPlaying: Boolean,
        val lastBecamePlayingSeq: Long,
        val listOrderIndex: Int,
    )

    fun selectDisplaySession(
        sessions: List<SessionSnapshot>,
        preferredPackage: String,
        isCasting: Boolean,
        selfPackage: String,
    ): SessionSnapshot? {
        val valid = sessions.filter { it.packageName.isNotBlank() && it.packageName != selfPackage }
        if (valid.isEmpty()) return null

        val playing = valid.filter { it.isPlaying }
        if (playing.isNotEmpty()) {
            return playing.sortedWith(
                compareByDescending<SessionSnapshot> { it.lastBecamePlayingSeq }
                    .thenByDescending {
                        if (isCasting && preferredPackage.isNotBlank() && it.packageName == preferredPackage) 1 else 0
                    }
                    .thenBy { it.listOrderIndex },
            ).first()
        }

        if (preferredPackage.isNotBlank()) {
            valid.firstOrNull { it.packageName == preferredPackage }?.let { return it }
        }
        return valid.minByOrNull { it.listOrderIndex }
    }

    /**
     * 选出当前最适合参与自动识别（待机或投送中热切换）的常用音源会话：
     * - 仅考虑 [favoriteSources] 中且 `autoDetectEnabled == true` 的包名；
     * - 若存在正在播放的合格会话，取最近进入播放态（`lastBecamePlayingSeq` 最大）者；
     * - 投送中绝不回退到已暂停会话；仅在待机且尚未选定任何音源时允许回退首个合格暂停会话。
     */
    fun selectAutoDetectCandidate(
        sessions: List<SessionSnapshot>,
        favoriteSources: List<FavoriteAudioSource>,
        isCasting: Boolean,
        currentSelectedPackage: String,
        selfPackage: String,
        blockedUntilNewPlaySeq: Map<String, Long> = emptyMap(),
    ): SessionSnapshot? {
        val eligiblePkgs = favoriteSources
            .asSequence()
            .filter { it.autoDetectEnabled }
            .map { it.packageName.trim() }
            .filter { it.isNotEmpty() && it != selfPackage }
            .toSet()
        if (eligiblePkgs.isEmpty()) return null

        val eligible = sessions.filter { it.packageName in eligiblePkgs }
        if (eligible.isEmpty()) return null

        val playing = eligible.filter { snap ->
            if (!snap.isPlaying) return@filter false
            val blockedSeq = blockedUntilNewPlaySeq[snap.packageName]
            blockedSeq == null || snap.lastBecamePlayingSeq > blockedSeq
        }
        if (playing.isNotEmpty()) {
            return playing.sortedWith(
                compareByDescending<SessionSnapshot> { it.lastBecamePlayingSeq }
                    .thenByDescending {
                        if (isCasting && currentSelectedPackage.isNotBlank() && it.packageName == currentSelectedPackage) 1 else 0
                    }
                    .thenBy { it.listOrderIndex },
            ).first()
        }

        if (!isCasting && currentSelectedPackage.isBlank()) {
            return eligible.minByOrNull { it.listOrderIndex }
        }
        return null
    }

    /**
     * 清理不再需要屏蔽的旧包名记录：
     * 当被屏蔽包已不再处于播放态，或已产生比屏蔽时更新的播放序号时，解除该包的屏蔽，
     * 使其在下次重新点击播放时可被自动识别切换。
     */
    fun pruneBlockedPackages(
        blockedUntilNewPlaySeq: Map<String, Long>,
        sessions: List<SessionSnapshot>,
    ): Map<String, Long> {
        if (blockedUntilNewPlaySeq.isEmpty()) return emptyMap()
        val playingMaxSeqByPkg = mutableMapOf<String, Long>()
        for (snap in sessions) {
            if (!snap.isPlaying) continue
            val prev = playingMaxSeqByPkg[snap.packageName]
            if (prev == null || snap.lastBecamePlayingSeq > prev) {
                playingMaxSeqByPkg[snap.packageName] = snap.lastBecamePlayingSeq
            }
        }
        val next = linkedMapOf<String, Long>()
        for ((pkg, blockedSeq) in blockedUntilNewPlaySeq) {
            val activeSeq = playingMaxSeqByPkg[pkg]
            if (activeSeq != null && activeSeq <= blockedSeq) {
                next[pkg] = blockedSeq
            }
        }
        return next
    }
}
