package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.output.CompositeOutputHandler
import com.rustyrazorblade.easydblab.output.ConsoleOutputHandler
import com.rustyrazorblade.easydblab.output.LoggerOutputHandler
import com.rustyrazorblade.easydblab.output.NodeOutput
import com.rustyrazorblade.easydblab.output.NodeOutputTail
import com.rustyrazorblade.easydblab.output.NodeStream
import com.rustyrazorblade.easydblab.output.OutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.test.inject

class OutputModuleTest : BaseKoinTest() {
    override fun coreTestModules(): List<Module> = emptyList()

    override fun additionalTestModules(): List<Module> = listOf(outputModule)

    @Test
    fun `default OutputHandler should be CompositeOutputHandler with Logger and Console handlers`() {
        val handler: OutputHandler by inject()

        assertThat(handler).isInstanceOf(CompositeOutputHandler::class.java)

        val composite = handler as CompositeOutputHandler
        assertThat(composite.getHandlerCount()).isEqualTo(2)

        val handlers = composite.getHandlers()
        assertThat(handlers).anyMatch { it is LoggerOutputHandler }
        assertThat(handlers).anyMatch { it is ConsoleOutputHandler }
    }

    @Test
    fun `named console handler should be ConsoleOutputHandler instance`() {
        val consoleHandler: OutputHandler by inject(qualifier = named("console"))

        assertThat(consoleHandler).isInstanceOf(ConsoleOutputHandler::class.java)
    }

    @Test
    fun `named logger handler should be LoggerOutputHandler instance`() {
        val loggerHandler: OutputHandler by inject(qualifier = named("logger"))

        assertThat(loggerHandler).isInstanceOf(LoggerOutputHandler::class.java)
    }

    @Test
    fun `default OutputHandler should be singleton`() {
        val handler1: OutputHandler by inject()
        val handler2: OutputHandler by inject()

        assertThat(handler1).isSameAs(handler2)
    }

    /** A tail is read from the instance the lines were written to; two instances would always read empty. */
    @Test
    fun `the node output and its tail are the same instance`() {
        val written = getKoin().get<NodeOutput>()
        written.line("db0", NodeStream.Stdout, "kept")

        assertThat(getKoin().get<NodeOutputTail>()).isSameAs(written)
        assertThat(getKoin().get<NodeOutputTail>().tail("db0")).containsExactly("kept")
    }
}
