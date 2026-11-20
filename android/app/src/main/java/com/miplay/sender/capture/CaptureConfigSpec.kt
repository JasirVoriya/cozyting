package com.miplay.sender.capture

/**
 * AudioPlaybackCapture 配置规格：App 模式必须带精确 UID + usage 过滤。
 * 故意不提供“无 UID 的系统级”工厂给 App 音源路径。
 */
data class CaptureConfigSpec(
    val matchingUids: List<Int>,
    val matchingUsages: List<Int>,
    val isStrictAppFilter: Boolean,
) {
    companion object {
        /** AudioAttributes.USAGE_MEDIA / GAME / UNKNOWN 的整型常量，避免 unit test 依赖 android.jar。 */
        const val USAGE_MEDIA = 1
        const val USAGE_GAME = 14
        const val USAGE_UNKNOWN = 0
        val DEFAULT_USAGES = listOf(USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN)

        fun appScoped(uid: Int?, usages: List<Int> = DEFAULT_USAGES): CaptureConfigSpec {
            val u = uid ?: throw IllegalStateException("App 捕获必须提供 UID，禁止空白名单")
            if (u < 0) throw IllegalStateException("非法 UID=$u")
            return CaptureConfigSpec(
                matchingUids = listOf(u),
                matchingUsages = usages,
                isStrictAppFilter = true,
            )
        }

        /** 若调用方试图在已选 App 时退回全系统捕获，直接失败。 */
        fun forbidSystemWideFallback(requestedAppPackage: String): Nothing {
            throw IllegalStateException(
                "已选择投送 App（$requestedAppPackage）时禁止回退全系统捕获",
            )
        }
    }
}
