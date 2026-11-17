package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.Constants
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Caps the nodes in their join step at [limit] (`Constants.Scale.JOIN_CONCURRENCY`), and gives
 * out no permit while the control plane is unhealthy. A pipeline holds its permit from the agent
 * configuration until its Node registers, and releases it before it waits for Ready: the
 * registration burst is what saturated the datastore in #1134.
 *
 * The semaphore is fair, so a node waiting longest joins first. A permit given out just as the
 * control plane turned unhealthy is handed back and waited for again.
 */
class JoinLimiter(
    private val health: HealthLatch,
    private val limit: Int = Constants.Scale.JOIN_CONCURRENCY,
) {
    private val permits = Semaphore(limit, true)

    /** The permits held now. */
    val inFlight: Int
        get() = limit - permits.availablePermits()

    /** Blocks until the control plane is healthy and a permit is free; close the permit to release it. */
    fun acquire(): Permit {
        while (true) {
            health.awaitHealthy()
            permits.acquire()
            if (health.healthy) return Permit(permits)
            permits.release()
        }
    }

    /** One join permit. Closing it more than once releases it once. */
    class Permit internal constructor(
        private val permits: Semaphore,
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) permits.release()
        }
    }
}

/**
 * Caps the SSH sessions open across every pipeline at [limit] (`Constants.Scale.HOST_CONCURRENCY`),
 * so the operator's machine opens no more than it did when each phase ran on a pool of that size.
 * Every SSH step runs its remote calls inside [withSession].
 */
class SshSessionLimiter(
    limit: Int = Constants.Scale.HOST_CONCURRENCY,
) {
    private val sessions = Semaphore(limit, true)

    /** Runs [block] holding one session permit. */
    fun <T> withSession(block: () -> T): T {
        sessions.acquire()
        try {
            return block()
        } finally {
            sessions.release()
        }
    }
}
