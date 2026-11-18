package app.aaps.pump.insight.utils

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

/** Review finding 10: a blocked socket write must not wedge the callers (they hold the connection monitor). */
class OutputStreamWriterTest {

    private class BlockingStream : OutputStream() {

        val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        @Volatile var closed = false
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            entered.countDown()
            released.await() // a dead Bluetooth socket: blocks until the socket is closed
            throw IOException("socket closed")
        }
        override fun close() { closed = true; released.countDown() }
    }

    private val noCallback = object : OutputStreamWriter.Callback { override fun onErrorWhileWriting(e: Exception) {} }

    @Test fun blockedWriteDoesNotBlockWriteCallersAndCloseUnblocksIt() {
        val stream = BlockingStream()
        val w = OutputStreamWriter(stream, noCallback).also { it.start() }
        w.write(byteArrayOf(1, 2, 3))
        assertThat(stream.entered.await(2, TimeUnit.SECONDS)).isTrue() // writer thread is now stuck inside write()
        val t0 = System.nanoTime()
        repeat(50) { w.write(ByteArray(10)) } // would hang forever when the stuck write held the queue lock
        val callerMs = (System.nanoTime() - t0) / 1_000_000
        assertThat(callerMs).isLessThan(500)
        w.close() // from "another thread" (the timer) without any lock: unblocks the stuck write
        w.join(2000)
        assertThat(w.isAlive).isFalse()
        assertThat(stream.closed).isTrue()
    }

    @Test fun writeAndWaitGivesUpAfterItsTimeoutOnABlockedSocket() {
        val stream = BlockingStream()
        val w = OutputStreamWriter(stream, noCallback).also { it.start() }
        val t0 = System.nanoTime()
        w.writeAndWait(byteArrayOf(9))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertThat(ms).isAtLeast(1500)
        assertThat(ms).isLessThan(3500)
        w.close()
        w.join(2000)
    }

    @Test fun normalWritesArriveInOrderAndWriteAndWaitReturnsWhenFlushed() {
        val out = ByteArrayOutputStream()
        val w = OutputStreamWriter(out, noCallback).also { it.start() }
        w.write(byteArrayOf(1, 2))
        w.writeAndWait(byteArrayOf(3))
        assertThat(synchronized(out) { out.toByteArray() }).isEqualTo(byteArrayOf(1, 2, 3))
        w.close()
    }

    @Test fun ioErrorIsReportedOnce() {
        val reported = java.util.concurrent.atomic.AtomicInteger()
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException("broken pipe")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("broken pipe")
        }
        val w = OutputStreamWriter(failing, object : OutputStreamWriter.Callback {
            override fun onErrorWhileWriting(e: Exception) { reported.incrementAndGet() }
        }).also { it.start() }
        w.write(byteArrayOf(1))
        w.join(2000)
        assertThat(reported.get()).isEqualTo(1)
    }
}
