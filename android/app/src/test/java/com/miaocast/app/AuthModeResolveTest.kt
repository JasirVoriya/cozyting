package com.miaocast.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v6 D1：失败日志无 auth_resolved，且 connect→prepare 时长接近 Auto，不能把 UI「Safety」当已执行。
 * 锁定自动化字符串/整型解析，避免只靠 spinner 静默落到 auto。
 */
class AuthModeResolveTest {
    @Test
    fun stringSafety_mapsTo2() {
        assertEquals(2, AuthModeResolve.fromParts("safety", null, false))
        assertEquals("safety", AuthModeResolve.label(2))
    }

    @Test
    fun intSafety_mapsTo2() {
        assertEquals(2, AuthModeResolve.fromParts(null, 2, true))
    }

    @Test
    fun stringTakesPriorityOverInt() {
        assertEquals(1, AuthModeResolve.fromParts("legacy", 2, true))
    }

    @Test
    fun missing_returnsNull() {
        assertNull(AuthModeResolve.fromParts(null, null, false))
        assertNull(AuthModeResolve.fromString(null))
        assertNull(AuthModeResolve.fromString("weird"))
    }

    @Test
    fun coerceClamps() {
        assertEquals(0, AuthModeResolve.coerceMode(-1))
        assertEquals(2, AuthModeResolve.coerceMode(99))
    }
}
