package com.dcimgallery.raw.core.tone

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** 主机回归检查：模拟慢渲染期间快速拖动，验证不积压、不并发且最终值不丢失。 */
fun main() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val finalRendered = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val frames = Collections.synchronizedList(mutableListOf<String>())
        val times = Collections.synchronizedList(mutableListOf<Long>())
        val scheduler = TonePreviewScheduler(scope, frameIntervalMillis = 30L) { value ->
            check(active.incrementAndGet() == 1) { "Concurrent preview rendering" }
            try {
                frames.add(value)
                times.add(System.nanoTime())
                if (value == "first") {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                if (value == "latest") finalRendered.complete(Unit)
            } finally {
                active.decrementAndGet()
            }
        }
        withTimeout(5000) {
            scheduler.request("first")
            firstStarted.await()
            repeat(1000) { scheduler.request("obsolete-$it") }
            scheduler.request("latest")
            releaseFirst.complete(Unit)
            finalRendered.await()
        }
        check(frames.toList() == listOf("first", "latest")) { "Backlog or lost final value: $frames" }
        check(times[1] - times[0] >= 29_000_000L) { "Preview exceeds frame cadence" }
        println("PASS: 1000 intermediate requests conflated; final value rendered; no concurrent frames; frame cadence respected")
    } finally {
        scope.cancel()
    }
}
