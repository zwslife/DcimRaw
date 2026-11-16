package com.dcimgallery.raw.core.media

/** The index range shares its anchor on every move. Only newly covered or uncovered indices affect the count. */
internal class GallerySelectionRange(
    private val anchor: Int,
    val initial: Set<String>,
    private val uriAt: (Int) -> String?,
) {
    private val selecting = requireNotNull(uriAt(anchor)) !in initial
    private var covered = anchor..anchor
    private var count = initial.size + if (selecting) 1 else -1

    val preview: Preview
        get() = Preview(covered, selecting, count)

    fun moveTo(index: Int): Preview? {
        val next = minOf(anchor, index)..maxOf(anchor, index)
        if (next == covered) return null
        val direction = if (selecting) 1 else -1
        if (next.first < covered.first) adjustCount(next.first until covered.first, direction)
        if (next.first > covered.first) adjustCount(covered.first until next.first, -direction)
        if (next.last > covered.last) adjustCount((covered.last + 1)..next.last, direction)
        if (next.last < covered.last) adjustCount((next.last + 1)..covered.last, -direction)
        covered = next
        return preview
    }

    private fun adjustCount(indices: IntRange, direction: Int) {
        for (index in indices) {
            val uri = uriAt(index) ?: continue
            if ((uri in initial) != selecting) count += direction
        }
    }

    fun complete(): Set<String> = initial.toMutableSet().apply {
        for (index in covered) {
            val uri = uriAt(index) ?: continue
            if (selecting) add(uri) else remove(uri)
        }
    }

    data class Preview(val indices: IntRange, val selecting: Boolean, val count: Int)
}
