package org.itantra.tts.output

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioChunkQueueTest {

    @Test
    fun `put then poll returns the same chunk and updates bufferedSamples`() {
        val queue = AudioChunkQueue(capacity = 4)
        assertEquals(0, queue.bufferedSamples)
        queue.put(floatArrayOf(1f, 2f, 3f))
        assertEquals(3, queue.bufferedSamples)
        val out = queue.poll()
        assertEquals(listOf(1f, 2f, 3f), out?.toList())
        assertEquals(0, queue.bufferedSamples)
    }

    @Test
    fun `empty chunk is a no-op`() {
        val queue = AudioChunkQueue()
        queue.put(FloatArray(0))
        assertEquals(0, queue.bufferedSamples)
        assertNull(queue.poll())
    }

    @Test
    fun `bufferedSamples accumulates across multiple chunks`() {
        val queue = AudioChunkQueue(capacity = 8)
        queue.put(floatArrayOf(1f, 2f))
        queue.put(floatArrayOf(3f, 4f, 5f))
        assertEquals(5, queue.bufferedSamples)
        queue.poll()
        assertEquals(3, queue.bufferedSamples)
    }

    @Test
    fun `poll with timeout returns null when nothing is buffered`() {
        val queue = AudioChunkQueue()
        val start = System.nanoTime()
        val out = queue.poll(30)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertNull(out)
        assertTrue("expected to wait roughly the timeout, took ${elapsedMs}ms", elapsedMs >= 25)
    }

    @Test
    fun `producer done is observable and sticky`() {
        val queue = AudioChunkQueue()
        assertFalse(queue.isProducerDone)
        queue.markProducerDone()
        assertTrue(queue.isProducerDone)
        assertFalse(queue.didProducerFail)
    }

    @Test
    fun `producer failed implies producer done`() {
        val queue = AudioChunkQueue()
        queue.markProducerFailed()
        assertTrue(queue.isProducerDone)
        assertTrue(queue.didProducerFail)
    }

    @Test
    fun `clear drops buffered chunks and resets bufferedSamples`() {
        val queue = AudioChunkQueue(capacity = 4)
        queue.put(floatArrayOf(1f, 2f, 3f, 4f))
        queue.clear()
        assertEquals(0, queue.bufferedSamples)
        assertNull(queue.poll())
    }

    @Test
    fun `clear unblocks a producer stuck on a full queue`() {
        val queue = AudioChunkQueue(capacity = 1)
        queue.put(floatArrayOf(1f)) // fills the single slot
        val putReturned = CountDownLatch(1)
        val producer = Thread {
            queue.put(floatArrayOf(2f)) // blocks until a slot frees up
            putReturned.countDown()
        }
        producer.start()
        // Give the producer thread a moment to actually reach the blocking put() call.
        Thread.sleep(50)
        assertFalse("put() should still be blocked", putReturned.await(0, TimeUnit.MILLISECONDS))

        queue.clear()

        assertTrue("clear() should free space and unblock put()", putReturned.await(2, TimeUnit.SECONDS))
        producer.join(2000)
    }

    @Test
    fun `capacity backpressure blocks put until a slot is consumed`() {
        val queue = AudioChunkQueue(capacity = 1)
        queue.put(floatArrayOf(1f))
        val putReturned = CountDownLatch(1)
        val producer = Thread {
            queue.put(floatArrayOf(2f))
            putReturned.countDown()
        }
        producer.start()
        Thread.sleep(50)
        assertFalse(putReturned.await(0, TimeUnit.MILLISECONDS))

        queue.poll() // frees the one slot

        assertTrue(putReturned.await(2, TimeUnit.SECONDS))
        producer.join(2000)
    }
}
