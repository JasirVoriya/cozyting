package com.cozyting.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：保留分组/暂停/重连/通知相关的服务契约（动作与 extra 名不可无故改掉）。
 * 真机重连/分组行为仍依赖 Rust core 单测与设备验收。
 */
class MiPlayFeatureRegressionTest {
    @Test
    fun castServiceActions_preserved() {
        assertEquals("com.cozyting.app.START", CastService.ACTION_START)
        assertEquals("com.cozyting.app.STOP", CastService.ACTION_STOP)
        assertEquals("com.cozyting.app.PAUSE", CastService.ACTION_PAUSE)
        assertEquals("com.cozyting.app.RESUME", CastService.ACTION_RESUME)
        assertEquals("com.cozyting.app.ADD", CastService.ACTION_ADD)
        assertEquals("com.cozyting.app.REMOVE", CastService.ACTION_REMOVE)
        assertEquals("com.cozyting.app.VOLUME", CastService.ACTION_VOLUME)
        assertEquals("com.cozyting.app.SWITCH_SOURCE", CastService.ACTION_SWITCH_SOURCE)
    }

    @Test
    fun appSource_doesNotUseLegacySystemLabel() {
        // 音源取值：silent | app；不再把“系统音频全量”作为 App 路径回退
        assertEquals("source", CastService.EXTRA_SOURCE)
        assertEquals("target_package", CastService.EXTRA_TARGET_PACKAGE)
        assertTrue(CastService.EXTRA_GRANT_SESSION.isNotBlank())
    }
}
