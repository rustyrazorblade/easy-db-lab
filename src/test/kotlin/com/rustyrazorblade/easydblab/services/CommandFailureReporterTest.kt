package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.commands.SetupProfileException
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.kernel.ErrorDescription
import com.rustyrazorblade.easydblab.kernel.ProviderErrorHandler
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletionException

/**
 * How a failure becomes one event: the first registered handler that claims an exception in the
 * cause chain wins, and anything unclaimed stays an ordinary execution error.
 */
class CommandFailureReporterTest {
    private class CredentialsProblem(
        message: String,
        cause: Throwable? = null,
    ) : RuntimeException(message, cause)

    private val emitted = mutableListOf<Event>()
    private val bus =
        EventBus().also {
            it.addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        emitted.add(envelope.event)
                    }

                    override fun close() = Unit
                },
            )
        }

    private fun handler(provider: String): ProviderErrorHandler =
        ProviderErrorHandler {
            if (it is CredentialsProblem) {
                ErrorDescription(provider, ProviderErrorKind.CREDENTIALS, it.message.orEmpty(), "fix $provider")
            } else {
                null
            }
        }

    private fun reporter(vararg handlers: ProviderErrorHandler) = CommandFailureReporter(bus, handlers.toList())

    @Test
    fun `a claimed exception is reported as a provider error`() {
        reporter(handler("AWS")).report(CredentialsProblem("token expired"))

        assertThat(emitted).containsExactly(
            Event.Command.ProviderError("AWS", ProviderErrorKind.CREDENTIALS, "token expired", "fix AWS"),
        )
    }

    @Test
    fun `the first matching handler wins`() {
        reporter(handler("first"), handler("second")).report(CredentialsProblem("x"))

        assertThat(emitted.single()).isInstanceOf(Event.Command.ProviderError::class.java)
        assertThat((emitted.single() as Event.Command.ProviderError).provider).isEqualTo("first")
    }

    @Test
    fun `a provider exception wrapped in completion and setup exceptions is found`() {
        val wrapped = CompletionException(SetupProfileException("setup failed", CredentialsProblem("deep")))

        reporter(handler("AWS")).report(wrapped)

        assertThat((emitted.single() as Event.Command.ProviderError).detail).isEqualTo("deep")
    }

    @Test
    fun `an outer match is preferred over an inner one`() {
        val error = CredentialsProblem("outer", CredentialsProblem("inner"))

        reporter(handler("AWS")).report(error)

        assertThat((emitted.single() as Event.Command.ProviderError).detail).isEqualTo("outer")
    }

    @Test
    fun `a cause loop terminates and emits one event`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)

        reporter(handler("AWS")).report(a)

        assertThat(emitted).hasSize(1)
        assertThat(emitted.single()).isInstanceOf(Event.Command.ExecutionError::class.java)
    }

    @Test
    fun `an unclaimed failure keeps the execution error with its cause chain`() {
        reporter(handler("AWS")).report(IllegalStateException("outer", IllegalArgumentException("inner")))

        val event = emitted.single() as Event.Command.ExecutionError
        assertThat(event.error).isEqualTo("IllegalStateException: outer\n  caused by: IllegalArgumentException: inner")
    }

    @Test
    fun `a failure the command already reported emits nothing`() {
        val error = CommandFailedException("already shown", CredentialsProblem("hidden"))

        reporter(handler("AWS")).report(error)

        assertThat(emitted).isEmpty()
    }

    @Test
    fun `a reported failure wrapped in a CompletionException emits nothing`() {
        val error = CompletionException(CommandFailedException("already shown", CredentialsProblem("hidden")))

        reporter(handler("AWS")).report(error)

        assertThat(emitted).isEmpty()
    }

    @Test
    fun `a reported failure wrapped in an ExecutionException and a SetupProfileException emits nothing`() {
        val reported = CommandFailedException("already shown", CredentialsProblem("hidden"))
        val error = SetupProfileException("setup failed", java.util.concurrent.ExecutionException(reported))

        reporter(handler("AWS")).report(error)

        assertThat(emitted).isEmpty()
    }
}
