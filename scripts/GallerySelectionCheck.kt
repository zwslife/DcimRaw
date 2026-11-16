package com.dcimgallery.raw.core.media

fun main() {
    val items = List(50_000) { if (it % 12 == 0) null else "photo-$it" }
    val initial = items.filterIndexed { index, _ -> index % 3 == 0 }.filterNotNull().toSet()
    val anchor = 6_001
    val endpoints = (anchor..45_000).toList() + (45_000 downTo 2_000) + listOf(4_800, 37_020, anchor)

    for (baseline in listOf(initial, initial + requireNotNull(items[anchor]))) {
        var reads = 0
        val selection = GallerySelectionRange(anchor, baseline) { index -> reads++; items[index] }
        val adding = items[anchor] !in baseline
        val prefix = IntArray(items.size + 1)
        for ((index, uri) in items.withIndex()) {
            prefix[index + 1] = prefix[index] + if (uri != null && (uri in baseline) != adding) 1 else 0
        }
        var previous = anchor
        for (endpoint in endpoints) {
            val before = reads
            selection.moveTo(endpoint)
            val range = minOf(anchor, endpoint)..maxOf(anchor, endpoint)
            val expectedDelta = prefix[range.last + 1] - prefix[range.first]
            check(selection.preview == GallerySelectionRange.Preview(range, adding,
                baseline.size + if (adding) expectedDelta else -expectedDelta))
            check(reads - before <= kotlin.math.abs(endpoint - previous)) {
                "Drag recalculated indices outside the changed range"
            }
            previous = endpoint
        }
        val expected = if (adding) baseline + requireNotNull(items[anchor])
            else baseline - requireNotNull(items[anchor])
        check(selection.complete() == expected)

        // Jump across dates and the anchor, then commit a long range.
        selection.moveTo(45_000)
        val affected = items.subList(anchor, 45_001).filterNotNull().toSet()
        check(selection.complete() == if (adding) baseline + affected else baseline - affected)
    }
    println("PASS: 50,000 gallery entries, forward and reverse selection, date headers, count and bounded range updates")
}
