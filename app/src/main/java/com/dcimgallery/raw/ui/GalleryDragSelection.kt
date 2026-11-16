package com.dcimgallery.raw.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.dcimgallery.raw.GridItem
import com.dcimgallery.raw.core.media.GallerySelectionRange
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Stable
internal class GalleryDragSelectionState(private val items: List<GridItem>) {
    private data class Active(val baseline: Set<String>, val range: GallerySelectionRange.Preview)
    private data class Pending(val baseline: Set<String>, val result: Set<String>)
    private var active by mutableStateOf<Active?>(null)
    private var pending by mutableStateOf<Pending?>(null)
    private var gesture: GallerySelectionRange? = null

    val dragging: Boolean get() = active != null

    fun uriAt(index: Int): String? = (items.getOrNull(index) as? GridItem.PhotoItem)?.photo?.uri

    private fun visibleSelection(selected: Set<String>): Set<String> =
        pending?.takeIf { it.baseline === selected }?.result ?: selected

    fun count(selected: Set<String>): Int = active?.range?.count ?: visibleSelection(selected).size

    fun isSelected(index: Int, uri: String, selected: Set<String>): Boolean {
        val preview = active ?: return uri in visibleSelection(selected)
        return if (index in preview.range.indices) preview.range.selecting else uri in preview.baseline
    }

    fun start(index: Int, selected: Set<String>) {
        val baseline = visibleSelection(selected)
        val range = GallerySelectionRange(index, baseline, ::uriAt)
        pending = null
        gesture = range
        active = Active(baseline, range.preview)
    }

    fun moveTo(index: Int) {
        val range = gesture ?: return
        range.moveTo(index)?.let { active = Active(range.initial, it) }
    }

    fun finish(selected: Set<String>): Set<String>? {
        val range = gesture ?: return null
        val result = range.complete()
        // Bridge the frame before the ViewModel's StateFlow reaches the grid.
        pending = Pending(selected, result)
        clearGesture()
        return result
    }

    fun acknowledge(selected: Set<String>) {
        val previous = pending ?: return
        if (selected !== previous.baseline) pending = null
    }

    fun clearGesture() {
        active = null
        gesture = null
    }

    fun cancel() {
        pending = null
        clearGesture()
    }
}

internal fun Modifier.galleryDragSelection(
    grid: LazyGridState,
    selection: GalleryDragSelectionState,
    selecting: Boolean,
    deleting: Boolean,
    selected: Set<String>,
    onBeginSelection: () -> Unit,
    onSelection: (Set<String>) -> Unit,
): Modifier = composed {
    val latestSelected by rememberUpdatedState(selected)
    val update by rememberUpdatedState(onSelection)
    val beginSelection by rememberUpdatedState(onBeginSelection)
    val selectionMode by rememberUpdatedState(selecting)
    val animationScope = rememberCoroutineScope()
    pointerInput(grid, selection, deleting) {
        if (deleting) return@pointerInput
        var location = Offset.Zero
        var scrollJob: Job? = null
        val maxSpeed = 750.dp.toPx()
        fun speed(): Float {
            val edge = minOf(48.dp.toPx(), size.height / 3f)
            if (edge <= 0f) return 0f
            val top = ((edge - location.y) / edge).coerceIn(0f, 1f)
            val bottom = ((location.y - size.height + edge) / edge).coerceIn(0f, 1f)
            return maxSpeed * (bottom * bottom - top * top)
        }

        fun hit(at: Offset, nearest: Boolean = false): Int? {
            val layout = grid.layoutInfo
            var bestIndex: Int? = null
            var bestDistance = Float.POSITIVE_INFINITY
            for (item in layout.visibleItemsInfo) {
                val left = item.offset.x.toFloat()
                val top = item.offset.y.toFloat()
                val right = left + item.size.width
                val bottom = top + item.size.height
                if (selection.uriAt(item.index) == null) continue
                if (at.x >= left && at.x < right && at.y >= top && at.y < bottom) return item.index
                if (nearest && bottom > layout.viewportStartOffset && top < layout.viewportEndOffset) {
                    val dx = at.x - at.x.coerceIn(left, right)
                    val dy = at.y - at.y.coerceIn(top, bottom)
                    val distance = dx * dx + dy * dy
                    if (distance < bestDistance) {
                        bestIndex = item.index
                        bestDistance = distance
                    }
                }
            }
            return bestIndex
        }

        fun move(at: Offset) {
            if (!selection.dragging) return
            location = at
            hit(at)?.let(selection::moveTo)
            if (speed() == 0f) {
                scrollJob?.cancel()
                scrollJob = null
                return
            }
            if (scrollJob?.isActive == true) return
            scrollJob = animationScope.launch {
                grid.scroll(MutatePriority.UserInput) {
                    var previous = withFrameNanos { it }
                    while (isActive && selection.dragging) {
                        val now = withFrameNanos { it }
                        val seconds = (now - previous).coerceIn(0L, 32_000_000L) / 1_000_000_000f
                        previous = now
                        val velocity = speed()
                        if (velocity == 0f) break
                        val consumed = scrollBy(velocity * seconds)
                        hit(location, nearest = true)?.let(selection::moveTo)
                        if (consumed == 0f && !(if (velocity > 0f) grid.canScrollForward else grid.canScrollBackward)) break
                    }
                }
            }
        }

        try {
            // Entering selection on long press must not restart the pointer input.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val anchor = hit(down.position) ?: return@awaitEachGesture
                val ready = if (selectionMode) awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    else awaitLongPressOrCancellation(down.id)
                if (ready != null) {
                    selection.start(anchor, latestSelected)
                    if (!selectionMode) beginSelection()
                    ready.consume()
                    move(ready.position)
                    drag(ready.id) { change ->
                        change.consume()
                        move(change.position)
                    }
                    scrollJob?.cancel()
                    scrollJob = null
                    selection.finish(latestSelected)?.let(update)
                }
            }
        } finally {
            scrollJob?.cancel()
            selection.clearGesture()
        }
    }
}
