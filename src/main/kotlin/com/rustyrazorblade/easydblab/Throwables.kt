package com.rustyrazorblade.easydblab

import io.fabric8.kubernetes.client.KubernetesClientException

/**
 * The text that tells the operator why a step failed.
 *
 * The Kubernetes client wraps a transport failure (a refused connection, a dead SOCKS tunnel) in a
 * [KubernetesClientException] whose own message is the generic "An error has occurred.". The cause
 * holds the real reason, so this unwraps each such wrapper before it reads the message. Any other
 * exception reads as its own message, or as its string form when it has none.
 */
fun Throwable.reason(): String {
    var current: Throwable = this
    while (current is KubernetesClientException) {
        current = current.cause ?: break
    }
    return current.message ?: current.toString()
}
