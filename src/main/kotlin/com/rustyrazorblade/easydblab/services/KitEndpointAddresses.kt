package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * Resolves a kit's declared [KitEndpoint]s to concrete addresses on the cluster.
 *
 * A kit declares each endpoint against a node type (`db`, `app`, `control`) and a
 * NodePort. The address a user connects to is that port on every host of the node
 * type, at the host's private IP. `kit info`, `<kit> status`, `<kit> start` and `status`
 * all report endpoints, so the lookup and the line format live here once.
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

    /** Why a running kit shows no endpoints when its descriptor cannot be read. */
    const val UNREADABLE_DESCRIPTOR = "cannot read ${Constants.Kit.CONFIG_FILE}"

    /**
     * Resolves the endpoints declared in the `kit.yaml` of the installed kit in [kitDir].
     * Fails, and logs why, when the descriptor cannot be read, so a caller can tell an
     * unreadable descriptor from a kit that declares no endpoints.
     */
    fun resolveInstalled(
        kitDir: File,
        hosts: Map<ServerType, List<ClusterHost>>,
    ): Result<List<Resolved>> =
        runCatching {
            installConfigYaml.decodeFromString(
                KitConfig.serializer(),
                File(kitDir, Constants.Kit.CONFIG_FILE).readText(),
            )
        }.onFailure { e ->
            log.warn(e) { "Cannot read ${Constants.Kit.CONFIG_FILE} of kit '${kitDir.name}'; its endpoints are not shown" }
        }.map { config -> resolve(config.endpoints, hosts) }

    /** The structured form of one resolved endpoint, as carried by kit events. */
    fun toEndpointAddress(resolved: Resolved): Event.Kit.EndpointAddress =
        Event.Kit.EndpointAddress(
            name = resolved.endpoint.name,
            type =
                resolved.endpoint.type.name
                    .lowercase(),
            address = resolved.address,
        )

    /** Renders resolved endpoints as indented `name  type  address` lines. */
    fun formatLines(resolved: List<Resolved>): String = resolved.joinToString("\n") { toEndpointAddress(it).displayLine() }
}
