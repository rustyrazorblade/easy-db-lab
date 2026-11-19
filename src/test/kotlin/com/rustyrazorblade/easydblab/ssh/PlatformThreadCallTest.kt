package com.rustyrazorblade.easydblab.ssh

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PlatformThreadCallTest {
    @Test
    fun `a virtual caller's block runs on a platform thread`() {
        val ranOnVirtual = CompletableFuture<Boolean>()
        Thread.ofVirtual().start { ranOnVirtual.complete(PlatformThreadCall.run { Thread.currentThread().isVirtual }) }

        assertThat(ranOnVirtual.get(5, TimeUnit.SECONDS)).isFalse()
    }

    @Test
    fun `a platform caller's block runs on the caller's own thread`() {
        val caller = Thread.currentThread()

        assertThat(PlatformThreadCall.run { Thread.currentThread() }).isSameAs(caller)
    }

    /** MINA reports a failed command as a checked exception; the caller must see it as itself, not wrapped. */
    @Test
    fun `the block's checked exception reaches a virtual caller unwrapped`() {
        val thrown = CompletableFuture<Throwable>()
        Thread.ofVirtual().start {
            try {
                PlatformThreadCall.run { throw IOException("channel closed") }
            } catch (e: IOException) {
                thrown.complete(e)
            }
        }

        assertThat(thrown.get(5, TimeUnit.SECONDS)).isInstanceOf(IOException::class.java).hasMessage("channel closed")
    }

    /** An abandoned attempt is stopped by its interrupt, so the interrupt must reach the blocked call. */
    @Test
    fun `an interrupt of the virtual caller reaches the block, and the caller keeps its flag`() {
        val blocking = CountDownLatch(1)
        val blockSawInterrupt = CompletableFuture<Boolean>()
        val callerFlag = CompletableFuture<Boolean>()
        val caller =
            Thread.ofVirtual().start {
                PlatformThreadCall.run {
                    blocking.countDown()
                    try {
                        Thread.sleep(10_000)
                        blockSawInterrupt.complete(false)
                    } catch (_: InterruptedException) {
                        blockSawInterrupt.complete(true)
                    }
                }
                callerFlag.complete(Thread.currentThread().isInterrupted)
            }
        blocking.await()
        caller.interrupt()

        assertThat(blockSawInterrupt.get(5, TimeUnit.SECONDS)).isTrue()
        assertThat(callerFlag.get(5, TimeUnit.SECONDS)).isTrue()
    }
}
