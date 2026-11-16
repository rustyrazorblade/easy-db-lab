package com.rustyrazorblade.easydblab.services.metrics

import sun.misc.Signal

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
 * One lock covers installing the handlers, a signal's stop (flag, restore, interrupt) and [close].
 * A signal that arrives while the handlers are being installed waits for both, so it restores both.
 * [close] waits for a signal already being handled, and a signal after [close] does nothing, so
 * the command thread is never interrupted once the loop has ended.
 *
 * @param registry installs the handlers; tests replace it to fire a signal by hand
 */
class JvmStopSignals(
    private val registry: SignalRegistry = JvmSignalRegistry,
) : StopSignals {
    private val lock = Any()

    @Volatile
    private var stopped = false

    // Guarded by [lock].
    private var restorers: List<() -> Unit> = emptyList()
    private var restored = false
    private var closed = false

    override val isStopped: Boolean get() = stopped

    override fun install(commandThread: Thread) {
        synchronized(lock) {
            restorers = SIGNALS.map { name -> registry.install(name) { onSignal(commandThread) } }
        }
    }

    private fun onSignal(commandThread: Thread) {
        synchronized(lock) {
            if (stopped || closed) return
            stopped = true
            restore()
            commandThread.interrupt()
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            restore()
        }
        Thread.interrupted()
    }

    // Called with [lock] held.
    private fun restore() {
        if (restored) return
        restored = true
        restorers.forEach { it() }
    }

    private companion object {
        val SIGNALS = listOf("INT", "TERM")
    }
}
