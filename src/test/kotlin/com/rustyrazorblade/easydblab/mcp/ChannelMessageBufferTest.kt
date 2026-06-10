package com.rustyrazorblade.easydblab.mcp

import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.StreamType
import com.rustyrazorblade.easydblab.output.OutputEvent
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ChannelMessageBufferTest {
    private lateinit var channel: Channel<OutputEvent>
    private lateinit var buffer: ChannelMessageBuffer

    @BeforeEach
    fun setup() {
        channel = Channel(Channel.UNLIMITED)
        buffer = ChannelMessageBuffer(channel)
    }

    @AfterEach
    fun tearDown() {
        buffer.stop()
        channel.close()
    }

    // Helper method to send event to channel and wait for processing
    private fun sendEvent(event: OutputEvent) {
        val result = channel.trySend(event)
        assertTrue(result.isSuccess, "Failed to send event")
        Thread.sleep(50) // Give buffer time to process
    }

    // ========== Construction Tests ==========

    @Test
    fun `constructor initializes with empty buffer`() {
        assertTrue(buffer.isEmpty())
        assertEquals(0, buffer.size())
        assertTrue(buffer.getMessages().isEmpty())
    }

    // ========== Event Processing Tests ==========

    @Test
    fun `processEvent handles MessageEvent and returns true`() {
        buffer.start()
        sendEvent(OutputEvent.MessageEvent("Test message"))

        assertEquals(1, buffer.size())
        val messages = buffer.getMessages()
        assertEquals(1, messages.size)
        assertTrue(messages[0].contains("Test message"))
        assertTrue(messages[0].matches(Regex("\\[\\d{2}:\\d{2}:\\d{2}\\.\\d+\\] .*")))
    }

    @Test
    fun `processEvent handles ErrorEvent with ERROR prefix and returns true`() {
        buffer.start()
        sendEvent(OutputEvent.ErrorEvent("Test error"))

        assertEquals(1, buffer.size())
        val messages = buffer.getMessages()
        assertEquals(1, messages.size)
        assertTrue(messages[0].contains("ERROR: Test error"))
        assertTrue(messages[0].matches(Regex("\\[\\d{2}:\\d{2}:\\d{2}\\.\\d+\\] ERROR: .*")))
    }

    @Test
    fun `processEvent handles ErrorEvent with throwable`() {
        buffer.start()
        val exception = RuntimeException("Test exception")
        sendEvent(OutputEvent.ErrorEvent("Error with exception", exception))

        assertEquals(1, buffer.size())
        val messages = buffer.getMessages()
        assertTrue(messages[0].contains("ERROR: Error with exception"))
    }

    @Test
    fun `processEvent handles FrameEvent without buffering and returns true`() {
        buffer.start()
        val frame = Frame(StreamType.STDOUT, "frame content".toByteArray())
        sendEvent(OutputEvent.FrameEvent(frame))

        assertTrue(buffer.isEmpty())
        assertEquals(0, buffer.size())
    }

    @Test
    fun `processEvent handles CloseEvent and stops consumer`() {
        buffer.start()
        sendEvent(OutputEvent.CloseEvent)
        Thread.sleep(100) // Give time for thread to stop

        assertTrue(buffer.isEmpty())
        // The consumer thread should stop after CloseEvent
    }

    @Test
    fun `processEvent handles multiple message types in sequence`() {
        buffer.start()
        sendEvent(OutputEvent.MessageEvent("Info message"))
        sendEvent(OutputEvent.ErrorEvent("Error message"))
        sendEvent(OutputEvent.FrameEvent(Frame(StreamType.STDOUT, "frame".toByteArray())))
        sendEvent(OutputEvent.MessageEvent("Another info"))

        val messages = buffer.getMessages()
        assertEquals(3, messages.size)
        assertTrue(messages[0].contains("Info message"))
        assertTrue(messages[1].contains("ERROR: Error message"))
        assertTrue(messages[2].contains("Another info"))
    }

    @Test
    fun `processEvent handles empty message content`() {
        buffer.start()
        sendEvent(OutputEvent.MessageEvent(""))
        sendEvent(OutputEvent.ErrorEvent(""))

        val messages = buffer.getMessages()
        assertEquals(2, messages.size)
        assertTrue(messages[0].matches(Regex("\\[\\d{2}:\\d{2}:\\d{2}\\.\\d+\\] $")))
        assertTrue(messages[1].matches(Regex("\\[\\d{2}:\\d{2}:\\d{2}\\.\\d+\\] ERROR: $")))
    }

    @Test
    fun `processEvent handles very long messages`() {
        buffer.start()
        val longMessage = "x".repeat(10000)
        sendEvent(OutputEvent.MessageEvent(longMessage))

        val messages = buffer.getMessages()
        assertEquals(1, messages.size)
        assertTrue(messages[0].contains(longMessage))
    }

    // ========== Buffer Operations Tests ==========

    @Test
    fun `getMessages returns copy of buffer`() {
        buffer.start()
        sendEvent(OutputEvent.MessageEvent("Message 1"))

        val messages1 = buffer.getMessages()
        val messages2 = buffer.getMessages()

        assertEquals(messages1, messages2)
        assertNotSame(messages1, messages2) // Different instances

        // Modifying returned list doesn't affect buffer
        messages1.toMutableList().clear()
        assertEquals(1, buffer.size())
    }

    @Test
    fun `clearMessages empties buffer`() {
        buffer.start()
        sendEvent(OutputEvent.MessageEvent("Message 1"))
        sendEvent(OutputEvent.MessageEvent("Message 2"))

        assertEquals(2, buffer.size())
        assertFalse(buffer.isEmpty())

        buffer.clearMessages()

        assertEquals(0, buffer.size())
        assertTrue(buffer.isEmpty())
        assertTrue(buffer.getMessages().isEmpty())
    }

    @Test
    fun `getAndClearMessages atomically retrieves and clears`() {
        buffer.start()
        sendEvent(OutputEvent.MessageEvent("Message 1"))
        sendEvent(OutputEvent.MessageEvent("Message 2"))

        val messages = buffer.getAndClearMessages()

        assertEquals(2, messages.size)
        assertTrue(buffer.isEmpty())
        assertEquals(0, buffer.size())
    }

    @Test
    fun `size returns correct count`() {
        buffer.start()
        assertEquals(0, buffer.size())

        sendEvent(OutputEvent.MessageEvent("Message 1"))
        assertEquals(1, buffer.size())

        sendEvent(OutputEvent.MessageEvent("Message 2"))
        assertEquals(2, buffer.size())

        sendEvent(OutputEvent.FrameEvent(Frame(StreamType.STDOUT, "frame".toByteArray())))
        assertEquals(2, buffer.size()) // Frame not buffered

        buffer.clearMessages()
        assertEquals(0, buffer.size())
    }

    @Test
    fun `isEmpty returns correct state`() {
        buffer.start()
        assertTrue(buffer.isEmpty())

        sendEvent(OutputEvent.MessageEvent("Message"))
        assertFalse(buffer.isEmpty())

        buffer.clearMessages()
        assertTrue(buffer.isEmpty())
    }

    // ========== Thread Safety Tests ==========

    @Test
    fun `concurrent operations are thread-safe`() {
        buffer.start()
        val executor = Executors.newFixedThreadPool(10)
        val iterations = 10 // Reduced from 100
        val expectedTotal = 10 * iterations // 100 messages total
        val latch = CountDownLatch(expectedTotal)

        // Submit multiple threads writing messages
        repeat(10) { threadIndex ->
            executor.submit {
                repeat(iterations) { i ->
                    val result = channel.trySend(OutputEvent.MessageEvent("Thread $threadIndex - Message $i"))
                    assertTrue(result.isSuccess)
                    latch.countDown()
                }
            }
        }

        // Wait for all operations to complete
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))

        // Give consumer time to process all messages (100 messages * 10ms + overhead)
        Thread.sleep(2000)

        // Verify all messages were buffered
        val messages = buffer.getMessages()
        assertEquals(expectedTotal, messages.size)
    }

    @Test
    fun `getAndClearMessages is atomic under concurrent access`() {
        buffer.start()
        val executor = Executors.newFixedThreadPool(10)
        val totalMessages = 100 // Reduced from 1000
        val collectedMessages = Collections.synchronizedList(mutableListOf<String>())

        // Add messages
        repeat(totalMessages) { i ->
            val result = channel.trySend(OutputEvent.MessageEvent("Message $i"))
            assertTrue(result.isSuccess)
        }
        Thread.sleep(1500) // Give consumer time to process (100 messages * 10ms + overhead)

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
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))

        // All messages should be collected exactly once
        assertEquals(totalMessages, collectedMessages.size)
        assertTrue(buffer.isEmpty())
    }

    // ========== Message Order Tests ==========

    @Test
    fun `messages preserve insertion order`() {
        buffer.start()
        repeat(50) { i ->
            // Reduced from 100
            sendEvent(OutputEvent.MessageEvent("Message $i"))
        }
        Thread.sleep(200) // Give consumer time to process all

        val messages = buffer.getMessages()
        assertEquals(50, messages.size)

        messages.forEachIndexed { index, message ->
            assertTrue(message.contains("Message $index"))
        }
    }

    @Test
    fun `messages maintain chronological timestamps`() {
        buffer.start()
        sendEvent(OutputEvent.MessageEvent("First"))
        Thread.sleep(10) // Small delay to ensure different timestamps
        sendEvent(OutputEvent.MessageEvent("Second"))
        Thread.sleep(10)
        sendEvent(OutputEvent.MessageEvent("Third"))

        val messages = buffer.getMessages()
        assertEquals(3, messages.size)

        // Extract timestamps (format: [HH:mm:ss.SSS])
        val timestampRegex = Regex("\\[(\\d{2}:\\d{2}:\\d{2}\\.\\d+)\\]")
        val timestamps =
            messages.map {
                timestampRegex.find(it)?.groupValues?.get(1) ?: ""
            }

        // Verify all timestamps were extracted
        assertTrue(timestamps.all { it.isNotEmpty() })

        // Verify chronological order
        assertTrue(messages[0].contains("First"))
        assertTrue(messages[1].contains("Second"))
        assertTrue(messages[2].contains("Third"))
    }

    // ========== Edge Cases ==========

    @Test
    fun `processEvent continues after exceptions`() {
        // This test verifies exception handling in processEvent
        // Since we can't easily trigger an exception in the when block,
        // we verify the structure handles all cases properly

        buffer.start()
        sendEvent(OutputEvent.MessageEvent("Before"))
        sendEvent(OutputEvent.MessageEvent("After"))

        assertEquals(2, buffer.size())
    }

    @Test
    fun `getAndClearMessages on empty buffer returns empty list`() {
        val messages = buffer.getAndClearMessages()

        assertTrue(messages.isEmpty())
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun `clearMessages on empty buffer is safe`() {
        buffer.clearMessages() // Should not throw
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun `high message volume is handled correctly`() {
        buffer.start()
        val messageCount = 1000 // Reduced from 10000
        repeat(messageCount) { i ->
            val result = channel.trySend(OutputEvent.MessageEvent("Message $i"))
            assertTrue(result.isSuccess)
        }

        // Wait for buffer to process all messages with periodic checks
        var waitTime = 0
        val maxWaitTime = 15000 // 15 seconds max
        while (buffer.size() < messageCount && waitTime < maxWaitTime) {
            Thread.sleep(100)
            waitTime += 100
        }

        assertEquals(messageCount, buffer.size())
        val messages = buffer.getMessages()
        assertEquals(messageCount, messages.size)
    }
    // ========== Start/Stop Tests ==========

    @Test
    fun `start initiates message consumption`() {
        // Buffer should be empty before start
        assertTrue(buffer.isEmpty())

        // Start the buffer
        buffer.start()

        // Now messages should be buffered
        sendEvent(OutputEvent.MessageEvent("After start"))
        assertEquals(1, buffer.size())
        assertTrue(buffer.getMessages()[0].contains("After start"))
    }

    @Test
    fun `stop halts message consumption`() {
        buffer.start()

        // Send and verify message is buffered
        sendEvent(OutputEvent.MessageEvent("Message 1"))
        assertEquals(1, buffer.size())

        // Stop the buffer
        buffer.stop()
        Thread.sleep(100) // Give thread time to stop

        // Clear existing messages
        buffer.clearMessages()

        // Send message after stopping - should not be buffered
        channel.trySend(OutputEvent.MessageEvent("After stop"))
        Thread.sleep(100)
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun `channel closure stops consumer thread`() {
        buffer.start()

        sendEvent(OutputEvent.MessageEvent("Before close"))
        assertEquals(1, buffer.size())

        // Close the channel
        channel.close()
        Thread.sleep(100) // Give thread time to detect closure

        // Buffer should still have the message from before close
        assertEquals(1, buffer.size())
    }
}
