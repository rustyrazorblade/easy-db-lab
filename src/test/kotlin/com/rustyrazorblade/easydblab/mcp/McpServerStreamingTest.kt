package com.rustyrazorblade.easydblab.mcp

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.di.KoinModules
import com.rustyrazorblade.easydblab.output.CompositeOutputHandler
import com.rustyrazorblade.easydblab.output.FilteringChannelOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.test.inject
import java.lang.reflect.Field
import java.util.concurrent.Semaphore

class McpServerStreamingTest : BaseKoinTest() {
    override fun coreTestModules(): List<Module> = emptyList()

    override fun additionalTestModules(): List<Module> = KoinModules.getAllModules()

    @Test
    fun `default OutputHandler should be CompositeOutputHandler for streaming integration`() {
        val outputHandler: OutputHandler by inject()

        assertThat(outputHandler).isInstanceOf(CompositeOutputHandler::class.java)
        assertThat((outputHandler as CompositeOutputHandler).getHandlerCount()).isGreaterThanOrEqualTo(2)
    }

    @Test
    fun `server should maintain semaphore-based single command execution`() {
        val server = McpServer()
        val semaphoreField: Field = McpServer::class.java.getDeclaredField("executionSemaphore")
        semaphoreField.isAccessible = true
        val semaphore = semaphoreField.get(server) as Semaphore

        assertThat(semaphore.tryAcquire()).isTrue()
        assertThat(semaphore.tryAcquire()).isFalse()
        semaphore.release()
        assertThat(semaphore.tryAcquire()).isTrue()
        semaphore.release()
    }

    @Test
    fun `server should be instantiable with mock context`() {
        assertThat(McpServer()).isNotNull()
    }

    @Test
    fun `initializeStreaming should add FilteringChannelOutputHandler to CompositeOutputHandler`() {
        val outputHandler: OutputHandler by inject()
        val compositeHandler = outputHandler as CompositeOutputHandler
        val initialCount = compositeHandler.getHandlerCount()

        McpServer().initializeStreaming()

        assertThat(compositeHandler.getHandlerCount()).isEqualTo(initialCount + 1)
        assertThat(compositeHandler.getHandlers()).anyMatch { it is FilteringChannelOutputHandler }
    }

    @Test
    fun `initializeStreaming should be idempotent`() {
        val outputHandler: OutputHandler by inject()
        val compositeHandler = outputHandler as CompositeOutputHandler
        val server = McpServer()

        server.initializeStreaming()
        val countAfterFirst = compositeHandler.getHandlerCount()

        server.initializeStreaming()

        assertThat(compositeHandler.getHandlerCount()).isEqualTo(countAfterFirst)
    }
}
