package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.BaseKoinTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class StopSignalsTest : BaseKoinTest() {
    /** Records the handler installed for each signal and which signals got their old handler back. */
    private class FakeRegistry : SignalRegistry {
        val handlers = mutableMapOf<String, () -> Unit>()
        val restored = mutableListOf<String>()

        override fun install(
            name: String,
            onSignal: () -> Unit,
        ): () -> Unit {
            handlers[name] = onSignal
            return { restored += name }
        }
    }

    private val registry = FakeRegistry()
    private val signals = JvmStopSignals(registry)

    @AfterEach
    fun clearInterrupt() {
        Thread.interrupted()
    }

    @Test
    fun `handlers are installed for INT and TERM and nothing is stopped yet`() {
        signals.install(Thread.currentThread())

        assertThat(registry.handlers.keys).containsExactlyInAnyOrder("INT", "TERM")
        assertThat(signals.isStopped).isFalse()
    }

    @Test
    fun `the first signal sets the flag, restores the earlier handlers and interrupts the command thread`() {
        val command = Thread { runCatching { Thread.sleep(LONG_SLEEP_MS) } }
        command.start()
        signals.install(command)

        registry.handlers.getValue("TERM").invoke()
        command.join(JOIN_MS)

        assertThat(signals.isStopped).isTrue()
        assertThat(registry.restored).containsExactlyInAnyOrder("INT", "TERM")
        assertThat(command.isAlive).withFailMessage("the command thread was not interrupted").isFalse()
    }

    @Test
    fun `the handlers are restored once, even when close follows a signal`() {
        signals.install(Thread.currentThread())

        registry.handlers.getValue("INT").invoke()
        signals.close()

        assertThat(registry.restored).hasSize(2)
    }

    @Test
    fun `close restores the handlers when no signal came`() {
        signals.install(Thread.currentThread())

        signals.close()

        assertThat(registry.restored).containsExactlyInAnyOrder("INT", "TERM")
        assertThat(signals.isStopped).isFalse()
    }

    @Test
    fun `close clears the interrupt the signal left on the command thread`() {
        signals.install(Thread.currentThread())
        registry.handlers.getValue("INT").invoke()
        assertThat(Thread.currentThread().isInterrupted).isTrue()

        signals.close()

        assertThat(Thread.currentThread().isInterrupted).isFalse()
    }

    private companion object {
        const val LONG_SLEEP_MS = 60_000L
        const val JOIN_MS = 5_000L
    }
}
