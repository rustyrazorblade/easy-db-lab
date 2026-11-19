package com.rustyrazorblade.easydblab

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.aws.AwsErrorHandler
import com.rustyrazorblade.easydblab.services.CommandFailureReporter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * A failure building the parser is reported once and yields no parser, so `main` exits with an
 * error instead of printing a raw stack trace.
 */
class ParserStartupTest {
    private val events = mutableListOf<Event>()
    private val reporter =
        CommandFailureReporter(
            EventBus().apply {
                addListener(
                    object : EventListener {
                        override fun onEvent(envelope: EventEnvelope) {
                            events.add(envelope.event)
                        }

                        override fun close() = Unit
                    },
                )
            },
            listOf(AwsErrorHandler()),
        )

    @Test
    fun `a parser that fails to build is reported once with its cause and yields nothing`() {
        val parser = ParserStartup.buildOrReport<String>(reporter) { error("kit registration broke") }

        assertThat(parser).isNull()
        val reported = events.single()
        assertThat(reported).isInstanceOf(Event.Command.ExecutionError::class.java)
        assertThat((reported as Event.Command.ExecutionError).error).contains("kit registration broke")
    }

    @Test
    fun `a parser that builds is returned and nothing is reported`() {
        assertThat(ParserStartup.buildOrReport(reporter) { "parser" }).isEqualTo("parser")
        assertThat(events).isEmpty()
    }

    @Test
    fun `an Error is not reported and propagates`() {
        assertThatThrownBy { ParserStartup.buildOrReport<String>(reporter) { throw OutOfMemoryError("oom") } }
            .isInstanceOf(OutOfMemoryError::class.java)
        assertThat(events).isEmpty()
    }
}
