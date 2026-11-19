package com.rustyrazorblade.easydblab

import io.fabric8.kubernetes.client.KubernetesClientException

/**
 * The text that tells the operator why a step failed.
 *
 * The Kubernetes client wraps a transport failure (a refused connection, a dead SOCKS tunnel) in a
 * [KubernetesClientException] whose own message is the generic "An error has occurred." or is empty:
 * the cause holds the real reason, so such a wrapper reads as its cause. A wrapper whose message says
 * something (the operation that failed, for example) keeps it, followed by the cause's reason. Any
 * other exception reads as its own message, or as its string form when it has none.
 */
fun Throwable.reason(): String {
    val cause = cause
    return when {
        this !is KubernetesClientException || cause == null -> messageOrString()
        message.isNullOrBlank() || message == GENERIC_CLIENT_MESSAGE -> cause.reason()
        else -> "$message: ${cause.reason()}"
    }
}

/** The text the Kubernetes client puts on an exception that only wraps a cause. */
private const val GENERIC_CLIENT_MESSAGE = "An error has occurred."

/**
 * The exception's own message, or its string form (class name included) when it has none. Every
 * place that reports a failure as text reads it through this, so none of them prints "null".
 */
fun Throwable.messageOrString(): String = message ?: toString()

/**
 * Runs [block] and captures an [Exception] it throws as a failed [Result], like [runCatching], but
 * an [Error] (an out-of-memory error, a stack overflow, a failed assertion) is rethrown, not
 * captured. A captured [InterruptedException] sets the thread's interrupt flag again, so the
 * caller's own blocking calls still see the interrupt. Use it where a boundary must turn any
 * failure of the work into a report or a fallback, and an error in the JVM itself must still stop
 * the process.
 */
inline fun <T> runCatchingException(block: () -> T): Result<T> =
    runCatching(block).onFailure {
        if (it !is Exception) throw it
        if (it is InterruptedException) Thread.currentThread().interrupt()
    }

/**
 * Like [runCatchingException], except an [InterruptedException] is not captured: it sets the
 * thread's interrupt flag again and propagates, so a poll loop or a retry stops when its thread is
 * interrupted instead of treating the interrupt as one more failed attempt. Use it where a failure
 * of the work is a value to act on but an interrupt is a request to stop.
 */
inline fun <T> runCatchingInterruptibly(block: () -> T): Result<T> =
    runCatchingException(block).onFailure {
        if (it is InterruptedException) throw it
    }

/**
 * Runs [block]; when it throws, runs [cleanup] and then rethrows the original failure. A failure of
 * [cleanup] is attached to the original with [Throwable.addSuppressed], so the error that started
 * the cleanup is the one the caller sees. An [Error] from [cleanup] is not captured. Use it where
 * a half-finished step must undo itself (a staging directory, a connection opened so far).
 */
inline fun <T> runCleaningUpOnFailure(
    cleanup: () -> Unit,
    block: () -> T,
): T =
    runCatching(block)
        .onFailure { original ->
            runCatchingException(cleanup).onFailure(original::addSuppressed)
        }.getOrThrow()

/**
 * Runs [action] on the [Exception] a failed result holds. A failure that is not an [Exception]
 * (an [Error]) is rethrown. [runCatchingException] never captures an [Error], so on its results
 * the rethrow is not reached.
 */
inline fun <T> Result<T>.onException(action: (Exception) -> Unit): Result<T> =
    onFailure {
        if (it is Exception) action(it) else throw it
    }
