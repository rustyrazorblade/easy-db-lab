package com.rustyrazorblade.easydblab.services.metrics

import sun.misc.Signal
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The stop flag of a `metrics stream` run, set by the first Ctrl-C or SIGTERM.
 *
 * The first signal stops the stream cleanly so the command exits 0.  It also puts back the handlers
 * that were there before, so a second signal gets the JVM's own handling and the process exits at
 * once with 130 or 143.
 */
interface StopSignals : AutoCloseable {
    /** True once a stop signal arrived. */
    val isStopped: Boolean

    /** Installs the handlers; the first signal interrupts [commandThread] so a wait or a query ends. */
    fun install(commandThread: Thread)

    /**
     * Puts back the earlier handlers if a signal has not already done so, and clears the interrupt a
     * signal left on the calling (command) thread.  Called by the command thread when the loop ends.
     */
    override fun close()
}

/** Installs a handler for one OS signal and returns what puts the earlier handler back. */
fun interface SignalRegistry {
    /** Installs [onSignal] for the signal named [name] (`INT`, `TERM`). */
    fun install(
        name: String,
        onSignal: () -> Unit,
    ): () -> Unit
}

/** [SignalRegistry] over `sun.misc.Signal`, which every JDK 21+ ships in `jdk.unsupported`. */
object JvmSignalRegistry : SignalRegistry {
    override fun install(
        name: String,
        onSignal: () -> Unit,
    ): () -> Unit {
        val signal = Signal(name)
        val previous = Signal.handle(signal) { onSignal() }
        return { Signal.handle(signal, previous) }
    }
}

/**
 * [StopSignals] for INT and TERM.
 *
 * @param registry installs the handlers; tests replace it to fire a signal by hand
 */
class JvmStopSignals(
    private val registry: SignalRegistry = JvmSignalRegistry,
) : StopSignals {
    private val stopped = AtomicBoolean(false)
    private val restored = AtomicBoolean(false)

    @Volatile
    private var restorers: List<() -> Unit> = emptyList()

    override val isStopped: Boolean get() = stopped.get()

    override fun install(commandThread: Thread) {
        restorers = SIGNALS.map { name -> registry.install(name) { onSignal(commandThread) } }
    }

    private fun onSignal(commandThread: Thread) {
        if (stopped.compareAndSet(false, true)) {
            restore()
            commandThread.interrupt()
        }
    }

    override fun close() {
        restore()
        Thread.interrupted()
    }

    private fun restore() {
        if (restored.compareAndSet(false, true)) restorers.forEach { it() }
    }

    private companion object {
        val SIGNALS = listOf("INT", "TERM")
    }
}
