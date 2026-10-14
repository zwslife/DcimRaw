package com.dcimgallery.raw.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp

/**
 * 大图页手势状态机（仿微信图片预览）。
 *
 * - 双指捏合：以触点为中心缩放（1x..5x），可平移，边界钳制
 * - 双击：2.5x 放大（落点居中）/ 复原，200ms 动画
 * - scale=1 时垂直拖动：下滑关闭（位移 + 背景淡出 + 图片微缩），
 *   松手超过 20% 屏高则关闭，否则回弹
 *
 * 变换模型：graphicsLayer + transformOrigin(0,0)，
 * 即 screenPoint = contentPoint * scale + offset。
 */
class PagerGestureState {

    var scale by mutableFloatStateOf(1f); private set
    var offset by mutableStateOf(Offset.Zero); private set
    var dragY by mutableFloatStateOf(0f); private set
    var viewSize by mutableStateOf(IntSize.Zero)
    var isTouching by mutableStateOf(false); private set
    var isFlinging by mutableStateOf(false); private set

    private var scope: CoroutineScope? = null
    private var flingJob: Job? = null
    private var transformJob: Job? = null
    private var closeJob: Job? = null
    private var flingGeneration = 0

    fun attach(scope: CoroutineScope) {
        this.scope = scope
    }

    val isZoomed: Boolean get() = scale > 1.02f

    fun onTouchStart() {
        stopFling()
        transformJob?.cancel()
        closeJob?.cancel()
        isTouching = true
    }

    fun onTouchEnd() {
        isTouching = false
    }

    private fun stopFling() {
        flingGeneration++
        flingJob?.cancel()
        flingJob = null
        isFlinging = false
    }

    /** 按松手速度继续平移，逐帧减速；到达图片边界即停止对应方向。 */
    fun fling(velocity: Offset) {
        if (!isZoomed || velocity.getDistance() < 120f) return
        stopFling()
        val activeScope = scope ?: return
        val generation = flingGeneration
        isFlinging = true
        flingJob = activeScope.launch {
            try {
                var vx = velocity.x.coerceIn(-6000f, 6000f)
                var vy = velocity.y.coerceIn(-6000f, 6000f)
                var previous = withFrameNanos { it }
                while (abs(vx) > 12f || abs(vy) > 12f) {
                    val now = withFrameNanos { it }
                    val seconds = ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    previous = now
                    val decay = exp(-5.5f * seconds)
                    val desired = offset + Offset(vx, vy) * ((1f - decay) / 5.5f)
                    val actual = clamped(scale, desired)
                    offset = actual
                    vx = if (abs(actual.x - desired.x) > 0.5f) 0f else vx * decay
                    vy = if (abs(actual.y - desired.y) > 0.5f) 0f else vy * decay
                }
            } finally {
                if (generation == flingGeneration) isFlinging = false
            }
        }
    }

    /** detectTransformGestures 回调。zoom≠1 为捏合；已缩放时 pan 为平移。 */
    fun onTransform(centroid: Offset, pan: Offset, zoom: Float) {
        stopFling()
        when {
            zoom != 1f -> {
                val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                val realZoom = newScale / scale
                scale = newScale
                offset = Offset(
                    centroid.x - (centroid.x - offset.x) * realZoom + pan.x,
                    centroid.y - (centroid.y - offset.y) * realZoom + pan.y,
                )
                clampOffset()
            }
            isZoomed -> {
                offset += pan
                clampOffset()
            }
        }
    }

    /** 未缩放时的单指垂直拖动（下滑关闭），由外层垂直手势探测器驱动。 */
    fun onCloseDrag(amount: Float) {
        if (!isZoomed) dragY += amount * DRAG_FRICTION
    }

    /** 全部手指抬起时调用；返回 true 表示达到关闭阈值。 */
    fun onGestureEnd(): Boolean {
        if (!isZoomed && viewSize.height > 0 && abs(dragY) > viewSize.height * CLOSE_THRESHOLD) {
            return true
        }
        if (dragY != 0f) {
            closeJob?.cancel()
            closeJob = scope?.launch { animateDragY(0f) }
        }
        return false
    }

    fun onDoubleTap(point: Offset) {
        stopFling()
        transformJob?.cancel()
        transformJob = scope?.launch {
            if (isZoomed) animateReset() else animateZoomTo(DOUBLE_TAP_SCALE, point)
        }
    }

    /** 翻页时重置。 */
    fun reset() {
        stopFling()
        transformJob?.cancel()
        closeJob?.cancel()
        isTouching = false
        scale = 1f
        offset = Offset.Zero
        dragY = 0f
    }

    // ---- 内部 ----

    private fun clampOffset() {
        offset = clamped(scale, offset)
    }

    /** 以 transformOrigin(0,0) 推导：offset 合法区间为 [view*(1-scale), 0]。 */
    private fun clamped(s: Float, off: Offset): Offset {
        val w = viewSize.width.toFloat()
        val h = viewSize.height.toFloat()
        if (w <= 0f || h <= 0f) return off
        return Offset(
            off.x.coerceIn(w * (1f - s), 0f),
            off.y.coerceIn(h * (1f - s), 0f),
        )
    }

    private suspend fun animateZoomTo(target: Float, focus: Offset) {
        val startScale = scale
        val startOffset = offset
        val realZoom = target / startScale
        val endOffset = clamped(
            target,
            Offset(
                focus.x - (focus.x - startOffset.x) * realZoom,
                focus.y - (focus.y - startOffset.y) * realZoom,
            ),
        )
        animate(0f, 1f, animationSpec = tween(200)) { t, _ ->
            scale = startScale + (target - startScale) * t
            offset = Offset(
                startOffset.x + (endOffset.x - startOffset.x) * t,
                startOffset.y + (endOffset.y - startOffset.y) * t,
            )
        }
        clampOffset()
    }

    private suspend fun animateReset() {
        val startScale = scale
        val startOffset = offset
        animate(0f, 1f, animationSpec = tween(200)) { t, _ ->
            scale = startScale + (1f - startScale) * t
            offset = Offset(startOffset.x * (1 - t), startOffset.y * (1 - t))
        }
        scale = 1f
        offset = Offset.Zero
    }

    private suspend fun animateDragY(target: Float) {
        val start = dragY
        animate(0f, 1f, animationSpec = tween(160)) { t, _ ->
            dragY = start + (target - start) * t
        }
    }

    private companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2.5f
        const val CLOSE_THRESHOLD = 0.20f
        const val DRAG_FRICTION = 0.85f
    }
}
