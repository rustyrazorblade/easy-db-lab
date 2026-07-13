package com.rustyrazorblade.easydblab.output

import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.StreamType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class FilteringChannelOutputHandlerFrameResetTest {
    private lateinit var outputChannel: Channel<OutputEvent>
    private lateinit var handler: FilteringChannelOutputHandler
    private lateinit var mockFrame: Frame

    @BeforeEach
    fun setup() {
        outputChannel = Channel(Channel.UNLIMITED)
        handler = FilteringChannelOutputHandler(outputChannel)

        // Create a mock frame for testing
        mockFrame = mock()
        whenever(mockFrame.payload).thenReturn("test output".toByteArray())
        whenever(mockFrame.streamType).thenReturn(StreamType.STDOUT)
    }

    @AfterEach
    fun teardown() {
        outputChannel.close()
    }

    @Test
    fun `resetFrameCount should reset frame count to zero`() =
        runBlocking {
            // Send 50 frames (no message should be sent yet)
            repeat(50) {
                handler.handleFrame(mockFrame)
            }

            // Verify no activity message was sent yet (need 100 frames)
            assertTrue(outputChannel.isEmpty, "No messages should be sent before 100 frames")

            // Send 50 more frames (should trigger activity message at frame 100)
            repeat(50) {
                handler.handleFrame(mockFrame)
            }

            // Verify activity message was sent for 100 frames
            val event1 = outputChannel.tryReceive().getOrNull()
            assertNotNull(event1, "Activity message should be sent at 100 frames")
            assertTrue(event1 is OutputEvent.MessageEvent, "Event should be MessageEvent")
            val message1 = (event1 as OutputEvent.MessageEvent).message
            assertEquals("Docker container activity: 100 frames processed", message1)

            // Reset frame count
            handler.resetFrameCount()

            // Send 100 more frames after reset
            repeat(100) {
                handler.handleFrame(mockFrame)
            }

            // Verify activity message shows 100 frames (not 200), indicating reset worked
            val event2 = outputChannel.tryReceive().getOrNull()
            assertNotNull(event2, "Activity message should be sent at 100 frames after reset")
            assertTrue(event2 is OutputEvent.MessageEvent, "Event should be MessageEvent")
            val message2 = (event2 as OutputEvent.MessageEvent).message
            assertEquals(
                "Docker container activity: 100 frames processed",
                message2,
                "Frame count should restart from 0 after reset",
            )
        }

    @Test
    fun `frame count should continue normally without reset`() =
        runBlocking {
            // Send 100 frames to trigger first activity message
            repeat(100) {
                handler.handleFrame(mockFrame)
            }

            // Verify first activity message
            val event1 = outputChannel.tryReceive().getOrNull()
            assertNotNull(event1, "First activity message should be sent")
            val message1 = (event1 as OutputEvent.MessageEvent).message
            assertEquals("Docker container activity: 100 frames processed", message1)

            // Send 100 more frames without reset (should show 200)
            repeat(100) {
                handler.handleFrame(mockFrame)
            }

            // Verify second activity message shows accumulated count (200)
            val event2 = outputChannel.tryReceive().getOrNull()
            assertNotNull(event2, "Second activity message should be sent")
            val message2 = (event2 as OutputEvent.MessageEvent).message
            assertEquals(
                "Docker container activity: 200 frames processed",
                message2,
                "Frame count should accumulate without reset",
            )
        }

    @Test
    fun `multiple resets should work correctly`() =
        runBlocking {
            // First batch: 100 frames
            repeat(100) {
                handler.handleFrame(mockFrame)
            }
            val event1 = outputChannel.tryReceive().getOrNull()
            assertEquals(
                "Docker container activity: 100 frames processed",
                (event1 as OutputEvent.MessageEvent).message,
            )

            // Reset and second batch: 100 frames
            handler.resetFrameCount()
            repeat(100) {
                handler.handleFrame(mockFrame)
            }
            val event2 = outputChannel.tryReceive().getOrNull()
            assertEquals(
                "Docker container activity: 100 frames processed",
                (event2 as OutputEvent.MessageEvent).message,
            )

            // Reset and third batch: 100 frames
            handler.resetFrameCount()
            repeat(100) {
                handler.handleFrame(mockFrame)
            }
            val event3 = outputChannel.tryReceive().getOrNull()
            assertEquals(
                "Docker container activity: 100 frames processed",
                (event3 as OutputEvent.MessageEvent).message,
            )

            // All three batches should show "100 frames processed" due to resets
        }

    @Test
    fun `reset should not affect other event types`() =
        runBlocking {
            // Send some frames
            repeat(50) {
                handler.handleFrame(mockFrame)
            }

            // Send a regular message
            handler.handleMessage("Test message")

            // Reset frame count
            handler.resetFrameCount()

            // Send error
            handler.handleError("Test error", RuntimeException("test"))

            // Verify message and error were sent unchanged
            val messageEvent = outputChannel.tryReceive().getOrNull()
            assertTrue(messageEvent is OutputEvent.MessageEvent, "Message event should be sent")
            assertEquals("Test message", (messageEvent as OutputEvent.MessageEvent).message)

            val errorEvent = outputChannel.tryReceive().getOrNull()
            assertTrue(errorEvent is OutputEvent.ErrorEvent, "Error event should be sent")
            assertEquals("Test error", (errorEvent as OutputEvent.ErrorEvent).message)
        }
}
