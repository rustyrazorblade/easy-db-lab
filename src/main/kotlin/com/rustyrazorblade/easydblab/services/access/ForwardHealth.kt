package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.client.LocalPortForward
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import java.net.BindException
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * Whether a held port-forward can still carry connections.
 *
 * Fabric8's [LocalPortForward.isAlive] goes false only on `close()` or when the local accept fails.
 * A forward whose pod was replaced keeps `isAlive` true while every connection through it fails, and
 * only [LocalPortForward.errorOccurred] shows it, so a holder must check both before reusing one.
 */
internal val LocalPortForward.isUsable: Boolean
    get() = isAlive && !errorOccurred()

/** The first error the forward recorded, client side or server side, or null when it has none. */
internal val LocalPortForward.firstError: Throwable?
    get() = (clientThrowables + serverThrowables).firstOrNull()

/** Whether [this] failure, or any cause behind it, is a local bind failure (Fabric8 wraps it). */
internal val Throwable.isBindFailure: Boolean
    get() = generateSequence(this) { it.cause }.any { it is BindException }

/**
 * The retry for opening a Service port-forward on a loopback port another process can take between
 * choosing it and binding it. Retries only a bind failure ([isBindFailure]): the caller picks a fresh
 * port per attempt, and any other failure is the cluster's answer. No wait, since the next attempt
 * binds another port and nothing has to settle. [Constants.K8s.PORT_FORWARD_BIND_ATTEMPTS] attempts.
 */
internal fun portForwardBindRetry(name: String): Retry =
    Retry.of(
        name,
        RetryConfig
            .custom<Any>()
            .maxAttempts(Constants.K8s.PORT_FORWARD_BIND_ATTEMPTS)
            .waitDuration(Duration.ZERO)
            .retryOnException { it.isBindFailure }
            .build(),
    )

/** Closes the forward, logging rather than throwing when the close itself fails. */
internal fun LocalPortForward.closeQuietly() {
    runCatching { close() }.onFailure { log.debug(it) { "Closing a port-forward failed" } }
}

/**
 * Opens a forward with [open] and returns it only once it reports no error, so a forward that failed
 * at once (a forbidden pod, a missing Service) never reaches a caller. A forward that reports an
 * error is closed. Either failure, the open's own or the reported one, is thrown as [failure] makes it.
 */
internal fun openVerified(
    open: () -> LocalPortForward,
    failure: (Throwable) -> Throwable = { it },
): LocalPortForward {
    val forward = runCatching(open).getOrElse { throw failure(it) }
    if (forward.errorOccurred()) {
        val error = forward.firstError
        forward.closeQuietly()
        throw failure(error ?: IllegalStateException("unknown error"))
    }
    return forward
}
