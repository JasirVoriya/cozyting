package com.miaocast.app

import com.miaocast.app.cast.LocalOutputMute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * 回归：CastService 构造不得经 AndroidStreamMusicVolume 触碰未 attach 的 applicationContext
 *（0.9.1 真机 NPE：AndroidStreamMusicVolume.<init> ← CastService.<init>）。
 *
 * JVM 单测无法完整 attach Service（Looper stub），故用字段形态 + 构造堆栈帧断言锁死修法。
 */
class CastServiceMuteInitTest {

    @Test
    fun localMuteIsAssignableLateinitStyleField() {
        val f = CastService::class.java.getDeclaredField("localMute")
        assertEquals(LocalOutputMute::class.java, f.type)
        // lateinit var → 非 final；若仍是 private val localMute = … 则为 final 并在 <init> 赋值
        assertFalse(
            "localMute 必须是 lateinit var（非 final），禁止属性初始化器里 new AndroidStreamMusicVolume(this)",
            Modifier.isFinal(f.modifiers),
        )
    }

    @Test
    fun constructingCastService_mustNotEnterAndroidStreamMusicVolumeInit() {
        try {
            CastService()
        } catch (t: Throwable) {
            val frames = generateSequence(t) { it.cause }
                .flatMap { it.stackTrace.asSequence() }
                .map { "${it.className}.${it.methodName}" }
                .toList()
            val hitMuteCtor = frames.any {
                it.startsWith("com.miaocast.app.cast.AndroidStreamMusicVolume.")
            }
            if (hitMuteCtor) {
                fail("构造期堆栈仍进入 AndroidStreamMusicVolume（会 getApplicationContext NPE）: $frames")
            }
            val hitAppCtxInServiceInit = frames.any {
                it == "android.content.ContextWrapper.getApplicationContext"
            } && frames.any { it.startsWith("com.miaocast.app.CastService.") }
            // 仅当 getApplicationContext 与 CastService 同栈且来自 mute 路径才算回归失败；
            // Looper stub 失败可接受。
            if (hitAppCtxInServiceInit && frames.any { it.contains("AndroidStreamMusicVolume") }) {
                fail("CastService 构造期 getApplicationContext: $frames")
            }
            assertTrue(
                "意外失败: ${t.javaClass.name}: ${t.message}",
                t.message.orEmpty().contains("getMainLooper") ||
                    t.message.orEmpty().contains("not mocked") ||
                    frames.any { it.contains("Looper.getMainLooper") },
            )
        }
    }
}
