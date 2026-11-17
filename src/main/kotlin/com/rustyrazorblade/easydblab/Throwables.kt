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
