package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.output.CompositeOutputHandler
import com.rustyrazorblade.easydblab.output.ConsoleOutputHandler
import com.rustyrazorblade.easydblab.output.LoggerOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import org.koin.test.inject

class OutputModuleTest : KoinTest {
    @BeforeEach
    fun setup() {
        startKoin {
            modules(outputModule)
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

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
        val consoleHandler: OutputHandler by inject(
            qualifier =
                org.koin.core.qualifier
                    .named("console"),
        )

        assertThat(consoleHandler).isInstanceOf(ConsoleOutputHandler::class.java)
    }

    @Test
    fun `named logger handler should be LoggerOutputHandler instance`() {
        val loggerHandler: OutputHandler by inject(
            qualifier =
                org.koin.core.qualifier
                    .named("logger"),
        )

        assertThat(loggerHandler).isInstanceOf(LoggerOutputHandler::class.java)
    }

    @Test
    fun `default OutputHandler should be singleton`() {
        val handler1: OutputHandler by inject()
        val handler2: OutputHandler by inject()

        assertThat(handler1).isSameAs(handler2)
    }
}
