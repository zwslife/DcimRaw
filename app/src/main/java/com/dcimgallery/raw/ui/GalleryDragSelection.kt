package com.dcimgallery.raw.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.dcimgallery.raw.GridItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A gesture uses a URI snapshot; moving back restores the selection before the drag. */
internal fun Modifier.galleryDragSelection(grid: LazyGridState, items: List<GridItem>, selecting: Boolean,
    deleting: Boolean, selected: Set<String>, onSelection: (Set<String>) -> Unit): Modifier = composed {
    val latestSelected by rememberUpdatedState(selected)
    val update by rememberUpdatedState(onSelection)
    val selectionMode by rememberUpdatedState(selecting)
    pointerInput(grid, items, deleting) {
        if (deleting) return@pointerInput
        coroutineScope {
            var anchor = -1
            var lastEnd = -1
            var initial = emptySet<String>()
            var choose = true
            var location = Offset.Zero
            var scrollJob: Job? = null
            val edge = 48.dp.toPx()
            fun hit(at: Offset): Int? = grid.layoutInfo.visibleItemsInfo.firstOrNull {
                at.x >= it.offset.x && at.x < it.offset.x + it.size.width &&
                    at.y >= it.offset.y && at.y < it.offset.y + it.size.height &&
                    items.getOrNull(it.index) is GridItem.PhotoItem
            }?.index
            fun applyAt(at: Offset) {
                if (anchor < 0) return
                val end = hit(at) ?: return
                if (end == lastEnd) return
                lastEnd = end
                val range = (minOf(anchor, end)..maxOf(anchor, end)).mapNotNull {
                    (items.getOrNull(it) as? GridItem.PhotoItem)?.photo?.uri
                }.toSet()
                update(if (choose) initial + range else initial - range)
            }
            val start: (Offset) -> Unit = { at ->
                location = at
                anchor = hit(at) ?: -1
                lastEnd = -1
                initial = latestSelected
                val uri = (items.getOrNull(anchor) as? GridItem.PhotoItem)?.photo?.uri
                choose = uri !in initial
                applyAt(at)
                if (anchor >= 0) scrollJob = launch {
                    while (true) {
                        val speed = when {
                            location.y < edge -> -((edge - location.y) / edge).coerceIn(0f, 1f)
                            location.y > size.height - edge -> ((location.y - size.height + edge) / edge).coerceIn(0f, 1f)
                            else -> 0f
                        }
                        if (speed != 0f) { grid.scrollBy(speed * 12.dp.toPx()); applyAt(location) }
                        delay(16)
                    }
                }
            }
            val finish: () -> Unit = { scrollJob?.cancel(); scrollJob = null; anchor = -1 }
            try {
                // Long press preserves ordinary gallery scrolling; selection mode also accepts direct drags.
                // Do not key on selecting: entering selection mid-gesture must not cancel this detector.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val ready = if (selectionMode) awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        else awaitLongPressOrCancellation(down.id)
                    if (ready != null) {
                        start(down.position)
                        if (anchor >= 0) {
                            ready.consume(); location = ready.position; applyAt(location)
                            drag(ready.id) { change -> change.consume(); location = change.position; applyAt(location) }
                        }
                    }
                    finish()
                }
            } finally { finish() }
        }
    }
}
