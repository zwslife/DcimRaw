package com.dcimgallery.raw.core.tone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 一次只渲染一帧，期间到来的请求只保留最新一项；停手后仍会渲染最终值。 */
internal class TonePreviewScheduler(
    scope: CoroutineScope,
    private val frameIntervalMillis: Long = 16L,
    render: suspend (String) -> Unit,
) {
    private val requests = Channel<String>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.Default) {
            try {
                for (uri in requests) {
                    val startedAt = System.nanoTime()
                    render(uri)
                    val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L
                    val remaining = frameIntervalMillis - elapsedMillis
                    if (remaining > 0) delay(remaining)
                }
            } finally {
                requests.close()
            }
        }
    }

    fun request(uri: String) {
        requests.trySend(uri)
    }
}
