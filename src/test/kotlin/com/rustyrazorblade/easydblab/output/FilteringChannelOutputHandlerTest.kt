package com.rustyrazorblade.easydblab.output

import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.StreamType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FilteringChannelOutputHandlerTest {
    @Test
    fun `should drop frame events and not send them to channel`() =
        runBlocking {
            val channel = Channel<OutputEvent>(Channel.UNLIMITED)
            val handler = FilteringChannelOutputHandler(channel)

            // Send some frame events
            repeat(50) {
                handler.handleFrame(Frame(StreamType.STDOUT, "output $it".toByteArray()))
            }

            // Channel should be empty since frames are dropped
            assertTrue(channel.isEmpty, "Channel should be empty since frames are dropped")
        }

    @Test
    fun `should send docker activity message every 100 frames`() =
        runBlocking {
            val channel = Channel<OutputEvent>(Channel.UNLIMITED)
            val handler = FilteringChannelOutputHandler(channel)

            // Send 150 frame events
            repeat(150) {
                handler.handleFrame(Frame(StreamType.STDOUT, "output $it".toByteArray()))
            }

            // Should have 1 docker activity message (after 100 frames)
            val events = mutableListOf<OutputEvent>()
            while (!channel.isEmpty) {
                val event = channel.tryReceive().getOrNull()
                if (event != null) {
                    events.add(event)
                } else {
                    break
                }
            }

            assertEquals(1, events.size, "Should have exactly 1 docker activity message")
            assertTrue(events[0] is OutputEvent.MessageEvent, "Event should be MessageEvent")

            val message = (events[0] as OutputEvent.MessageEvent).message
            assertTrue(message.contains("docker", ignoreCase = true), "Message should mention docker")
            assertTrue(message.contains("100"), "Message should mention frame count")
        }

    @Test
    fun `should send docker activity message every 100 frames with multiple batches`() =
        runBlocking {
            val channel = Channel<OutputEvent>(Channel.UNLIMITED)
            val handler = FilteringChannelOutputHandler(channel)

            // Send 250 frame events (should trigger 2 activity messages)
            repeat(250) {
                handler.handleFrame(Frame(StreamType.STDOUT, "output $it".toByteArray()))
            }

            val events = mutableListOf<OutputEvent>()
            while (!channel.isEmpty) {
                val event = channel.tryReceive().getOrNull()
                if (event != null) {
                    events.add(event)
                } else {
                    break
                }
            }

            assertEquals(2, events.size, "Should have exactly 2 docker activity messages")
            events.forEach { event ->
                assertTrue(event is OutputEvent.MessageEvent, "All events should be MessageEvent")
                val message = (event as OutputEvent.MessageEvent).message
                assertTrue(message.contains("docker", ignoreCase = true), "Message should mention docker")
            }

            // First message should mention 100 frames, second should mention 200
            val firstMessage = (events[0] as OutputEvent.MessageEvent).message
            val secondMessage = (events[1] as OutputEvent.MessageEvent).message
            assertTrue(firstMessage.contains("100"), "First message should mention 100 frames")
            assertTrue(secondMessage.contains("200"), "Second message should mention 200 frames")
        }

    @Test
    fun `should pass through MessageEvent unchanged`() =
        runBlocking {
            val channel = Channel<OutputEvent>(Channel.UNLIMITED)
            val handler = FilteringChannelOutputHandler(channel)

            val testMessage = "test message"
            handler.handleMessage(testMessage)

            val event = channel.tryReceive().getOrNull()
            assertNotNull(event, "Should have received an event")
            assertTrue(event is OutputEvent.MessageEvent, "Event should be MessageEvent")
            assertEquals(
                testMessage,
                (event as OutputEvent.MessageEvent).message,
                "Message content should be unchanged",
            )
        }

    @Test
    fun `should pass through ErrorEvent unchanged`() =
        runBlocking {
            val channel = Channel<OutputEvent>(Channel.UNLIMITED)
            val handler = FilteringChannelOutputHandler(channel)

            val testError = "test error"
            val testException = RuntimeException("test exception")
            handler.handleError(testError, testException)

            val event = channel.tryReceive().getOrNull()
            assertNotNull(event, "Should have received an event")
            assertTrue(event is OutputEvent.ErrorEvent, "Event should be ErrorEvent")

            val errorEvent = event as OutputEvent.ErrorEvent
            assertEquals(testError, errorEvent.message, "Error message should be unchanged")
            assertEquals(testException, errorEvent.throwable, "Exception should be unchanged")
        }

    @Test
    fun `should pass through ErrorEvent with null throwable`() =
        runBlocking {
            val channel = Channel<OutputEvent>(Channel.UNLIMITED)
            val handler = FilteringChannelOutputHandler(channel)

            val testError = "test error"
            handler.handleError(testError, null)

            val event = channel.tryReceive().getOrNull()
            assertNotNull(event, "Should have received an event")
            assertTrue(event is OutputEvent.ErrorEvent, "Event should be ErrorEvent")

            val errorEvent = event as OutputEvent.ErrorEvent
            assertEquals(testError, errorEvent.message, "Error message should be unchanged")
            assertNull(errorEvent.throwable, "Throwable should be null")
        }

    @Test
    fun `should send CloseEvent when close is called`() =
        runBlocking {
            val channel = Channel<OutputEvent>(Channel.UNLIMITED)
            val handler = FilteringChannelOutputHandler(channel)

            handler.close()

            val event = channel.tryReceive().getOrNull()
            assertNotNull(event, "Should have received an event")
            assertTrue(event is OutputEvent.CloseEvent, "Event should be CloseEvent")
        }

    @Test
    fun `should handle mixed event types correctly`() =
        runBlocking {
            val channel = Channel<OutputEvent>(Channel.UNLIMITED)
            val handler = FilteringChannelOutputHandler(channel)

            // Send mixed events
            handler.handleMessage("start message")
            repeat(150) {
                // This should trigger 1 docker activity message
                handler.handleFrame(Frame(StreamType.STDOUT, "frame $it".toByteArray()))
            }
            handler.handleError("error message", null)
            handler.handleMessage("end message")
            handler.close()

            val events = mutableListOf<OutputEvent>()
            while (!channel.isEmpty) {
                val event = channel.tryReceive().getOrNull()
                if (event != null) {
                    events.add(event)
                } else {
                    break
                }
            }

            // Should have: start message, docker activity (after 100 frames), error message, end message, close event
            assertEquals(5, events.size, "Should have 5 events total")

            assertTrue(events[0] is OutputEvent.MessageEvent, "First event should be MessageEvent")
            assertEquals("start message", (events[0] as OutputEvent.MessageEvent).message)

            assertTrue(events[1] is OutputEvent.MessageEvent, "Second event should be docker activity MessageEvent")
            assertTrue((events[1] as OutputEvent.MessageEvent).message.contains("docker", ignoreCase = true))

            assertTrue(events[2] is OutputEvent.ErrorEvent, "Third event should be ErrorEvent")
            assertEquals("error message", (events[2] as OutputEvent.ErrorEvent).message)

            assertTrue(events[3] is OutputEvent.MessageEvent, "Fourth event should be MessageEvent")
            assertEquals("end message", (events[3] as OutputEvent.MessageEvent).message)

            assertTrue(events[4] is OutputEvent.CloseEvent, "Fifth event should be CloseEvent")
        }
}
