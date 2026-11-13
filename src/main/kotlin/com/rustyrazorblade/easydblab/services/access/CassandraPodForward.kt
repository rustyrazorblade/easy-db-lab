package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.LocalPortForward
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * One pod and port a port-forward targets: the Cassandra pod the single-endpoint CQL session talks to.
 *
 * @property namespace the pod's namespace
 * @property pod the pod's name, a StatefulSet ordinal such as `cassandra-pods-0`
 * @property port the pod's native transport port
 */
data class PodTarget(
    val namespace: String,
    val pod: String,
    val port: Int,
)

/** Opens one Kubernetes port-forward to a pod on a local port: the Kubernetes boundary of [CassandraPodForward]. */
fun interface PodForwarder {
    /** Forwards `127.0.0.1:`[localPort] to [target]; throws when it cannot. */
    fun forward(
        target: PodTarget,
        localPort: Int,
    ): LocalPortForward
}

/**
 * [PodForwarder] over Fabric8, bound to the IPv4 loopback only. The client is built on the first
 * forward and closed by [close].
 *
 * @param newClient builds the cluster's Kubernetes client from the recorded kubeconfig
 */
class Fabric8PodForwarder(
    newClient: () -> KubernetesClient,
) : PodForwarder,
    AutoCloseable {
    private val client = lazy(newClient)

    override fun forward(
        target: PodTarget,
        localPort: Int,
    ): LocalPortForward =
        client.value
            .pods()
            .inNamespace(target.namespace)
            .withName(target.pod)
            .portForward(target.port, InetAddress.getByName(Constants.Proxy.TUNNEL_LISTEN_ADDRESS), localPort)

    override fun close() {
        if (client.isInitialized()) client.value.close()
    }
}

/**
 * The port-forward the single-endpoint CQL session dials: one Cassandra pod on one local port.
 *
 * The local port is picked once and kept. When the forward dies (a pod restart, a dropped
 * connection), the next [endpoint] reopens it on the same port, so the driver, which holds that
 * address as its only node, reconnects on its own. A forward that cannot be opened fails naming the
 * namespace, the pod, the port and the Kubernetes error.
 *
 * @param pod the pod to forward to
 * @param forwarder opens one forward
 * @param localPorts a free loopback port, asked once
 */
class CassandraPodForward(
    private val pod: PodTarget,
    private val forwarder: PodForwarder,
    localPorts: () -> Int,
) : AutoCloseable {
    private val log = KotlinLogging.logger {}
    private val localPort by lazy(localPorts)
    private var forward: LocalPortForward? = null

    /** The loopback address of a live forward to the pod, opening or reopening it as needed. */
    @Synchronized
    fun endpoint(): InetSocketAddress {
        val live = forward?.takeIf { it.isAlive } ?: reopen()
        return InetSocketAddress(Constants.Proxy.TUNNEL_LISTEN_ADDRESS, live.localPort)
    }

    private fun reopen(): LocalPortForward {
        forward?.closeQuietly()
        forward = null
        val opened =
            runCatching { forwarder.forward(pod, localPort) }
                .getOrElse { throw failure(it) }
        if (opened.errorOccurred()) {
            val error = (opened.clientThrowables + opened.serverThrowables).firstOrNull()
            opened.closeQuietly()
            throw failure(error ?: IllegalStateException("unknown error"))
        }
        log.debug { "Forwarding 127.0.0.1:$localPort to pod ${pod.namespace}/${pod.pod}:${pod.port}" }
        forward = opened
        return opened
    }

    private fun failure(cause: Throwable) =
        IllegalStateException("Cannot port-forward to pod ${pod.namespace}/${pod.pod} port ${pod.port}: ${cause.message}", cause)

    @Synchronized
    override fun close() {
        forward?.closeQuietly()
        forward = null
        (forwarder as? AutoCloseable)?.close()
    }

    private fun LocalPortForward.closeQuietly() {
        runCatching { close() }.onFailure { log.debug(it) { "Closing a port-forward failed" } }
    }
}
