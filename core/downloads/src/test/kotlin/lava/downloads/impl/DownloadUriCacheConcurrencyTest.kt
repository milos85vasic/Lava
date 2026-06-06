package lava.downloads.impl

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reproducing test for the completeness Phase 3 leak/race sweep finding:
 * [DownloadUriCache] is read on the caller's coroutine dispatcher and
 * written from the `DownloadManager` `BroadcastReceiver.onReceive` (which
 * Android delivers on the MAIN thread), so concurrent downloads touch the
 * map from two threads. The cache MUST be thread-safe.
 *
 * Primary assertion: after N threads each cache M distinct id→uri pairs
 * AND read back concurrently, every one of the N*M entries is present and
 * holds its exact value — i.e. no write was lost, no read corrupted, and
 * no exception escaped a worker thread.
 *
 * Falsifiability (rehearsed): change `ConcurrentHashMap` to `HashMap` in
 * DownloadUriCache and this test fails — either with a corrupted final
 * size (lost writes) or a worker recording a ConcurrentModificationException
 * / NullPointerException from HashMap's unsynchronized resize.
 */
class DownloadUriCacheConcurrencyTest {

    @Test
    fun `concurrent put and get keep every entry intact`() {
        val cache = DownloadUriCache()
        val threads = 16
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val errors = ConcurrentLinkedQueue<Throwable>()

        repeat(threads) { t ->
            pool.execute {
                try {
                    start.await()
                    for (i in 0 until perThread) {
                        val id = "t$t-i$i"
                        val uri = "file:///downloads/$id.torrent"
                        cache.put(id, uri)
                        // Interleave reads against concurrent writers to
                        // exercise the read/write race the receiver triggers.
                        cache.get("t${(t + 1) % threads}-i$i")
                    }
                } catch (e: Throwable) {
                    errors += e
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        check(done.await(30, TimeUnit.SECONDS)) { "workers did not finish in time" }
        pool.shutdownNow()

        assertEquals("no worker thread may throw", emptyList<Throwable>(), errors.toList())
        // Every distinct key written by every thread must be present + exact.
        for (t in 0 until threads) {
            for (i in 0 until perThread) {
                val id = "t$t-i$i"
                assertEquals("file:///downloads/$id.torrent", cache.get(id))
            }
        }
    }

    @Test
    fun `get returns null for an uncached id`() {
        assertEquals(null, DownloadUriCache().get("never-downloaded"))
    }

    @Test
    fun `put then get round-trips the exact uri`() {
        val cache = DownloadUriCache()
        cache.put("abc", "file:///downloads/abc.torrent")
        assertEquals("file:///downloads/abc.torrent", cache.get("abc"))
    }
}
