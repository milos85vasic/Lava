package lava.tracker.kinozal.magnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Concurrency + semantics tests for [KinozalMagnetCache].
 *
 * The cache is the §6.E Capability-Honesty path that makes the SYNCHRONOUS
 * `getMagnetLink` return a real magnet once a topic view has populated it. It
 * is `@Singleton`, so the topic feature (writer) and the download feature
 * (reader) — potentially on different coroutines/threads — share one instance.
 * These tests pin the user-visible contract: an honest `null` on a miss, a
 * read-after-write that returns the EXACT stored magnet, overwrite semantics,
 * blank-input rejection, and thread-safety under concurrent writers (a plain
 * `HashMap` would corrupt or lose writes here).
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *   Replace the backing `ConcurrentHashMap` in [KinozalMagnetCache] with a plain
 *   `HashMap`. Re-run `concurrentWritesFromManyThreads_loseNoEntry`: it then
 *   FAILS (either a thrown `ConcurrentModificationException`/`NullPointerException`
 *   captured in `errors`, or `cache.get(...)` returning null for a key a thread
 *   provably wrote → "lost write for key ..."). Revert; re-run; green.
 */
class KinozalMagnetCacheTest {

    private fun magnetFor(id: Int): String =
        "magnet:?xt=urn:btih:%040x".format(id) + "&dn=topic-$id"

    @Test
    fun missReturnsNull() {
        val cache = KinozalMagnetCache()
        assertNull("an id never put must return null", cache.get("never-seen"))
    }

    @Test
    fun putThenGetReturnsExactValue() {
        val cache = KinozalMagnetCache()
        val magnet = magnetFor(1)
        cache.put("topic-1", magnet)
        assertEquals(magnet, cache.get("topic-1"))
    }

    @Test
    fun overwriteUpdatesValue() {
        val cache = KinozalMagnetCache()
        cache.put("topic-1", magnetFor(1))
        cache.put("topic-1", magnetFor(2))
        assertEquals("overwrite must replace the prior value", magnetFor(2), cache.get("topic-1"))
    }

    @Test
    fun blankInputsAreIgnored() {
        val cache = KinozalMagnetCache()
        cache.put("", magnetFor(1)) // blank id ignored
        cache.put("topic-1", "") // blank magnet ignored
        cache.put("   ", magnetFor(2)) // whitespace id ignored
        assertNull(cache.get(""))
        assertNull(cache.get("topic-1"))
        assertNull(cache.get("   "))
    }

    @Test
    fun sharedInstance_writesByOneClientAreVisibleToAnother() {
        // @Singleton clone-shared semantics: two references to the ONE cache
        // (writer feature + reader feature) see each other's writes.
        val shared = KinozalMagnetCache()
        val writerView = shared
        val readerView = shared
        val magnet = magnetFor(42)

        writerView.put("topic-42", magnet)

        assertEquals(
            "a reader sharing the singleton must observe the writer's entry",
            magnet,
            readerView.get("topic-42"),
        )
    }

    @Test
    fun concurrentWritesFromManyThreads_loseNoEntry() {
        val cache = KinozalMagnetCache()
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
                        val id = t * perThread + i
                        cache.put("topic-$id", magnetFor(id))
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertTrue("threads did not finish in time", done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        assertTrue("concurrent puts threw: ${errors.joinToString { it.toString() }}", errors.isEmpty())
        // Every key written by every thread must be retrievable with its exact value.
        for (id in 0 until threads * perThread) {
            assertEquals("lost write for key topic-$id", magnetFor(id), cache.get("topic-$id"))
        }
    }

    @Test
    fun concurrentPutGetSameKey_isThreadSafeAndFinalValueIsOneWritten() {
        val cache = KinozalMagnetCache()
        val threads = 12
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val written = (0 until threads).map { magnetFor(it) }.toSet()

        repeat(threads) { t ->
            pool.execute {
                try {
                    start.await()
                    repeat(1000) {
                        cache.put("hot-key", magnetFor(t))
                        cache.get("hot-key") // concurrent read while others write
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertTrue("threads did not finish in time", done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        assertTrue("concurrent put/get threw: ${errors.joinToString { it.toString() }}", errors.isEmpty())
        val finalValue = cache.get("hot-key")
        assertTrue("final value '$finalValue' must be one a thread actually wrote", finalValue in written)
    }
}
