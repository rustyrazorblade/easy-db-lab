package com.rustyrazorblade.easydblab.mcp

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.di.KoinModules
import com.rustyrazorblade.easydblab.output.CompositeOutputHandler
import com.rustyrazorblade.easydblab.output.OutputEvent
import com.rustyrazorblade.easydblab.output.OutputHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.inject
import java.io.File
import java.time.Duration

class McpMessageFlowTest : BaseKoinTest() {
    companion object {
        private val log = KotlinLogging.logger {}

        // Failure ceiling only: awaitUntil returns as soon as the condition holds. Generous so a
        // loaded CI machine never flakes.
        private val AWAIT_TIMEOUT = Duration.ofSeconds(10)
        private const val POLL_INTERVAL_MS = 5L
    }

    // tempDir is provided by BaseKoinTest and set by @TempDir before KoinTestExtension fires
    override fun coreTestModules(): List<Module> = emptyList()

    override fun additionalTestModules(): List<Module> {
        val profileDir = File(tempDir, "profiles/default")
        profileDir.mkdirs()
        File(profileDir, "settings.yaml").writeText(
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
        val ctx = Context(tempDir)
        return KoinModules.getAllModules() + module { single { ctx } }
    }

    private val outputHandler: OutputHandler by inject()

    private lateinit var mcpServer: McpServer
    private lateinit var registry: McpToolRegistry

    @BeforeEach
    fun setup() {
        mcpServer = McpServer()
        registry = McpToolRegistry()
    }

    /**
     * Polls [condition] until it holds or [AWAIT_TIMEOUT] elapses. The message buffer drains its
     * channel on a background consumer thread, so the tests wait on observable buffer state rather
     * than a fixed sleep that a descheduled consumer can overrun.
     */
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

    @Test
    fun `test message flow through server`() {
        val messageBufferField = McpServer::class.java.getDeclaredField("messageBuffer")
        messageBufferField.isAccessible = true
        val messageBuffer = messageBufferField.get(mcpServer) as ChannelMessageBuffer

        messageBuffer.start()
        mcpServer.initializeStreaming()

        val compositeHandler = outputHandler as CompositeOutputHandler
        log.info { "CompositeOutputHandler has ${compositeHandler.getHandlerCount()} handlers" }

        compositeHandler.handleMessage("Test message 1")
        compositeHandler.handleMessage("Test message 2")
        compositeHandler.handleError("Test error", null)

        val expected = listOf("Test message 1", "Test message 2", "ERROR: Test error")
        awaitUntil("all three messages are buffered") {
            val buffered = messageBuffer.getMessages()
            expected.all { text -> buffered.any { it.contains(text) } }
        }

        val messages = messageBuffer.getMessages()
        log.info { "Messages in buffer: $messages" }

        assertThat(messageBuffer.isEmpty()).isFalse()
        assertThat(messages).anyMatch { it.contains("Test message 1") }
        assertThat(messages).anyMatch { it.contains("Test message 2") }
        assertThat(messages).anyMatch { it.contains("ERROR: Test error") }

        messageBuffer.stop()
    }

    @Test
    fun `test get_status returns accumulated messages`() {
        val messageBufferField = McpServer::class.java.getDeclaredField("messageBuffer")
        messageBufferField.isAccessible = true
        val messageBuffer = messageBufferField.get(mcpServer) as ChannelMessageBuffer

        val outputChannelField = McpServer::class.java.getDeclaredField("outputChannel")
        outputChannelField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val outputChannel = outputChannelField.get(mcpServer) as Channel<OutputEvent>

        messageBuffer.start()
        mcpServer.initializeStreaming()

        assertThat(outputChannel.trySend(OutputEvent.MessageEvent("Tool execution started")).isSuccess).isTrue()
        assertThat(outputChannel.trySend(OutputEvent.MessageEvent("Processing data...")).isSuccess).isTrue()
        assertThat(outputChannel.trySend(OutputEvent.ErrorEvent("Warning: High memory usage", null)).isSuccess).isTrue()
        assertThat(outputChannel.trySend(OutputEvent.MessageEvent("Tool execution completed")).isSuccess).isTrue()

        awaitUntil("all four messages are buffered") { messageBuffer.size() >= 4 }

        assertThat(messageBuffer.getMessages()).hasSize(4)

        val clearedMessages = messageBuffer.getAndClearMessages()

        assertThat(clearedMessages).hasSize(4)
        assertThat(clearedMessages[0]).contains("Tool execution started")
        assertThat(clearedMessages[1]).contains("Processing data")
        assertThat(clearedMessages[2]).contains("ERROR: Warning: High memory usage")
        assertThat(clearedMessages[3]).contains("Tool execution completed")

        assertThat(messageBuffer.isEmpty()).isTrue()

        messageBuffer.stop()
    }
}
