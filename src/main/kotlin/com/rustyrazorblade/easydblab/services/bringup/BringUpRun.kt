package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.events.EventBus
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The control plane once its gate is open: what nodes join with, and the clients to watch them through. */
internal data class OpenControlPlane(
    val access: ControlPlaneAccess,
    val connection: ControlPlaneConnection,
)

/**
 * What every pipeline of one bring-up shares: the ports, the step runner, the limiters, the
 * health latch, the progress counts, the replacer, and the latches the control pipeline releases
 * (the [workspace] files every setup installs from, the registry [certificate], the
 * control-plane [connection], and [controlPlane] once the gate is open). It also owns the
 * background work (tickers, the connection) and closes it in [close].
 */
internal class BringUpRun(
    val ports: BringUpPortSet,
    val settings: BringUpSettings,
    val eventBus: EventBus,
    val clock: Clock,
) : AutoCloseable {
    val timing: BringUpTiming get() = settings.timing
    val health = HealthLatch()
    val runner = StepRunner(eventBus, health, timing.retryInterval, timing.attemptStopGrace)
    val joins = JoinLimiter(health, settings.joinLimit)
    val ssh = SshSessionLimiter(settings.sshSessions)
    val progress = BringUpProgress(settings.runCount, settings.joinLimit, clock)
    val workspace = CompletableFuture<Unit>()
    val certificate = CompletableFuture<Unit>()
    val connection = CompletableFuture<ControlPlaneConnection>()
    val controlPlane = CompletableFuture<OpenControlPlane>()
    val versions = Once()
    val replacer =
        NodeReplacer(
            ports.fleet,
            ports.state,
            ReplacementBudget(settings.nodeCount),
            health,
            eventBus,
        )
    private val owned = ConcurrentLinkedDeque<AutoCloseable>()

    /** Takes [resource] to close when the bring-up ends, after everything taken later. */
    fun <T : AutoCloseable> own(resource: T): T = resource.also(owned::push)

    /** Closes what the bring-up owns, newest first; a failed close is logged, so the others still close. */
    override fun close() {
        generateSequence { owned.poll() }.forEach { resource ->
            runCatching { resource.close() }.onFailure { log.warn(it) { "Closing $resource failed" } }
        }
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}

/** Runs an action once: the first caller to succeed does it, and every later call returns at once. */
internal class Once {
    private val lock = ReentrantLock()
    private var done = false

    /** Runs [action] unless it already succeeded; a failure leaves it for the next caller. */
    fun run(action: () -> Unit) =
        lock.withLock {
            if (!done) {
                action()
                done = true
            }
        }
}
