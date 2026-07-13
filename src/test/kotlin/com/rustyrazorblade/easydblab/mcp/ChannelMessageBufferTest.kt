package com.rustyrazorblade.easydblab.mcp

import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.StreamType
import com.rustyrazorblade.easydblab.output.OutputEvent
import kotlinx.coroutines.channels.Channel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tests for [ChannelMessageBuffer].
 *
 * The buffer runs a background consumer that drains [channel] asynchronously, so these tests
 * synchronize on observable buffer state (via [awaitBufferSize] / [awaitUntil]) rather than fixed
 * `Thread.sleep` calls. Polling a condition removes the wall-clock cost of worst-case sleeps while
 * being strictly more reliable than a fixed pause.
 */
class ChannelMessageBufferTest {
    private lateinit var channel: Channel<OutputEvent>
    private lateinit var buffer: ChannelMessageBuffer

    companion object {
        private const val TIMESTAMP_PREFIX = "\\[\\d{2}:\\d{2}:\\d{2}\\.\\d+\\] "

        // Failure ceiling only: awaitUntil returns as soon as the condition holds. Generous so a
        // loaded CI machine never flakes; the consumer (zero poll interval here) drains in ms.
        private val AWAIT_TIMEOUT = Duration.ofSeconds(10)
        private const val POLL_INTERVAL_MS = 5L
    }

    @BeforeEach
    fun setup() {
        channel = Channel(Channel.UNLIMITED)
        // Zero poll interval so the consumer drains without the production per-message throttle;
        // only throughput changes, not the buffering behavior under test.
        buffer = ChannelMessageBuffer(channel, pollInterval = Duration.ZERO)
    }

    @AfterEach
    fun tearDown() {
        buffer.stop()
        channel.close()
    }

    /** Sends an event to the channel, asserting the send succeeded. */
    private fun send(event: OutputEvent) {
        assertThat(channel.trySend(event).isSuccess)
            .describedAs("send $event to channel")
            .isTrue()
    }

    /** Polls [condition] on a short interval until it holds or the timeout elapses. */
    private fun awaitUntil(
        description: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos()
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        assertThat(condition()).describedAs(description).isTrue()
    }

    /** Waits until the buffer has exactly [expected] messages. */
    private fun awaitBufferSize(expected: Int) = awaitUntil("buffer size reaches $expected") { buffer.size() == expected }

    // ========== Construction Tests ==========

    @Test
    fun `constructor initializes with empty buffer`() {
        assertThat(buffer.isEmpty()).isTrue()
        assertThat(buffer.size()).isZero()
        assertThat(buffer.getMessages()).isEmpty()
    }

    // ========== Event Processing Tests ==========

    @Test
    fun `processEvent handles MessageEvent and returns true`() {
        buffer.start()
        send(OutputEvent.MessageEvent("Test message"))
        awaitBufferSize(1)

        val messages = buffer.getMessages()
        assertThat(messages).hasSize(1)
        assertThat(messages[0]).contains("Test message")
        assertThat(messages[0]).matches("$TIMESTAMP_PREFIX.*")
    }

    @Test
    fun `processEvent handles ErrorEvent with ERROR prefix and returns true`() {
        buffer.start()
        send(OutputEvent.ErrorEvent("Test error"))
        awaitBufferSize(1)

        val messages = buffer.getMessages()
        assertThat(messages).hasSize(1)
        assertThat(messages[0]).contains("ERROR: Test error")
        assertThat(messages[0]).matches("${TIMESTAMP_PREFIX}ERROR: .*")
    }

    @Test
    fun `processEvent handles ErrorEvent with throwable`() {
        buffer.start()
        val exception = RuntimeException("Test exception")
        send(OutputEvent.ErrorEvent("Error with exception", exception))
        awaitBufferSize(1)

        assertThat(buffer.getMessages()[0]).contains("ERROR: Error with exception")
    }

    @Test
    fun `processEvent handles FrameEvent without buffering and returns true`() {
        buffer.start()
        val frame = Frame(StreamType.STDOUT, "frame content".toByteArray())
        send(OutputEvent.FrameEvent(frame))
        // The consumer processes the channel FIFO, so once the sentinel is buffered the frame ahead
        // of it has already been processed. A frame that contributed nothing leaves size at 1.
        send(OutputEvent.MessageEvent("sentinel"))
        awaitBufferSize(1)

        val messages = buffer.getMessages()
        assertThat(messages).hasSize(1)
        assertThat(messages[0]).contains("sentinel")
    }

    @Test
    fun `processEvent handles CloseEvent without buffering`() {
        buffer.start()
        send(OutputEvent.CloseEvent)
        // CloseEvent is not buffered; the following sentinel proves the event was consumed and
        // added nothing to the buffer.
        send(OutputEvent.MessageEvent("sentinel"))
        awaitBufferSize(1)

        assertThat(buffer.getMessages()[0]).contains("sentinel")
    }

    @Test
    fun `processEvent handles multiple message types in sequence`() {
        buffer.start()
        send(OutputEvent.MessageEvent("Info message"))
        send(OutputEvent.ErrorEvent("Error message"))
        send(OutputEvent.FrameEvent(Frame(StreamType.STDOUT, "frame".toByteArray())))
        send(OutputEvent.MessageEvent("Another info"))
        awaitBufferSize(3)

        val messages = buffer.getMessages()
        assertThat(messages[0]).contains("Info message")
        assertThat(messages[1]).contains("ERROR: Error message")
        assertThat(messages[2]).contains("Another info")
    }

    @Test
    fun `processEvent handles empty message content`() {
        buffer.start()
        send(OutputEvent.MessageEvent(""))
        send(OutputEvent.ErrorEvent(""))
        awaitBufferSize(2)

        val messages = buffer.getMessages()
        assertThat(messages[0]).matches("$TIMESTAMP_PREFIX$")
        assertThat(messages[1]).matches("${TIMESTAMP_PREFIX}ERROR: $")
    }

    @Test
    fun `processEvent handles very long messages`() {
        buffer.start()
        val longMessage = "x".repeat(10000)
        send(OutputEvent.MessageEvent(longMessage))
        awaitBufferSize(1)

        assertThat(buffer.getMessages()[0]).contains(longMessage)
    }

    // ========== Buffer Operations Tests ==========

    @Test
    fun `getMessages returns copy of buffer`() {
        buffer.start()
        send(OutputEvent.MessageEvent("Message 1"))
        awaitBufferSize(1)

        val messages1 = buffer.getMessages()
        val messages2 = buffer.getMessages()

        assertThat(messages1).isEqualTo(messages2)
        assertThat(messages1).isNotSameAs(messages2)

        // The returned list is an independent copy, so the buffer is unaffected by consumers of it.
        assertThat(buffer.size()).isEqualTo(1)
    }

    @Test
    fun `clearMessages empties buffer`() {
        buffer.start()
        send(OutputEvent.MessageEvent("Message 1"))
        send(OutputEvent.MessageEvent("Message 2"))
        awaitBufferSize(2)

        assertThat(buffer.isEmpty()).isFalse()

        buffer.clearMessages()

        assertThat(buffer.size()).isZero()
        assertThat(buffer.isEmpty()).isTrue()
        assertThat(buffer.getMessages()).isEmpty()
    }

    @Test
    fun `getAndClearMessages atomically retrieves and clears`() {
        buffer.start()
        send(OutputEvent.MessageEvent("Message 1"))
        send(OutputEvent.MessageEvent("Message 2"))
        awaitBufferSize(2)

        val messages = buffer.getAndClearMessages()

        assertThat(messages).hasSize(2)
        assertThat(buffer.isEmpty()).isTrue()
        assertThat(buffer.size()).isZero()
    }

    @Test
    fun `size returns correct count`() {
        buffer.start()
        assertThat(buffer.size()).isZero()

        send(OutputEvent.MessageEvent("Message 1"))
        awaitBufferSize(1)

        send(OutputEvent.MessageEvent("Message 2"))
        awaitBufferSize(2)

        // Frame is not buffered: after the sentinel is buffered (size 3), the frame ahead of it in
        // the FIFO has already been processed and added nothing.
        send(OutputEvent.FrameEvent(Frame(StreamType.STDOUT, "frame".toByteArray())))
        send(OutputEvent.MessageEvent("sentinel"))
        awaitBufferSize(3)

        buffer.clearMessages()
        assertThat(buffer.size()).isZero()
    }

    @Test
    fun `isEmpty returns correct state`() {
        buffer.start()
        assertThat(buffer.isEmpty()).isTrue()

        send(OutputEvent.MessageEvent("Message"))
        awaitBufferSize(1)
        assertThat(buffer.isEmpty()).isFalse()

        buffer.clearMessages()
        assertThat(buffer.isEmpty()).isTrue()
    }

    // ========== Thread Safety Tests ==========

    @Test
    fun `concurrent operations are thread-safe`() {
        buffer.start()
        val executor = Executors.newFixedThreadPool(10)
        val iterations = 10
        val expectedTotal = 10 * iterations
        val latch = CountDownLatch(expectedTotal)

        // Submit multiple threads writing messages
        repeat(10) { threadIndex ->
            executor.submit {
                repeat(iterations) { i ->
                    assertThat(channel.trySend(OutputEvent.MessageEvent("Thread $threadIndex - Message $i")).isSuccess).isTrue()
                    latch.countDown()
                }
            }
        }

        // Wait for all sends to complete
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue()
        executor.shutdown()
        assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue()

        // Wait for the consumer to buffer every message
        awaitBufferSize(expectedTotal)
        assertThat(buffer.getMessages()).hasSize(expectedTotal)
    }

    @Test
    fun `getAndClearMessages is atomic under concurrent access`() {
        buffer.start()
        val executor = Executors.newFixedThreadPool(10)
        val totalMessages = 100
        val collectedMessages = Collections.synchronizedList(mutableListOf<String>())

        // Add messages
        repeat(totalMessages) { i ->
            send(OutputEvent.MessageEvent("Message $i"))
        }
        awaitBufferSize(totalMessages)

        // Multiple threads trying to get and clear
        val futures =
            List(10) {
                executor.submit {
                    val messages = buffer.getAndClearMessages()
                    collectedMessages.addAll(messages)
                }
            }

        // Wait for all threads
        futures.forEach { it.get() }
        executor.shutdown()
        assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue()

        // All messages should be collected exactly once
        assertThat(collectedMessages).hasSize(totalMessages)
        assertThat(buffer.isEmpty()).isTrue()
    }

    // ========== Message Order Tests ==========

    @Test
    fun `messages preserve insertion order`() {
        buffer.start()
        repeat(50) { i ->
            send(OutputEvent.MessageEvent("Message $i"))
        }
        awaitBufferSize(50)

        val messages = buffer.getMessages()
        messages.forEachIndexed { index, message ->
            assertThat(message).contains("Message $index")
        }
    }

    @Test
    fun `messages maintain chronological timestamps`() {
        buffer.start()
        send(OutputEvent.MessageEvent("First"))
        send(OutputEvent.MessageEvent("Second"))
        send(OutputEvent.MessageEvent("Third"))
        awaitBufferSize(3)

        val messages = buffer.getMessages()

        // Every message carries a timestamp prefix
        val timestampRegex = Regex("\\[(\\d{2}:\\d{2}:\\d{2}\\.\\d+)\\]")
        assertThat(messages).allMatch { timestampRegex.containsMatchIn(it) }

        // FIFO insertion order is preserved
        assertThat(messages[0]).contains("First")
        assertThat(messages[1]).contains("Second")
        assertThat(messages[2]).contains("Third")
    }

    // ========== Edge Cases ==========

    @Test
    fun `processEvent continues after exceptions`() {
        buffer.start()
        send(OutputEvent.MessageEvent("Before"))
        send(OutputEvent.MessageEvent("After"))
        awaitBufferSize(2)

        assertThat(buffer.size()).isEqualTo(2)
    }

    @Test
    fun `getAndClearMessages on empty buffer returns empty list`() {
        val messages = buffer.getAndClearMessages()

        assertThat(messages).isEmpty()
        assertThat(buffer.isEmpty()).isTrue()
    }

    @Test
    fun `clearMessages on empty buffer is safe`() {
        buffer.clearMessages() // Should not throw
        assertThat(buffer.isEmpty()).isTrue()
    }

    @Test
    fun `high message volume is handled correctly`() {
        buffer.start()
        val messageCount = 1000
        repeat(messageCount) { i ->
            send(OutputEvent.MessageEvent("Message $i"))
        }

        awaitBufferSize(messageCount)
        assertThat(buffer.getMessages()).hasSize(messageCount)
    }

    // ========== Start/Stop Tests ==========

    @Test
    fun `start initiates message consumption`() {
        assertThat(buffer.isEmpty()).isTrue()

        buffer.start()

        send(OutputEvent.MessageEvent("After start"))
        awaitBufferSize(1)
        assertThat(buffer.getMessages()[0]).contains("After start")
    }

    @Test
    fun `stop halts message consumption`() {
        buffer.start()

        send(OutputEvent.MessageEvent("Message 1"))
        awaitBufferSize(1)

        // stop() joins the consumer thread, so once it returns nothing will drain the channel.
        buffer.stop()
        buffer.clearMessages()

        send(OutputEvent.MessageEvent("After stop"))
        assertThat(buffer.isEmpty()).isTrue()
    }

    @Test
    fun `channel closure retains already-buffered messages`() {
        buffer.start()

        send(OutputEvent.MessageEvent("Before close"))
        awaitBufferSize(1)

        // Closing the channel stops new events from arriving but does not clear the buffer.
        channel.close()

        assertThat(buffer.size()).isEqualTo(1)
    }
}
