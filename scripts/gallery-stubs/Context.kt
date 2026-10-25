package android.content

import android.net.Uri
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class Context(val contentResolver: TestResolver)
class TestResolver {
    val files = ConcurrentHashMap<String, ByteArray>()
    val opens = AtomicInteger()
    val active = AtomicInteger()
    val peak = AtomicInteger()
    var blockedUri: String? = null
    var entered = CountDownLatch(1)
    var release = CountDownLatch(1)
    fun openInputStream(uri: Uri): ByteArrayInputStream {
        opens.incrementAndGet()
        val bytes = files[uri.value] ?: error("file unavailable")
        return object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val running = active.incrementAndGet()
                peak.accumulateAndGet(running, ::maxOf)
                try {
                    if (uri.value == blockedUri) { entered.countDown(); release.await() }
                    Thread.sleep(1)
                    return super.read(buffer, offset, length)
                } finally { active.decrementAndGet() }
            }
        }
    }
}
