package com.cozyting.app.ui

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * 主界面与悬浮控制卡共用的物理音量键拦截控制器：
 * - 仅在已选音箱/投送中（[shouldIntercept] 返回 true）时拦截 `KEYCODE_VOLUME_UP` / `KEYCODE_VOLUME_DOWN`；
 * - 同时消费 `ACTION_DOWN` 与 `ACTION_UP`，防止弹出系统媒体音量条；
 * - 单次按压步进 ±[stepPercent]%（默认 5%），连按/长按期间即时更新 UI 预览并做 [commitDebounceMs] 合并防抖，
 *   松手 `ACTION_UP` 时立即提交，避免高频下发打满音箱控制通道。
 */
class HardwareVolumeKeyController(
    private val stepPercent: Int = DEFAULT_STEP_PERCENT,
    private val commitDebounceMs: Long = DEFAULT_COMMIT_DEBOUNCE_MS,
    private val scheduler: Scheduler = HandlerScheduler(Handler(Looper.getMainLooper())),
    private val shouldIntercept: () -> Boolean,
    private val currentVolume: () -> Int,
    private val onPreview: (Int) -> Unit,
    private val onCommit: (Int) -> Unit,
) {
    interface Scheduler {
        fun postDelayed(runnable: Runnable, delayMs: Long)
        fun removeCallbacks(runnable: Runnable)
    }

    private class HandlerScheduler(private val handler: Handler) : Scheduler {
        override fun postDelayed(runnable: Runnable, delayMs: Long) {
            handler.postDelayed(runnable, delayMs)
        }

        override fun removeCallbacks(runnable: Runnable) {
            handler.removeCallbacks(runnable)
        }
    }

    companion object {
        const val DEFAULT_STEP_PERCENT = 5
        const val DEFAULT_COMMIT_DEBOUNCE_MS = 100L
    }

    private var pendingTarget: Int? = null
    private var interceptedDown = false

    private val commitRunnable = Runnable {
        flushPendingCommit()
    }

    /**
     * 处理来自 `Activity.dispatchKeyEvent` 的按键事件；返回 `true` 表示已消费，不再交由系统处理。
     */
    fun onDispatchKeyEvent(event: KeyEvent): Boolean {
        val delta = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> stepPercent
            KeyEvent.KEYCODE_VOLUME_DOWN -> -stepPercent
            else -> return false
        }

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (!shouldIntercept()) {
                    return false
                }
                interceptedDown = true
                val base = pendingTarget ?: currentVolume()
                val next = (base + delta).coerceIn(0, 100)
                pendingTarget = next
                onPreview(next)
                scheduler.removeCallbacks(commitRunnable)
                scheduler.postDelayed(commitRunnable, commitDebounceMs)
                true
            }
            KeyEvent.ACTION_UP -> {
                if (!interceptedDown && !shouldIntercept()) {
                    return false
                }
                interceptedDown = false
                scheduler.removeCallbacks(commitRunnable)
                flushPendingCommit()
                true
            }
            else -> false
        }
    }

    fun cancel() {
        interceptedDown = false
        pendingTarget = null
        scheduler.removeCallbacks(commitRunnable)
    }

    private fun flushPendingCommit() {
        val target = pendingTarget ?: return
        pendingTarget = null
        onCommit(target)
    }
}
