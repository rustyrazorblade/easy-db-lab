package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.LocalPortForward
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.InetAddress

/**
 * Opens one Kubernetes port-forward to a Service on a local port. The Kubernetes boundary of
 * [PortForwardServiceAccess], so its reuse and failure decisions are tested without a cluster.
 */
fun interface ServiceForwarder : AutoCloseable {
    /** Forwards `127.0.0.1:`[localPort] to [target]'s Service port; throws when it cannot. */
    fun forward(
        target: ServiceTarget,
        localPort: Int,
    ): LocalPortForward

    /** Releases what the forwarder holds, such as its Kubernetes client; nothing by default. */
    override fun close() = Unit
}

/**
 * The port of [service] a port-forward to [port] opens. A kit endpoint names its NodePort (30000-32767),
 * which a Service port-forward cannot open, so a port that is one of [service]'s NodePorts maps to the
 * Service port behind it; a Service port is used as it is.
 *
 * @throws IllegalStateException when [service] exposes [port] neither way
 */
fun servicePortFor(
    service: Service,
    port: Int,
): Int {
    val ports = service.spec?.ports.orEmpty()
    return ports.firstOrNull { it.port == port }?.port
        ?: ports.firstOrNull { it.nodePort == port }?.port
        ?: error(
            "Service ${service.metadata.namespace}/${service.metadata.name} exposes no port $port " +
                "(its ports: ${ports.joinToString { p -> listOfNotNull(p.port, p.nodePort).joinToString("/") }})",
        )
}

/**
 * [ServiceForwarder] over Fabric8: verifies the Service exists, then forwards to it bound to the
 * IPv4 loopback only, so nothing off this machine can reach the forward. A kit endpoint's NodePort
 * is mapped to the Service port behind it ([servicePortFor]). The client is built on the first
 * forward and closed by [close].
 *
 * @param newClient builds the cluster's Kubernetes client from the recorded kubeconfig and pinned context
 */
class Fabric8ServiceForwarder(
    newClient: () -> KubernetesClient,
) : ServiceForwarder {
    private val client = lazy(newClient)

    override fun forward(
        target: ServiceTarget,
        localPort: Int,
    ): LocalPortForward {
        val service =
            client.value
                .services()
                .inNamespace(target.namespace)
                .withName(target.service)
        val existing = checkNotNull(service.get()) { "Service ${target.namespace}/${target.service} does not exist" }
        return service.portForward(
            servicePortFor(existing, target.servicePort),
            InetAddress.getByName(Constants.Network.LOOPBACK_ADDRESS),
            localPort,
        )
    }

    override fun close() {
        if (client.isInitialized()) client.value.close()
    }
}

/**
 * [ServiceAccess] for a cluster easy-db-lab does not own: a Kubernetes port-forward to the Service,
 * bound to `127.0.0.1` on a port from [localPorts] (the loopback range, 20000-29999).
 *
 * One forward is held per Service and reused for the rest of the command; a forward that has died
 * (a long-running server or REPL can outlive one) is closed and reopened on the next [open], which
 * resolves the Service's pod again. Dead means closed, or with an error recorded since it opened:
 * Fabric8 keeps `isAlive` true after the pod a Service forward resolved is replaced. A forward
 * whose port another process bound after it was chosen is retried once on a fresh port. A forward
 * that cannot be opened, or reports an error at once, fails naming the namespace, the Service, the port
 * and the Kubernetes error. [close] closes every forward, and is registered to run when the CLI exits.
 *
 * @param forwarder opens one forward
 * @param localPorts a free loopback port for each new forward
 */
class PortForwardServiceAccess(
    private val forwarder: ServiceForwarder,
    private val localPorts: () -> Int,
) : ServiceAccess {
    private val log = KotlinLogging.logger {}
    private val forwards = mutableMapOf<ServiceTarget, LocalPortForward>()

    @Synchronized
    override fun open(target: ServiceTarget): ServiceEndpoint {
        check(target.service.isNotBlank()) {
            "No Service is known for port ${target.nodePort}: no manifest of the kit declares it as a NodePort. " +
                "Name the Service in the endpoint's kit.yaml entry with `service:` (and `service-port:`)"
        }
        val forward =
            forwards[target]?.takeIf { it.isUsable } ?: run {
                forwards.remove(target)?.let { replace(target, it) }
                openForward(target).also { forwards[target] = it }
            }
        return ServiceEndpoint.Plain(LOOPBACK, forward.localPort)
    }

    /** Closes a held forward that can no longer carry connections, saying why it is replaced. */
    private fun replace(
        target: ServiceTarget,
        dead: LocalPortForward,
    ) {
        log.warn(dead.firstError) {
            "Port-forward $LOOPBACK:${dead.localPort} to ${target.namespace}/${target.service} stopped working; reopening it"
        }
        dead.closeQuietly()
    }

    /**
     * Opens a verified forward on a fresh loopback port. Another process can bind the port between
     * [localPorts] choosing it and the forward binding it, so a bind failure is retried once on a new
     * port; any other failure is the cluster's answer and fails at once. `executeCallable`, because a
     * forwarder may throw the checked `BindException` itself, which a plain decorator never retries.
     */
    private fun openForward(target: ServiceTarget): LocalPortForward {
        val retry = portForwardBindRetry("port-forward-${target.namespace}-${target.service}")
        val forward =
            try {
                retry.executeCallable { openVerified({ forwarder.forward(target, localPorts()) }) }
            } catch (e: Exception) {
                throw forwardFailure(target, e)
            }
        log.debug {
            "Forwarding $LOOPBACK:${forward.localPort} to ${target.namespace}/${target.service}:${target.servicePort}"
        }
        return forward
    }

    private fun forwardFailure(
        target: ServiceTarget,
        cause: Throwable,
    ) = IllegalStateException(
        "Cannot port-forward to Service ${target.namespace}/${target.service} port ${target.servicePort}: ${cause.message}",
        cause,
    )

    @Synchronized
    override fun close() {
        forwards.values.forEach { it.closeQuietly() }
        forwards.clear()
        forwarder.close()
    }

    private companion object {
        const val LOOPBACK = Constants.Network.LOOPBACK_ADDRESS
    }
}
