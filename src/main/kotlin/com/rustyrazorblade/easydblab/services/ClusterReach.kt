package com.rustyrazorblade.easydblab.services

import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.file.NoSuchFileException

/**
 * Decides whether a failed step of the pre-teardown save never reached the cluster.
 *
 * A step marks the workload it is about to change before it makes the call, because a call that
 * times out may still have changed it. When the call fails in a way that proves the request never
 * left (the tunnel is down, the connection was refused, there is no kubeconfig because K3s never
 * started), that mark is wrong, and `down` must not report the workload as stopped or deleted. A
 * timeout, a reset or an HTTP error says nothing of the kind, so those keep the mark.
 */
object ClusterReach {
    /** Depth limit for the cause chain, so a self-referencing chain cannot loop. */
    private const val MAX_CAUSE_DEPTH = 16

    /**
     * Returns true when [failure], or a cause of it, shows that the request never reached the cluster.
     */
    fun neverReached(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .any { it.provesNoRequestWasSent() }

    private fun Throwable.provesNoRequestWasSent(): Boolean =
        when (this) {
            is ConnectException, is UnknownHostException, is NoRouteToHostException -> true
            is FileNotFoundException, is NoSuchFileException -> true
            // The SOCKS client reports a tunnel that is down or cannot connect as a SocketException
            // whose message names SOCKS. A bare SocketException (a reset) says the request was sent.
            is SocketException -> message.orEmpty().contains("SOCKS")
            else -> false
        }
}
