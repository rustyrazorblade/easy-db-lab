package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.configuration.ServerType

/**
 * A cluster Service the CLI reads: the Kubernetes Service and port a port-forward targets, plus the
 * node role and port the Socks and Direct seams dial, since those Services run on host networking.
 *
 * @property namespace the Service's namespace
 * @property service the Service's name
 * @property servicePort the Service port a port-forward opens
 * @property nodeRole the node type that runs the workload
 * @property nodePort the port the workload listens on at that node's address
 */
data class ServiceTarget(
    val namespace: String,
    val service: String,
    val servicePort: Int,
    val nodeRole: ServerType,
    val nodePort: Int,
)

/** Where a caller dials a cluster Service it opened through [ServiceAccess]. */
sealed interface ServiceEndpoint {
    /** The host the caller connects to. */
    val host: String

    /** The port the caller connects to. */
    val port: Int

    /** Dial [host]:[port] with no proxy: a Direct node address or a local port-forward. */
    data class Plain(
        override val host: String,
        override val port: Int,
    ) : ServiceEndpoint

    /** Dial [host]:[port] through the SOCKS tunnel listening on `127.0.0.1:`[socksPort]. */
    data class ViaSocks(
        override val host: String,
        override val port: Int,
        val socksPort: Int,
    ) : ServiceEndpoint
}

/**
 * The one way the CLI reaches a cluster Service. It opens (or reuses) access to a [ServiceTarget] for
 * the current command and returns the endpoint to dial, so no client builds a node address or picks a
 * proxy route itself. The implementation is chosen from the cluster's recorded
 * [com.rustyrazorblade.easydblab.configuration.Exposure]; [close] releases whatever it opened.
 */
fun interface ServiceAccess : AutoCloseable {
    /** The endpoint that reaches [target] now. */
    fun open(target: ServiceTarget): ServiceEndpoint

    override fun close() = Unit
}

/**
 * An [open][ServiceAccess.open] on a [ServiceAccess] that was already closed. [ExposureServiceAccess]
 * takes it to mean the exposure changed under the open, and opens again on the new implementation.
 */
class ServiceAccessClosedException(
    message: String,
) : IllegalStateException(message)
