package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Resolves a kit's declared [KitEndpoint]s to concrete addresses on the cluster.
 *
 * A kit declares each endpoint against a node type (`db`, `app`, `control`) and a
 * NodePort. The address a user connects to is that port on every host of the node
 * type, at the host's private IP. `kit info`, `<kit> status` and `<kit> start` all
 * report endpoints, so the lookup and the line format live here once.
 */
object KitEndpointAddresses {
    private val log = KotlinLogging.logger {}

    /** One declared [endpoint] resolved to a connectable [address] on one host. */
    data class Resolved(
        val endpoint: KitEndpoint,
        val address: String,
    )

    /**
     * Returns the private IPs of the [hosts] of [nodeType], or an empty list when the
     * cluster has none or the node type is unknown.
     */
    fun privateIps(
        nodeType: String,
        hosts: Map<ServerType, List<ClusterHost>>,
    ): List<String> {
        val serverType =
            runCatching { ServerType.from(nodeType.lowercase()) }
                .getOrElse {
                    log.warn { "Unknown node-type '$nodeType' in kit endpoint" }
                    return emptyList()
                }
        return hosts[serverType]?.map { it.privateIp }.orEmpty()
    }

    /** Resolves every endpoint against every host of its node type. */
    fun resolve(
        endpoints: List<KitEndpoint>,
        hosts: Map<ServerType, List<ClusterHost>>,
    ): List<Resolved> =
        endpoints.flatMap { endpoint ->
            privateIps(endpoint.nodeType, hosts).map { ip -> Resolved(endpoint, endpoint.formatUrl(ip)) }
        }

    /** Renders resolved endpoints as indented `name  type  address` lines. */
    fun formatLines(resolved: List<Resolved>): String =
        resolved.joinToString("\n") { (endpoint, address) ->
            "  %-20s  %-8s  %s".format(endpoint.name, endpoint.type.name.lowercase(), address)
        }
}
