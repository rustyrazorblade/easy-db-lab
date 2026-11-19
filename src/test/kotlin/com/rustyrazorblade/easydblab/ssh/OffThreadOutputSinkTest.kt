package com.rustyrazorblade.easydblab.ssh

import com.rustyrazorblade.easydblab.output.NodeStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OffThreadOutputSinkTest {
    private val delivered: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    @Test
    fun `a line is handed over on another thread, in order, and all of it before close returns`() {
        val writer = Thread.currentThread().name
        val sink = OffThreadOutputSink { _, text -> delivered += Thread.currentThread().name to text }

        (1..200).forEach { sink.line(NodeStream.Stdout, "line $it") }
        sink.close()

        assertThat(delivered.map { it.second }).containsExactlyElementsOf((1..200).map { "line $it" })
        assertThat(delivered.map { it.first }).noneMatch { it == writer }
    }

    @Test
    fun `a sink that blocks never blocks the writer`() {
        val release = CountDownLatch(1)
        val sink = OffThreadOutputSink { _, text -> release.await(WAIT_SECONDS, TimeUnit.SECONDS).also { delivered += "" to text } }

        val queued = Thread.ofVirtual().start { (1..50).forEach { sink.line(NodeStream.Stderr, "line $it") } }

        assertThat(queued.join(java.time.Duration.ofSeconds(WAIT_SECONDS))).isTrue()
        release.countDown()
        sink.close()
        assertThat(delivered).hasSize(50)
    }

    @Test
    fun `a sink that throws does not reach the writer, and the next line is still delivered`() {
        val sink =
            OffThreadOutputSink { _, text ->
                check(text != "bad") { "console closed" }
                delivered += "" to text
            }

        assertThatCode {
            sink.line(NodeStream.Stdout, "bad")
            sink.line(NodeStream.Stdout, "good")
            sink.close()
        }.doesNotThrowAnyException()
        assertThat(delivered.map { it.second }).containsExactly("good")
    }

    /** An abandoned worker is interrupted while its command runs; its output must still all print. */
    @Test
    fun `an interrupted writer still queues every line, close waits for all of them, and the interrupt is kept`() {
        val release = CountDownLatch(1)
        val sink = OffThreadOutputSink { _, text -> release.await(WAIT_SECONDS, TimeUnit.SECONDS).also { delivered += "" to text } }
        var stillInterrupted = false

        val writer =
            Thread.ofVirtual().start {
                Thread.currentThread().interrupt()
                (1..20).forEach { sink.line(NodeStream.Stdout, "line $it") }
                Thread.ofVirtual().start { Thread.sleep(SETTLE_MILLIS).also { release.countDown() } }
                sink.close()
                stillInterrupted = Thread.currentThread().isInterrupted
            }

        assertThat(writer.join(java.time.Duration.ofSeconds(WAIT_SECONDS))).isTrue()
        assertThat(delivered.map { it.second }).containsExactlyElementsOf((1..20).map { "line $it" })
        assertThat(stillInterrupted).isTrue()
    }

    /** A stalled console (a paused terminal) must not hold an abandoned worker forever. */
    @Test
    fun `an interrupted writer stops waiting for a sink that never returns`() {
        val stalled = CountDownLatch(1)
        val reported = CountDownLatch(1)
        val stalling =
            object : RemoteOutputSink {
                override fun line(
                    stream: NodeStream,
                    text: String,
                ) = stalled.await()

                override fun stalled() = reported.countDown()
            }
        val sink = OffThreadOutputSink(java.time.Duration.ofMillis(SETTLE_MILLIS), stalling)
        var stillInterrupted = false

        val writer =
            Thread.ofVirtual().start {
                sink.line(NodeStream.Stdout, "stuck")
                Thread.currentThread().interrupt()
                sink.close()
                stillInterrupted = Thread.currentThread().isInterrupted
            }

        assertThat(writer.join(java.time.Duration.ofSeconds(WAIT_SECONDS))).isTrue()
        assertThat(stillInterrupted).isTrue()
        // The reader is told the output is still coming, so a short tail is not taken as the whole story.
        assertThat(reported.count).isZero()
        stalled.countDown()
    }

    private companion object {
        const val SETTLE_MILLIS = 100L
        const val WAIT_SECONDS = 5L
    }
}
