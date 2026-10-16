package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants

/**
 * Which services of a compose file can reach each other. Compose puts a service that names no
 * network on the project's `default` network, and a service with a `network_mode` on none of the
 * project's networks.
 */
object ComposeNetworks {
    private const val DEFAULT = "default"

    /** The project networks [service] joins in [compose]. */
    fun of(
        compose: ComposeFile,
        service: String,
    ): Set<String> {
        val definition = compose.services.getValue(service)
        return when {
            definition.networkMode != null -> emptySet()
            else -> definition.networks?.toSet() ?: setOf(DEFAULT)
        }
    }

    /** Every other service of [compose] that shares a network with [service]. */
    fun sharingWith(
        compose: ComposeFile,
        service: String,
    ): Set<String> {
        val networks = of(compose, service)
        return compose.services.keys
            .filter { it != service && of(compose, it).any(networks::contains) }
            .toSet()
    }

    /** The services that may share a network with the signer, which signs any request with the operator's credentials. */
    val SIGNER_PEERS: Set<String> =
        setOf(Constants.LocalStack.Services.STORE_GATE, Constants.LocalStack.Services.STORE_CHECK)
}
