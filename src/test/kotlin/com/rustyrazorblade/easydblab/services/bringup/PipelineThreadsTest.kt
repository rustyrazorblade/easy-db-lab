package com.rustyrazorblade.easydblab.services.bringup

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** [PipelineThreads.close] waits for every pipeline thread, but never longer than its grace. */
class PipelineThreadsTest {
    private val reported = CopyOnWriteArrayList<List<String>>()

    @Test
    fun `close waits for a thread that takes a moment to end, and reports nothing`() {
        val threads = PipelineThreads(Duration.ofSeconds(10), reported::add)
        val finished = AtomicBoolean(false)
        threads
            .newThread {
                Thread.sleep(STOP_TAKES_MS)
                finished.set(true)
            }.start()

        threads.close()

        assertThat(finished).isTrue()
        assertThat(reported).isEmpty()
    }

    @Test
    fun `a thread still running after the grace is reported by name and close returns`() {
        val threads = PipelineThreads(Duration.ofMillis(GRACE_MS), reported::add)
        val release = AtomicBoolean(false)
        val started = CountDownLatch(1)
        val stuck =
            threads.newThread {
                started.countDown()
                // Ignores its interrupt, as a call outside a step may.
                while (!release.get()) Thread.onSpinWait()
            }
        stuck.name = "db7"
        stuck.start()
        threads.newThread {}.also { it.name = "db8" }.start()
        started.await()

        try {
            val began = System.nanoTime()
            threads.close()
            val waited = Duration.ofNanos(System.nanoTime() - began)

            assertThat(reported).containsExactly(listOf("db7"))
            assertThat(waited).isLessThan(Duration.ofSeconds(5))
        } finally {
            release.set(true)
            stuck.join()
        }
    }

    @Test
    fun `an interrupt does not cut the wait short, and the flag is kept`() {
        val threads = PipelineThreads(Duration.ofSeconds(10), reported::add)
        val finished = AtomicBoolean(false)
        threads
            .newThread {
                Thread.sleep(STOP_TAKES_MS)
                finished.set(true)
            }.start()
        var flagKept = false
        val closer =
            Thread.ofVirtual().start {
                Thread.currentThread().interrupt()
                threads.close()
                flagKept = Thread.currentThread().isInterrupted
            }

        closer.join()

        assertThat(finished).isTrue()
        assertThat(flagKept).isTrue()
        assertThat(reported).isEmpty()
    }

    private companion object {
        const val STOP_TAKES_MS = 200L
        const val GRACE_MS = 200L
    }
}
