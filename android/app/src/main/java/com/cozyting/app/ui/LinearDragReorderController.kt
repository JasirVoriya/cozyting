package com.cozyting.app.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/**
 * 零外部依赖的原生 LinearLayout 长按拖拽排序控制器：
 * - 支持纵向（常用音源卡片列表）与横向（主界面快捷音源胶囊栏）；
 * - 由宿主 Activity 的 `dispatchTouchEvent` 转发事件，拖拽期间通过合成 `ACTION_CANCEL` 清除原点击态，
 *   并在容器内 `removeView/addView` 换位时保持手势不中断、手指下方的视图零跳动；
 * - 兼容 Android 10 (API 29)+，无任何高版本 API 依赖。
 */
class LinearDragReorderController(
    context: Context,
    private val axis: Axis,
    private val containerProvider: () -> LinearLayout?,
    private val scrollViewProvider: () -> View?,
    private val itemKeyProvider: (View) -> String?,
    private val isEnabled: () -> Boolean = { true },
    private val dispatchSyntheticCancel: (MotionEvent) -> Unit,
    private val onDragVisualStateChanged: (View, Boolean) -> Unit = { _, _ -> },
    private val onItemMoved: (fromKey: String, toKey: String) -> Unit,
    private val onDragFinished: (orderChanged: Boolean) -> Unit,
) {
    enum class Axis { VERTICAL, HORIZONTAL }

    companion object {
        const val TAG_IGNORE = "cozyting_drag_ignore"
        const val TAG_HANDLE = "cozyting_drag_handle"
        private const val HANDLE_LONG_PRESS_MS = 220L
        private const val MAX_CARD_LONG_PRESS_MS = 380L
        private const val SWAP_ANIM_MS = 150L
        private const val EDGE_ZONE_DP = 56
        private const val AUTO_SCROLL_STEP_DP = 6
    }

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val cardLongPressMs =
        ViewConfiguration.getLongPressTimeout().toLong().coerceIn(260L, MAX_CARD_LONG_PRESS_MS)
    private val edgeZonePx = UiComponents.dp(context, EDGE_ZONE_DP).toFloat()
    private val autoScrollStepPx = UiComponents.dp(context, AUTO_SCROLL_STEP_DP).coerceAtLeast(4)
    private val liftElevationPx = UiComponents.dpf(context, 8f)

    private var dispatchingCancel = false
    private var pendingView: View? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f

    private var draggingView: View? = null
    private var draggingKey: String? = null
    private var anchorRaw = 0f
    private var orderChanged = false
    private var awaitingSwapLayout = false
    private var autoScrolling = false
    private val tmpLoc = IntArray(2)

    private val longPressRunnable = Runnable {
        val target = pendingView ?: return@Runnable
        pendingView = null
        startDragging(target)
    }

    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            val view = draggingView ?: run {
                autoScrolling = false
                return
            }
            val scroller = scrollViewProvider() ?: run {
                autoScrolling = false
                return
            }
            val delta = computeEdgeScrollDelta(scroller)
            if (delta == 0) {
                autoScrolling = false
                return
            }
            val actual = scrollByAxis(scroller, delta)
            if (actual != 0) {
                // 滚动使内容反向位移，同步补偿锚点，确保拖拽视图始终紧贴手指。
                anchorRaw -= actual.toFloat()
                updateDraggingTranslation(view)
                trySwapIfNeeded(view)
                view.postOnAnimation(this)
            } else {
                autoScrolling = false
            }
        }
    }

    val isDragging: Boolean
        get() = draggingView != null

    /**
     * 在宿主 Activity 的 `dispatchTouchEvent(ev)` 入口调用。
     * 返回 `true` 表示事件已被拖拽控制器消费，宿主不应再转发给 `super.dispatchTouchEvent(ev)`。
     */
    fun onDispatchTouchEvent(ev: MotionEvent): Boolean {
        if (dispatchingCancel) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPending()
                if (draggingView != null) {
                    finishDragging()
                }
                if (!isEnabled()) return false
                val container = containerProvider() ?: return false
                if (draggableChildCount(container) < 2) return false
                val hit = findDraggableChildAt(container, ev.rawX, ev.rawY) ?: return false
                if (isPointInsideTaggedDescendant(hit, ev.rawX, ev.rawY, TAG_IGNORE)) {
                    return false
                }
                val onHandle = isPointInsideTaggedDescendant(hit, ev.rawX, ev.rawY, TAG_HANDLE)
                pendingView = hit
                downRawX = ev.rawX
                downRawY = ev.rawY
                lastRawX = ev.rawX
                lastRawY = ev.rawY
                val delayMs = if (onHandle) HANDLE_LONG_PRESS_MS else cardLongPressMs
                handler.postDelayed(longPressRunnable, delayMs)
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                lastRawX = ev.rawX
                lastRawY = ev.rawY
                val active = draggingView
                if (active != null) {
                    updateDraggingTranslation(active)
                    trySwapIfNeeded(active)
                    maybeStartAutoScroll(active)
                    return true
                }
                if (pendingView != null) {
                    val dx = abs(ev.rawX - downRawX)
                    val dy = abs(ev.rawY - downRawY)
                    if (dx > touchSlop || dy > touchSlop) {
                        cancelPending()
                    }
                }
                return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPending()
                if (draggingView != null) {
                    finishDragging()
                    return true
                }
                return false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelPending()
                return draggingView != null
            }

            else -> return draggingView != null
        }
    }

    /** 在视图重建或页面销毁前安全清理挂起的定时器和拖拽态。 */
    fun cancel() {
        cancelPending()
        if (draggingView != null) {
            finishDragging(notifyIfChanged = false)
        }
    }

    private fun cancelPending() {
        handler.removeCallbacks(longPressRunnable)
        pendingView = null
    }

    private fun startDragging(target: View) {
        val container = containerProvider() ?: return
        if (target.parent !== container) return
        val key = itemKeyProvider(target) ?: return
        if (draggableChildCount(container) < 2) return

        draggingView = target
        draggingKey = key
        orderChanged = false
        awaitingSwapLayout = false
        anchorRaw = currentRawForAxis()

        // 向原视图树发送一次合成 ACTION_CANCEL，清理按下水波纹并杜绝松手误触发 onClick。
        val now = SystemClock.uptimeMillis()
        val cancelEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, lastRawX, lastRawY, 0)
        dispatchingCancel = true
        try {
            dispatchSyntheticCancel(cancelEvent)
        } finally {
            dispatchingCancel = false
            cancelEvent.recycle()
        }

        (scrollViewProvider() as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
        target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        target.elevation = liftElevationPx
        target.translationZ = liftElevationPx
        target.animate()
            .scaleX(1.025f)
            .scaleY(1.025f)
            .alpha(0.95f)
            .setDuration(120L)
            .start()
        onDragVisualStateChanged(target, true)
    }

    private fun updateDraggingTranslation(view: View) {
        val delta = currentRawForAxis() - anchorRaw
        when (axis) {
            Axis.VERTICAL -> view.translationY = delta
            Axis.HORIZONTAL -> view.translationX = delta
        }
    }

    private fun trySwapIfNeeded(dragged: View) {
        if (awaitingSwapLayout) return
        val container = containerProvider() ?: return
        val fromIndex = container.indexOfChild(dragged)
        if (fromIndex < 0) return
        val fromKey = draggingKey ?: return

        val draggedCenter = viewStart(dragged) + viewTranslation(dragged) + viewSize(dragged) / 2f
        val targetIndex = findSwapTargetIndex(container, fromIndex, draggedCenter) ?: return
        val neighbor = container.getChildAt(targetIndex) ?: return
        val toKey = itemKeyProvider(neighbor) ?: return
        if (fromKey == toKey) return

        val draggedOldStart = viewStart(dragged)
        val neighborOldStart = viewStart(neighbor)
        awaitingSwapLayout = true

        container.removeViewAt(fromIndex)
        container.addView(dragged, targetIndex)
        orderChanged = true
        dragged.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onItemMoved(fromKey, toKey)

        // 在下一次绘制前修正锚点并为被挤开的邻居播平滑让位动画，保证手指下方的视图零跳动。
        val vto = container.viewTreeObserver
        vto.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (container.viewTreeObserver.isAlive) {
                    container.viewTreeObserver.removeOnPreDrawListener(this)
                }
                awaitingSwapLayout = false
                val draggedDelta = viewStart(dragged) - draggedOldStart
                if (draggingView === dragged) {
                    anchorRaw += draggedDelta.toFloat()
                    updateDraggingTranslation(dragged)
                }
                val neighborDelta = (neighborOldStart - viewStart(neighbor)).toFloat()
                if (neighborDelta != 0f) {
                    neighbor.animate().cancel()
                    setViewTranslation(neighbor, neighborDelta)
                    val anim = neighbor.animate().setDuration(SWAP_ANIM_MS)
                    when (axis) {
                        Axis.VERTICAL -> anim.translationY(0f)
                        Axis.HORIZONTAL -> anim.translationX(0f)
                    }
                    anim.start()
                }
                return true
            }
        })
    }

    private fun findSwapTargetIndex(
        container: LinearLayout,
        fromIndex: Int,
        draggedCenter: Float,
    ): Int? {
        if (fromIndex > 0) {
            val prev = container.getChildAt(fromIndex - 1)
            if (prev != null && itemKeyProvider(prev) != null) {
                val prevMid = viewStart(prev) + viewSize(prev) / 2f
                if (draggedCenter < prevMid) return fromIndex - 1
            }
        }
        if (fromIndex < container.childCount - 1) {
            val next = container.getChildAt(fromIndex + 1)
            if (next != null && itemKeyProvider(next) != null) {
                val nextMid = viewStart(next) + viewSize(next) / 2f
                if (draggedCenter > nextMid) return fromIndex + 1
            }
        }
        return null
    }

    private fun maybeStartAutoScroll(dragged: View) {
        if (autoScrolling) return
        val scroller = scrollViewProvider() ?: return
        if (computeEdgeScrollDelta(scroller) != 0) {
            autoScrolling = true
            dragged.postOnAnimation(autoScrollRunnable)
        }
    }

    private fun computeEdgeScrollDelta(scroller: View): Int {
        scroller.getLocationOnScreen(tmpLoc)
        val start = if (axis == Axis.VERTICAL) tmpLoc[1].toFloat() else tmpLoc[0].toFloat()
        val end = start + if (axis == Axis.VERTICAL) scroller.height else scroller.width
        val pos = currentRawForAxis()
        return when {
            pos < start + edgeZonePx -> -autoScrollStepPx
            pos > end - edgeZonePx -> autoScrollStepPx
            else -> 0
        }
    }

    private fun scrollByAxis(scroller: View, delta: Int): Int = when (axis) {
        Axis.VERTICAL -> {
            val sv = scroller as? ScrollView ?: return 0
            val before = sv.scrollY
            sv.scrollBy(0, delta)
            sv.scrollY - before
        }
        Axis.HORIZONTAL -> {
            val hsv = scroller as? HorizontalScrollView ?: return 0
            val before = hsv.scrollX
            hsv.scrollBy(delta, 0)
            hsv.scrollX - before
        }
    }

    private fun finishDragging(notifyIfChanged: Boolean = true) {
        val view = draggingView ?: return
        val changed = orderChanged
        draggingView = null
        draggingKey = null
        orderChanged = false
        awaitingSwapLayout = false
        autoScrolling = false
        view.removeCallbacks(autoScrollRunnable)
        (scrollViewProvider() as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)

        view.animate()
            .translationX(0f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(SWAP_ANIM_MS)
            .withEndAction {
                view.elevation = 0f
                view.translationZ = 0f
                onDragVisualStateChanged(view, false)
            }
            .start()

        if (notifyIfChanged) {
            onDragFinished(changed)
        }
    }

    private fun draggableChildCount(container: LinearLayout): Int {
        var count = 0
        for (i in 0 until container.childCount) {
            if (itemKeyProvider(container.getChildAt(i)) != null) count++
        }
        return count
    }

    private fun findDraggableChildAt(container: LinearLayout, rawX: Float, rawY: Float): View? {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (itemKeyProvider(child) == null) continue
            if (isPointInsideView(child, rawX, rawY)) return child
        }
        return null
    }

    private fun isPointInsideTaggedDescendant(
        root: View,
        rawX: Float,
        rawY: Float,
        expectedTag: String,
    ): Boolean {
        if (root.visibility != View.VISIBLE) return false
        if (root.tag == expectedTag && isPointInsideView(root, rawX, rawY)) return true
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (isPointInsideTaggedDescendant(root.getChildAt(i), rawX, rawY, expectedTag)) {
                    return true
                }
            }
        }
        return false
    }

    private fun isPointInsideView(view: View, rawX: Float, rawY: Float): Boolean {
        if (view.visibility != View.VISIBLE) return false
        view.getLocationOnScreen(tmpLoc)
        val left = tmpLoc[0].toFloat()
        val top = tmpLoc[1].toFloat()
        return rawX >= left && rawX <= left + view.width && rawY >= top && rawY <= top + view.height
    }

    private fun currentRawForAxis(): Float = if (axis == Axis.VERTICAL) lastRawY else lastRawX
    private fun viewStart(v: View): Int = if (axis == Axis.VERTICAL) v.top else v.left
    private fun viewSize(v: View): Int = if (axis == Axis.VERTICAL) v.height else v.width
    private fun viewTranslation(v: View): Float = if (axis == Axis.VERTICAL) v.translationY else v.translationX
    private fun setViewTranslation(v: View, value: Float) {
        if (axis == Axis.VERTICAL) v.translationY = value else v.translationX = value
    }
}
