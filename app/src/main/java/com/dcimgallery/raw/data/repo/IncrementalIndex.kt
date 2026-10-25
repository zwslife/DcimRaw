package com.dcimgallery.raw.data.repo

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Publish changed rows as they arrive; only a successful, uncancelled scan may prune old rows. */
internal suspend fun <T> updateIndex(previous: List<T>, key: (T) -> String,
    write: suspend (List<T>) -> Unit, prune: suspend (List<String>) -> Unit,
    onProgress: (Int) -> Unit, scan: suspend (suspend (List<T>) -> Unit) -> Unit): Int {
    val old = previous.associateBy(key)
    val seen = HashSet<String>(old.size)
    scan { batch ->
        currentCoroutineContext().ensureActive()
        val changed = batch.filter { seen.add(key(it)) && old[key(it)] != it }
        if (changed.isNotEmpty()) write(changed)
        onProgress(seen.size)
    }
    currentCoroutineContext().ensureActive()
    prune(old.keys.filter { it !in seen })
    return seen.size
}
