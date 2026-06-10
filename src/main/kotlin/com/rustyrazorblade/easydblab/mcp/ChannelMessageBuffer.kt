package com.rustyrazorblade.easydblab.mcp

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.output.OutputEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import java.time.LocalTime
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Manages buffering of messages from output events.
 *
 * This class processes OutputEvent messages from a channel and stores them in a thread-safe buffer
 * with timestamps. It manages its own consumer thread internally.
 */
class ChannelMessageBuffer(
    private val outputChannel: Channel<OutputEvent>,
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Thread-safe buffer for storing timestamped messages.
     */
    private val messageBuffer = Collections.synchronizedList(mutableListOf<String>())

    /**
     * The consumer thread that reads from the channel.
     */
    private var consumerThread: Thread? = null

    /**
     * Flag indicating whether the consumer is running.
     */
    @Volatile
    private var running = false

    /**
     * Starts the consumer thread that reads from the channel and buffers messages.
     */
    fun start() {
        if (running) {
            log.debug { "Message buffer consumer already running" }
            return
        }

        running = true
        consumerThread =
            thread(start = true, name = "MCP-MessageBuffer-Consumer", isDaemon = true) {
                log.info { "Starting message buffer consumer thread" }
                try {
                    while (running) {
                        val result = outputChannel.tryReceive()
                        if (result.isSuccess) {
                            result.getOrNull()?.let { processEvent(it) }
                        } else if (result.isClosed) {
                            // do nothing
                        }
                        Thread.sleep(Constants.Time.THREAD_SLEEP_DELAY_MS) // Small delay to avoid busy-waiting
                    }
                } catch (e: InterruptedException) {
                    log.info { "Message buffer consumer thread interrupted" }
                } catch (e: RuntimeException) {
                    log.error(e) { "Message buffer consumer thread error" }
                }
                log.info { "Message buffer consumer thread completed" }
                running = false
            }
    }

    /**
     * Stops the consumer thread.
     */
    fun stop() {
        running = false
        consumerThread?.join(Constants.Time.THREAD_JOIN_TIMEOUT_MS) // Wait up to 1 second for thread to finish
    }

    /**
     * Cleans a message by removing ANSI escape sequences and newlines.
     *
     * @param message The message to clean
     * @return The cleaned message
     */
    internal fun cleanMessage(message: String): String {
        // Remove ANSI escape sequences (e.g., color codes)
        // Pattern matches ESC followed by [ and any number of digits, semicolons, and letters
        val noAnsi = message.replace(Regex("\u001b\\[[0-9;]*[a-zA-Z]"), "")

        // Remove newlines and carriage returns, replace with space to preserve word boundaries
        val noNewlines = noAnsi.replace(Regex("[\r\n]+"), " ")

        // Trim and collapse multiple spaces into single space
        return noNewlines.trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Processes an output event and adds it to the buffer if appropriate.
     *
     * @param event The OutputEvent to process
     * @return true if processing should continue, false if a CloseEvent was received
     */
    private fun processEvent(event: OutputEvent): Boolean {
        try {
            when (event) {
                is OutputEvent.MessageEvent -> {
                    val cleanedMessage = cleanMessage(event.message)
                    val timestampedMessage = "[${LocalTime.now()}] $cleanedMessage"
                    messageBuffer.add(timestampedMessage)
                    log.debug { "Buffered message: $cleanedMessage" }
                    return true
                }
                is OutputEvent.ErrorEvent -> {
                    val cleanedMessage = cleanMessage(event.message)
                    val timestampedMessage = "[${LocalTime.now()}] ERROR: $cleanedMessage"
                    messageBuffer.add(timestampedMessage)
                    log.debug { "Buffered error: $cleanedMessage" }
                    return true
                }
                is OutputEvent.CloseEvent -> {
                    log.debug { "Close event received" }
                    return false
                }
                is OutputEvent.FrameEvent -> {
                    // Frame events are filtered by FilteringChannelOutputHandler
                    // We log them but don't buffer them
                    log.trace { "Frame event received but not buffered" }
                    return true
                }
            }
        } catch (e: RuntimeException) {
            log.error(e) { "Error processing output event for message buffer" }
            return true
        }
    }

    /**
     * Returns a copy of all messages currently in the buffer.
     *
     * @return List of timestamped messages
     */
    fun getMessages(): List<String> = messageBuffer.toList()

    /**
     * Clears all messages from the buffer.
     */
    fun clearMessages() {
        messageBuffer.clear()
    }

    /**
     * Atomically retrieves all messages and clears the buffer.
     *
     * This method is useful for consuming all messages at once while
     * ensuring no messages are lost between retrieval and clearing.
     *
     * @return List of timestamped messages that were in the buffer
     */
    fun getAndClearMessages(): List<String> {
        synchronized(messageBuffer) {
            val messages = messageBuffer.toList()
            messageBuffer.clear()
            return messages
        }
    }

    /**
     * Returns the current number of messages in the buffer.
     *
     * @return The number of messages currently buffered
     */
    fun size(): Int = messageBuffer.size

    /**
     * Checks if the buffer is empty.
     *
     * @return true if the buffer contains no messages, false otherwise
     */
    fun isEmpty(): Boolean = messageBuffer.isEmpty()
}
