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
 * A kit declares each endpoint against a node type (`db`, `app`, `control`) and a port. A
 * NodePort endpoint is reached at that port on every host of the node type, at the host's
 * private IP. An endpoint served by one pod is a hostPort, reached only on the node that runs
 * its pod, which [KitEndpointHost] finds. `kit info`, `<kit> status`, `<kit> start` and
 * `status` all report endpoints, so the lookup and the line format live here once.
 */
object KitEndpointAddresses {
    private val log = KotlinLogging.logger {}

    /** One declared [endpoint] resolved to a connectable [address] on one host. */
    data class Resolved(
        val endpoint: KitEndpoint,
        val address: String,
    )

    /** One declared [endpoint] served by one pod that no pod serves now, for [reason]. */
    data class Unresolved(
        val endpoint: KitEndpoint,
        val reason: String,
    )

    /** Where every endpoint of one kit is served: the [resolved] addresses and the [unresolved] endpoints. */
    data class Report(
        val resolved: List<Resolved>,
        val unresolved: List<Unresolved>,
    )

    /**
     * Returns the private IPs of the [hosts] of [nodeType], matched case-insensitively, or an
     * empty list when the cluster has none or the node type is unknown.
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

    /**
     * Resolves every endpoint [config] declares for the kit instance [kitName] to the addresses
     * that serve it, through [endpointHost]. An endpoint served by one pod that no pod serves is
     * reported as [Unresolved] with the reason, so no address of a node that does not serve it is
     * shown and the user sees why. Endpoints sharing a selector are looked up once, so they agree.
     */
    fun resolve(
        config: KitConfig,
        kitName: String,
        hosts: Map<ServerType, List<ClusterHost>>,
        endpointHost: KitEndpointHost,
    ): Report {
        val lookup = endpointHost.once()
        val found = config.endpoints.map { endpoint -> endpoint to lookup.lookup(endpoint, kitName, config.namespace, hosts) }
        return Report(
            resolved =
                found.flatMap { (endpoint, where) ->
                    (where as? KitEndpointHost.Lookup.Served)?.addresses.orEmpty().map { ip -> Resolved(endpoint, endpoint.formatUrl(ip)) }
                },
            unresolved =
                found.mapNotNull { (endpoint, where) ->
                    (where as? KitEndpointHost.Lookup.Unserved)?.let { Unresolved(endpoint, it.reason) }
                },
        )
    }

    /** Why a running kit shows no endpoints when its descriptor cannot be read. */
    const val UNREADABLE_DESCRIPTOR = "cannot read ${Constants.Kit.CONFIG_FILE}"

    /**
     * Resolves the endpoints declared in the `kit.yaml` of the installed kit in [kitDir], through
     * [endpointHost].
     * Fails, and logs why, when the descriptor cannot be read, so a caller can tell an
     * unreadable descriptor from a kit that declares no endpoints.
     */
    fun resolveInstalled(
        kitDir: File,
        hosts: Map<ServerType, List<ClusterHost>>,
        endpointHost: KitEndpointHost,
    ): Result<Report> =
        runCatching {
            installConfigYaml.decodeFromString(
                KitConfig.serializer(),
                File(kitDir, Constants.Kit.CONFIG_FILE).readText(),
            )
        }.onFailure { e ->
            log.warn(e) { "Cannot read ${Constants.Kit.CONFIG_FILE} of kit '${kitDir.name}'; its endpoints are not shown" }
        }.map { config -> resolve(config, kitDir.name, hosts, endpointHost) }

    /** The structured form of one resolved endpoint, as carried by kit events. */
    fun toEndpointAddress(resolved: Resolved): Event.Kit.EndpointAddress =
        Event.Kit.EndpointAddress(
            name = resolved.endpoint.name,
            type =
                resolved.endpoint.type.name
                    .lowercase(),
            address = resolved.address,
        )

    /** The structured form of one endpoint no pod serves, as carried by kit events. */
    fun toUnservedEndpoint(unresolved: Unresolved): Event.Kit.UnservedEndpoint =
        Event.Kit.UnservedEndpoint(name = unresolved.endpoint.name, reason = unresolved.reason)

    /**
     * One line for `status` that says which endpoints of [kitName] no pod serves and why, and
     * where to look, or null when every endpoint is served. Endpoints that share a reason (one
     * pod serves them all) share a clause.
     */
    fun unavailableSummary(
        kitName: String,
        unresolved: List<Unresolved>,
    ): String? {
        if (unresolved.isEmpty()) return null
        val clauses =
            unresolved
                .groupBy({ it.reason }, { it.endpoint.name })
                .map { (reason, names) -> "${names.joinToString(", ")}: $reason" }
        return "${clauses.joinToString("; ")}; run '$kitName status'"
    }

    /** Renders resolved endpoints as indented `name  type  address` lines, then one line for each endpoint no pod serves. */
    fun formatLines(report: Report): String =
        (
            report.resolved.map { toEndpointAddress(it).displayLine() } +
                report.unresolved.map { toUnservedEndpoint(it).displayLine() }
        ).joinToString("\n")
}
