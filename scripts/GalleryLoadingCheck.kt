package com.dcimgallery.raw.data.repo

import com.dcimgallery.raw.data.scan.ScanBatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis

private data class Row(val uri: String, val revision: Int = 0)

fun main() = runBlocking {
    val old = (0 until 50_000).map { Row("photo-$it") }
    val expected = old.drop(100).mapIndexed { i, row -> if (i < 50) row.copy(revision = 1) else row } +
        (50_000 until 50_500).map { Row("photo-$it") }
    val store = old.associateBy { it.uri }.toMutableMap()
    var writes = 0; var firstBatch = 0; var traversed = 0
    val elapsed = measureTimeMillis {
        val count = updateIndex(old, Row::uri, write = { batch ->
            if (firstBatch == 0) { firstBatch = traversed; check(traversed < expected.size) }
            writes += batch.size; batch.forEach { store[it.uri] = it }
        }, prune = { keys -> keys.forEach(store::remove) }, onProgress = {}) { publish ->
            val batches = ScanBatcher(publish)
            expected.forEach { traversed++; batches.add(it) }
            batches.flush()
        }
        check(count == expected.size && store == expected.associateBy { it.uri })
        check(firstBatch == 32 && writes == 550) { "Wrote $writes rows; first publish after $firstBatch" }
    }
    var repeatedWrites = 0; var deletes = 0
    updateIndex(expected, Row::uri, { repeatedWrites += it.size }, { deletes += it.size }, {}) { publish ->
        expected.chunked(512).forEach { publish(it) }
    }
    check(repeatedWrites == 0 && deletes == 0)
    for (cancelled in listOf(false, true)) {
        var pruned = false; var published = false
        val result = runCatching {
            updateIndex(old, Row::uri, { published = true }, { pruned = true }, {}) { publish ->
                publish(listOf(Row("new")))
                if (cancelled) throw CancellationException("cancelled scan") else error("unavailable subtree")
            }
        }
        check(result.isFailure && published && !pruned) { "Failed/partial scans must retain old records" }
    }
    val batches = mutableListOf<List<Int>>()
    val small = ScanBatcher<Int> { batches += it }
    repeat(7) { small.add(it) }; small.flush(); small.flush()
    check(batches.single() == (0..6).toList())
    val combined = mutableMapOf("local-stale" to Row("local-stale"), "otg-offline" to Row("otg-offline"))
    updateIndex(listOf(combined.getValue("local-stale")), Row::uri,
        { batch -> batch.forEach { combined[it.uri] = it } }, { keys -> keys.forEach(combined::remove) }, {}) { publish ->
        publish(listOf(Row("local-new")))
    }
    check(combined.keys == setOf("otg-offline", "local-new")) {
        "Local album reconciliation must remove deleted local rows without pruning an unavailable OTG source"
    }
    println("PASS: 50,400 photos; first publish at 32; only 550 changed rows written; unchanged refresh writes 0; failure/cancellation never prune; $elapsed ms host reconciliation")
}
