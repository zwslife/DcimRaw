package com.dcimgallery.raw.core.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val targets = List(450) { DeleteTarget("local-$it", true) } +
        listOf(DeleteTarget("otg-a", false), DeleteTarget("otg-b", false), DeleteTarget("local-0", true))
    val plan = deletionPlan(targets)
    check(plan.map { it.uris.size } == listOf(200, 200, 50, 1, 1))
    val remaining = targets.map { it.uri }.toMutableSet()
    val complete = executeDeletion(plan, { DeleteAttempt(it.uris.toSet()) }) { remaining.removeAll(it) }
    check(complete.deleted.size == 452 && complete.failed.isEmpty() && !complete.cancelled && remaining.isEmpty())

    var requests = 0
    val removed = mutableSetOf<String>()
    val cancelled = executeDeletion(plan, {
        requests++
        if (requests == 1) DeleteAttempt(it.uris.toSet()) else DeleteAttempt(emptySet(), cancelled = true)
    }) { removed += it }
    check(requests == 2 && cancelled.cancelled && removed == plan.first().uris.toSet())
    check(cancelled.deleted.size == 200 && "otg-a" !in removed)

    val deniedRemoved = mutableSetOf<String>()
    val denied = executeDeletion(plan, { DeleteAttempt(emptySet(), true) }) { deniedRemoved += it }
    check(denied.cancelled && denied.deleted.isEmpty() && deniedRemoved.isEmpty())

    val direct = deletionPlan(listOf(DeleteTarget("a", false), DeleteTarget("b", false), DeleteTarget("c", false)))
    val partial = executeDeletion(direct, { batch ->
        DeleteAttempt(if (batch.uris.single() == "b") emptySet() else batch.uris.toSet() + "not-requested")
    }) { check("not-requested" !in it) }
    check(partial.deleted == setOf("a", "c") && partial.failed == setOf("b"))

    var cancelledCleanup = false
    check(runCatching {
        executeDeletion(direct, { throw CancellationException("job cancelled") }) { cancelledCleanup = true }
    }.isFailure && !cancelledCleanup)
    check(executeDeletion(emptyList(), { error("No request should be made") }, {}).deleted.isEmpty())
    println("PASS: bounded mixed-source batches; duplicate targets; single/direct removal; partial failures; denied/cancelled authorization never removes unconfirmed rows; coroutine cancellation; empty selection")
}
