package com.dcimgallery.raw.data.scan

/** Publish a first screen quickly, then use larger transactions for the remaining index. */
internal class ScanBatcher<T>(private val publish: suspend (List<T>) -> Unit) {
    private val pending = ArrayList<T>(512)
    private var first = true
    suspend fun add(item: T) {
        pending += item
        if (pending.size >= if (first) 32 else 512) flush()
    }
    suspend fun flush() {
        if (pending.isEmpty()) return
        publish(pending.toList())
        pending.clear()
        first = false
    }
}
