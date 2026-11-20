package com.miplay.sender.capture

/**
 * acceptance「本地音频过滤验收」门禁：无音箱也可启动；严禁扩大 UID / 回退全系统；
 * 本地探针模式不得触发 MiPlay 连接。
 */
object LocalFilterAcceptanceGate {
    enum class Mode { LOCAL_PCM_PROBE, MIPLAY_CAST }

    enum class BlockReason { NO_TARGET_APP, NO_SPEAKERS }

    sealed class StartDecision {
        data object Allow : StartDecision()
        data class Block(val reason: BlockReason, val messageZh: String) : StartDecision()
    }

    data class ExecutionPlan(
        val callMiPlayConnect: Boolean,
        val callMiPlayDiscover: Boolean,
        val callMiPlayCreateSession: Boolean,
        val sendMediaToSpeakers: Boolean,
        val playLocalMonitor: Boolean,
        val recordMicrophone: Boolean,
        val recordDisplaySurface: Boolean,
        val persistAudioFiles: Boolean,
        val runUidFilteredPlaybackCapture: Boolean,
        val runPcmProbeOnly: Boolean,
    )

    fun validateStart(
        mode: Mode,
        targetPackageSelected: Boolean,
        speakerCount: Int,
    ): StartDecision {
        if (!targetPackageSelected && mode == Mode.LOCAL_PCM_PROBE) {
            return StartDecision.Block(
                BlockReason.NO_TARGET_APP,
                "请先选择严格目标 App。本地过滤验收不会回退全系统捕获。",
            )
        }
        if (mode == Mode.MIPLAY_CAST && speakerCount <= 0) {
            return StartDecision.Block(
                BlockReason.NO_SPEAKERS,
                "请勾选音箱",
            )
        }
        if (mode == Mode.LOCAL_PCM_PROBE && !targetPackageSelected) {
            return StartDecision.Block(BlockReason.NO_TARGET_APP, "请先选择目标 App")
        }
        // LOCAL_PCM_PROBE：speakerCount 可为 0
        if (mode == Mode.LOCAL_PCM_PROBE && targetPackageSelected) {
            return StartDecision.Allow
        }
        if (mode == Mode.MIPLAY_CAST && targetPackageSelected && speakerCount > 0) {
            return StartDecision.Allow
        }
        // silent cast without app target is separate; for app cast need target — handled by callers
        if (mode == Mode.MIPLAY_CAST && speakerCount > 0) return StartDecision.Allow
        return StartDecision.Block(BlockReason.NO_SPEAKERS, "请勾选音箱")
    }

    fun executionPlan(mode: Mode): ExecutionPlan = when (mode) {
        Mode.LOCAL_PCM_PROBE -> ExecutionPlan(
            callMiPlayConnect = false,
            callMiPlayDiscover = false,
            callMiPlayCreateSession = false,
            sendMediaToSpeakers = false,
            playLocalMonitor = false,
            recordMicrophone = false,
            recordDisplaySurface = false,
            persistAudioFiles = false,
            runUidFilteredPlaybackCapture = true,
            runPcmProbeOnly = true,
        )
        Mode.MIPLAY_CAST -> ExecutionPlan(
            callMiPlayConnect = true,
            callMiPlayDiscover = true,
            callMiPlayCreateSession = true,
            sendMediaToSpeakers = true,
            playLocalMonitor = false,
            recordMicrophone = false,
            recordDisplaySurface = false,
            persistAudioFiles = false,
            runUidFilteredPlaybackCapture = true,
            runPcmProbeOnly = false,
        )
    }

    fun forbidUidWideningOrSystemFallback(selectedUids: List<Int>, attemptedUids: List<Int>) {
        if (attemptedUids.isEmpty()) {
            throw IllegalStateException("禁止空 UID 白名单（会回退全系统捕获）")
        }
        if (attemptedUids.toSet() != selectedUids.toSet()) {
            throw IllegalStateException("禁止扩大或更改 UID 过滤范围：selected=$selectedUids attempted=$attemptedUids")
        }
        if (attemptedUids.size != 1) {
            throw IllegalStateException("严格单选 App 仅允许 1 个 UID，禁止扩大")
        }
    }

    fun silenceCannotProveFilterZh(): String =
        "捕获近似静音：不能据此证明 UID 过滤（可能音量=0、目标未播放或禁止捕获）。"

    fun exposeLocalProbeEntry(isAcceptanceBuild: Boolean): Boolean = isAcceptanceBuild
}
