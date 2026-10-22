package com.rustyrazorblade.easydblab.export.query

/**
 * The JVM's shutdown hooks, as a query export registers them while its temporary docker compose
 * projects run: an export killed by Ctrl-C or SIGTERM otherwise left them running. The seam lets a
 * test run a hook by hand.
 */
interface ShutdownHooks {
    /** Registers [hook] to run when the JVM shuts down. */
    fun add(hook: Thread)

    /** Removes [hook]; nothing happens when the JVM is already shutting down, since the hook then runs anyway. */
    fun remove(hook: Thread)
}

/** [ShutdownHooks] over [Runtime]. */
object JvmShutdownHooks : ShutdownHooks {
    override fun add(hook: Thread) = Runtime.getRuntime().addShutdownHook(hook)

    override fun remove(hook: Thread) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook)
        } catch (_: IllegalStateException) {
            // The JVM is shutting down: the hook runs, and the project's own teardown runs at most once.
        }
    }
}

/**
 * Runs [teardown] at most once: from the normal path's [run], or from a JVM shutdown hook registered
 * at construction, whichever comes first. A hook that fires while the normal path is tearing down
 * waits for it to finish, so the JVM does not exit halfway through a `docker compose down`. [close]
 * removes the hook.
 *
 * @property hooks the JVM's shutdown hooks.
 * @property teardown what removes the project.
 */
class ShutdownGuard(
    private val hooks: ShutdownHooks,
    name: String,
    private val teardown: () -> Unit,
) : AutoCloseable {
    private var done = false
    private val hook = Thread({ run() }, name)

    init {
        hooks.add(hook)
    }

    /**
     * Runs [instead], the teardown by default, unless a teardown already ran or is running elsewhere;
     * in that case waits for it to end.
     */
    @Synchronized
    fun run(instead: () -> Unit = teardown) {
        if (done) return
        done = true
        instead()
    }

    /** Removes the shutdown hook; the teardown does not run. */
    override fun close() = hooks.remove(hook)
}
