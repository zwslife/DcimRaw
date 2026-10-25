package com.dcimgallery.raw.core.media

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class DeleteTarget(val uri: String, val systemManaged: Boolean)
data class DeleteBatch(val uris: List<String>, val systemManaged: Boolean)
data class DeleteAttempt(val deleted: Set<String>, val cancelled: Boolean = false)
data class DeleteReport(val deleted: Set<String>, val failed: Set<String>, val cancelled: Boolean)

/** Bound platform requests; direct SAF/legacy operations stay independent for partial failures. */
fun deletionPlan(targets: List<DeleteTarget>): List<DeleteBatch> {
    val unique = targets.distinctBy { it.uri }
    return unique.filter { it.systemManaged }.map { it.uri }.chunked(200).map { DeleteBatch(it, true) } +
        unique.filterNot { it.systemManaged }.map { DeleteBatch(listOf(it.uri), false) }
}

suspend fun executeDeletion(plan: List<DeleteBatch>, execute: suspend (DeleteBatch) -> DeleteAttempt,
    onDeleted: suspend (Set<String>) -> Unit): DeleteReport {
    val deleted = linkedSetOf<String>()
    val failed = linkedSetOf<String>()
    for (batch in plan) {
        currentCoroutineContext().ensureActive()
        val result = execute(batch)
        // An adapter may only remove rows belonging to the actual confirmed request.
        val verified = result.deleted.intersect(batch.uris.toSet())
        if (verified.isNotEmpty()) { onDeleted(verified); deleted += verified }
        if (result.cancelled) return DeleteReport(deleted, failed, true)
        failed += batch.uris.filterNot { it in verified }
    }
    return DeleteReport(deleted, failed, false)
}
