package com.miaocast.app.cast

/**
 * 投送协议标注（产品徽标，非官方商标复制）。
 * 当前发现栈仅产出 [MIPLAY]；其余枚举供资源映射与对照预览，**未实现投送**。
 */
enum class CastProtocol(
    val id: String,
    val labelZh: String,
    /** 本 App 是否已实现向该协议设备投送。 */
    val castImplemented: Boolean,
) {
    MIPLAY("miplay", "MiPlay", castImplemented = true),
    DLNA("dlna", "DLNA", castImplemented = false),
    AIRPLAY("airplay", "AirPlay", castImplemented = false),
    GOOGLE_CAST("gcast", "Google Cast", castImplemented = false),
    ;

    companion object {
        fun fromId(id: String?): CastProtocol? =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }

        /** 对照预览 / 图例顺序。 */
        fun legendOrder(): List<CastProtocol> = entries.toList()
    }
}
