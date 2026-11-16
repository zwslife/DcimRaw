package com.dcimgallery.raw.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.memory.MemoryCache
import com.dcimgallery.raw.GridItem
import com.dcimgallery.raw.core.coil.GalleryThumbnail
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Composable
internal fun PrefetchGalleryThumbnails(state: LazyGridState, items: List<GridItem>, edge: Int) {
    val context = LocalContext.current
    val loader = context.imageLoader
    LaunchedEffect(state, items, edge, loader) {
        val jobs = mutableMapOf<Int, Job>()
        val slots = Semaphore(2)
        var previous = -1
        var forward = true
        snapshotFlow {
            val visible = state.layoutInfo.visibleItemsInfo
            val first = visible.minOfOrNull { it.index } ?: return@snapshotFlow IntRange.EMPTY
            first..visible.maxOf { it.index }
        }.collect { visible ->
            if (visible.isEmpty()) {
                jobs.values.forEach { it.cancel() }
                jobs.clear()
                return@collect
            }
            if (visible.first != previous) forward = visible.first > previous
            previous = visible.first
            // Skip date headers and favor five rows in the scroll direction, one on the other side.
            val before = (visible.first - 1 downTo 0).asSequence().filter { items.getOrNull(it) is GridItem.PhotoItem }
            val after = (visible.last + 1 until items.size).asSequence().filter { items[it] is GridItem.PhotoItem }
            val near = if (forward) after.take(20) + before.take(4) else before.take(20) + after.take(4)
            val wanted = near.toSet()
            jobs.entries.removeAll { (index, job) ->
                val nearby = index in wanted || index in visible
                if (!nearby) job.cancel()
                !nearby || job.isCompleted
            }
            for (index in wanted) {
                if (index in jobs) continue
                val photo = (items[index] as GridItem.PhotoItem).photo
                val thumbnail = GalleryThumbnail(photo, edge)
                if (loader.memoryCache?.get(MemoryCache.Key(thumbnail.cacheKey)) != null) continue
                jobs[index] = launch {
                    slots.withPermit {
                        loader.execute(thumbnail.request(context))
                    }
                }
            }
        }
    }
}
