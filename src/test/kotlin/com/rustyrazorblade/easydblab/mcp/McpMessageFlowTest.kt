package com.rustyrazorblade.easydblab.mcp

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.di.KoinModules
import com.rustyrazorblade.easydblab.output.CompositeOutputHandler
import com.rustyrazorblade.easydblab.output.OutputEvent
import com.rustyrazorblade.easydblab.output.OutputHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.File

class McpMessageFlowTest : KoinComponent {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    @TempDir lateinit var tempDir: File

    private lateinit var context: Context
    private lateinit var mcpServer: McpServer
    private lateinit var registry: McpToolRegistry
    private val outputHandler: OutputHandler by inject()

    @BeforeEach
    fun setup() {
        // Create a proper settings file
        val profileDir = File(tempDir, "profiles/default")
        profileDir.mkdirs()
        val userConfigFile = File(profileDir, "settings.yaml")
        userConfigFile.writeText(
            """
            email: test@example.com
            region: us-east-1
            keyName: test-key
            sshKeyPath: /tmp/test-key.pem
            awsProfile: default
            awsAccessKey: test-access-key
            awsSecret: test-secret
            axonOpsOrg: ""
            axonOpsKey: ""
            """.trimIndent(),
        )

        // Create context and MCP components
        context = Context(tempDir)

        // Initialize Koin for dependency injection
        startKoin { modules(KoinModules.getAllModules() + org.koin.dsl.module { single { context } }) }

        mcpServer = McpServer()
        registry = McpToolRegistry()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `test message flow through server`() {
        // Use reflection to access the private messageBuffer field
        val messageBufferField = McpServer::class.java.getDeclaredField("messageBuffer")
        messageBufferField.isAccessible = true
        val messageBuffer = messageBufferField.get(mcpServer) as ChannelMessageBuffer

        // Start the message buffer
        messageBuffer.start()

        // Initialize streaming which adds FilteringChannelOutputHandler
        mcpServer.initializeStreaming()

        // Get the composite handler
        val compositeHandler = outputHandler as CompositeOutputHandler
        log.info { "CompositeOutputHandler has ${compositeHandler.getHandlerCount()} handlers" }

        // Send a test message
        compositeHandler.handleMessage("Test message 1")
        compositeHandler.handleMessage("Test message 2")
        compositeHandler.handleError("Test error", null)

        // Give some time for async processing
        Thread.sleep(500)

        // Check if messages are in the buffer
        val messages = messageBuffer.getMessages()
        log.info { "Messages in buffer: $messages" }

        assertFalse(messageBuffer.isEmpty(), "Message buffer should not be empty")
        assertTrue(messages.any { it.contains("Test message 1") }, "Should contain test message 1")
        assertTrue(messages.any { it.contains("Test message 2") }, "Should contain test message 2")
        assertTrue(messages.any { it.contains("ERROR: Test error") }, "Should contain test error")

        // Clean up
        messageBuffer.stop()
    }

    @Test
    fun `test direct channel and buffer interaction`() {
        // Create channel and buffer directly
        val channel = Channel<OutputEvent>(Channel.UNLIMITED)
        val buffer = ChannelMessageBuffer(channel)

        // Start the buffer consumer
        buffer.start()

        // Send events directly to the channel
        assertTrue(channel.trySend(OutputEvent.MessageEvent("Direct message 1")).isSuccess)
        assertTrue(channel.trySend(OutputEvent.MessageEvent("Direct message 2")).isSuccess)
        assertTrue(channel.trySend(OutputEvent.ErrorEvent("Direct error", null)).isSuccess)

        // Wait for processing
        Thread.sleep(100)

        // Check buffer has messages
        assertFalse(buffer.isEmpty(), "Buffer should not be empty")
        assertEquals(3, buffer.size(), "Buffer should have 3 messages")

        val messages = buffer.getMessages()
        assertTrue(messages[0].contains("Direct message 1"))
        assertTrue(messages[1].contains("Direct message 2"))
        assertTrue(messages[2].contains("ERROR: Direct error"))

        // Clean up
        buffer.stop()
    }

    @Test
    fun `test get_status returns accumulated messages`() {
        // Use reflection to access the private messageBuffer and outputChannel fields
        val messageBufferField = McpServer::class.java.getDeclaredField("messageBuffer")
        messageBufferField.isAccessible = true
        val messageBuffer = messageBufferField.get(mcpServer) as ChannelMessageBuffer

        val outputChannelField = McpServer::class.java.getDeclaredField("outputChannel")
        outputChannelField.isAccessible = true
        val outputChannel = outputChannelField.get(mcpServer) as Channel<OutputEvent>

        // Start the message buffer
        messageBuffer.start()

        // Initialize streaming which adds FilteringChannelOutputHandler
        mcpServer.initializeStreaming()

        // Send messages directly to the channel (simulating tool execution output)
        assertTrue(
            outputChannel.trySend(OutputEvent.MessageEvent("Tool execution started")).isSuccess,
        )
        assertTrue(outputChannel.trySend(OutputEvent.MessageEvent("Processing data...")).isSuccess)
        assertTrue(
            outputChannel
                .trySend(OutputEvent.ErrorEvent("Warning: High memory usage", null))
                .isSuccess,
        )
        assertTrue(
            outputChannel
                .trySend(OutputEvent.MessageEvent("Tool execution completed"))
                .isSuccess,
        )

        // Wait for processing
        Thread.sleep(100)

        // Verify messages are in the buffer before calling getAndClear
        val messagesBeforeClear = messageBuffer.getMessages()
        assertEquals(4, messagesBeforeClear.size, "Should have 4 messages before clear")

        // Call getAndClearMessages as the get_status handler would
        val clearedMessages = messageBuffer.getAndClearMessages()

        // Verify we got the messages
        assertEquals(4, clearedMessages.size, "Should retrieve 4 messages")
        assertTrue(clearedMessages[0].contains("Tool execution started"))
        assertTrue(clearedMessages[1].contains("Processing data"))
        assertTrue(clearedMessages[2].contains("ERROR: Warning: High memory usage"))
        assertTrue(clearedMessages[3].contains("Tool execution completed"))

        // Verify buffer is now empty
        assertTrue(messageBuffer.isEmpty(), "Buffer should be empty after clear")
        assertEquals(0, messageBuffer.getMessages().size, "Should have no messages after clear")

        // Clean up
        messageBuffer.stop()
    }
}
