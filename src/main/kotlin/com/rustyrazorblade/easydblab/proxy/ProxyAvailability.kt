package com.rustyrazorblade.easydblab.proxy

/**
 * Records a SOCKS5 proxy establishment failure for the command currently executing, for the
 * sole benefit of a command whose `@RequiresProxy(tolerateFailure = true)` annotation opts
 * into tolerating that failure instead of aborting (see `Status`).
 *
 * `DefaultCommandExecutor.checkRequirements()` clears this before every command runs and, only
 * for a tolerant command, records the failure here instead of letting it propagate as an
 * exception. The command then queries [failure] from its own `execute()` to decide what it can
 * still do. Every other command's proxy failure still propagates as a normal exception — this
 * holder exists only to give an explicitly tolerant, read-only command a narrow way to observe
 * "the tunnel is down" without reintroducing the blanket catch this change removed.
 *
 * Backed by a [ThreadLocal] so a stale failure recorded for one command can never leak into the
 * next command executed on the same thread during a long-running `Server`/`Repl` session.
 */
interface ProxyAvailability {
    /** Records that the proxy failed to establish for the command currently executing. */
    fun recordFailure(cause: Throwable)

    /** Clears any previously recorded failure. Called before every command executes. */
    fun clear()

    /** The recorded failure, if the proxy failed to establish for a tolerant command. */
    fun failure(): Throwable?
}

/** Default [ProxyAvailability], backed by a [ThreadLocal]. */
class DefaultProxyAvailability : ProxyAvailability {
    private val state = ThreadLocal<Throwable?>()

    override fun recordFailure(cause: Throwable) {
        state.set(cause)
    }

    override fun clear() {
        state.remove()
    }

    override fun failure(): Throwable? = state.get()
}
