package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.BaseKoinTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    @Test
    fun `a signal during install still restores both earlier handlers`() {
        val restored = mutableListOf<String>()
        var firing: Thread? = null
        val registry =
            SignalRegistry { name, onSignal ->
                if (name == "INT") {
                    // INT arrives as soon as its handler is in, before TERM's is.
                    firing = Thread(onSignal).apply { start() }
                } else {
                    awaitBlockedOrDone(firing)
                }
                { synchronized(restored) { restored += name } }
            }

        // The signal interrupts the command thread; a stand-in keeps the interrupt off the test thread.
        JvmStopSignals(registry).install(Thread {})
        firing?.join(JOIN_MS)

        assertThat(restored).containsExactlyInAnyOrder("INT", "TERM")
    }

    @Test
    fun `close waits for a signal in progress, so the command thread is not interrupted after it`() {
        val inRestore = CountDownLatch(1)
        val release = CountDownLatch(1)
        val handlers = mutableMapOf<String, () -> Unit>()
        val registry =
            SignalRegistry { name, onSignal ->
                handlers[name] = onSignal
                {
                    if (name == "INT") {
                        inRestore.countDown()
                        release.await(JOIN_MS, TimeUnit.MILLISECONDS)
                    }
                }
            }
        val signals = JvmStopSignals(registry)
        val commandThread = Thread.currentThread()
        signals.install(commandThread)
        val signal = Thread { handlers.getValue("TERM").invoke() }.apply { start() }
        inRestore.await(JOIN_MS, TimeUnit.MILLISECONDS)
        val closeReturned = AtomicBoolean(false)
        // Releases the signal once close() is blocked on it, or once close() has returned without waiting.
        val releaser =
            Thread {
                while (commandThread.state != Thread.State.BLOCKED && !closeReturned.get()) Thread.onSpinWait()
                release.countDown()
            }.apply { start() }

        signals.close()
        closeReturned.set(true)
        releaser.join(JOIN_MS)
        signal.join(JOIN_MS)

        assertThat(Thread.currentThread().isInterrupted).isFalse()
        assertThat(signals.isStopped).isTrue()
    }

    private fun awaitBlockedOrDone(thread: Thread?) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(JOIN_MS)
        while (thread != null && thread.state != Thread.State.BLOCKED && thread.state != Thread.State.TERMINATED) {
            check(System.nanoTime() < deadline) { "the signal thread never reached the handler" }
            Thread.onSpinWait()
        }
    }

    private companion object {
        const val LONG_SLEEP_MS = 60_000L
        const val JOIN_MS = 5_000L
    }
}
