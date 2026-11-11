package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import org.koin.core.component.KoinComponent
import java.util.concurrent.ConcurrentHashMap

/**
 * Outcome of running a per-host action against a single host.
 *
 * Pairs the host with its [Result] so a caller can report success and failure per host rather than
 * only learning that "something, somewhere" failed.
 */
data class HostResult<T>(
    val host: ClusterHost,
    val result: Result<T>,
)

/**
 * Service for iterating over cluster hosts and executing operations.
 *
 * This service provides a clean abstraction for remote host operations,
 * separating host iteration concerns from configuration file writing.
 *
 * Usage:
 * ```
 * hostOperationsService.withHosts(ServerType.Cassandra, "db0,db1") { host ->
 *     remoteOperationsService.executeRemotely(host.toHost(), "nodetool status")
 * }
 * ```
 */
class HostOperationsService(
    private val clusterStateManager: ClusterStateManager,
) : KoinComponent {
    /**
     * Executes an action on filtered hosts of a specific server type.
     *
     * @param serverType The type of server to filter (Cassandra, Stress, Control)
     * @param hostFilter Comma-separated list of host aliases to include (empty means all)
     * @param parallel If true, execute operations in parallel using threads
     * @param action The action to perform on each host
     */
    fun withHosts(
        serverType: ServerType,
        hostFilter: String = "",
        parallel: Boolean = false,
        action: (ClusterHost) -> Unit,
    ) {
        val clusterState = clusterStateManager.load()
        withHosts(clusterState.hosts, serverType, hostFilter, parallel, action)
    }

    /**
     * Executes an action on filtered hosts from a provided hosts map.
     *
     * Use this overload when you already have the hosts map loaded
     * (e.g., from a working copy of ClusterState).
     *
     * @param hosts Map of server types to their hosts
     * @param serverType The type of server to filter
     * @param hostFilter Comma-separated list of host aliases to include (empty means all)
     * @param parallel If true, execute operations in parallel
     * @param action The action to perform on each host
     */
    fun withHosts(
        hosts: Map<ServerType, List<ClusterHost>>,
        serverType: ServerType,
        hostFilter: String = "",
        parallel: Boolean = false,
        action: (ClusterHost) -> Unit,
    ) {
        val filteredHosts = filteredHosts(hosts, serverType, hostFilter)

        if (parallel && filteredHosts.size > 1) {
            val failures =
                collectFromHosts(hosts, serverType, hostFilter, parallel = true, action = action)
                    .mapNotNull { it.result.exceptionOrNull() }

            // Every host ran. Rethrow the first failure with the rest attached, so an operator
            // fixing a multi-node problem sees all of it at once instead of one host per run.
            failures.firstOrNull()?.let { first ->
                failures.drop(1).filter { it !== first }.forEach { first.addSuppressed(it) }
                throw first
            }
        } else {
            filteredHosts.forEach(action)
        }
    }

    /**
     * Executes an action on filtered hosts and returns each host's outcome instead of throwing.
     *
     * Every host runs regardless of what happens on any other host; a failing action is captured
     * as a failed [Result] on that host's [HostResult]. Use this when the caller needs to report
     * per-host success and failure (e.g. installing a version across a cluster); use [withHosts]
     * when the first failure should simply abort the command.
     *
     * @param hosts Map of server types to their hosts
     * @param serverType The type of server to filter
     * @param hostFilter Comma-separated list of host aliases to include (empty means all)
     * @param parallel If true, execute operations in parallel
     * @param action The action to perform on each host
     * @return One [HostResult] per targeted host, in declaration order
     */
    fun <T> collectFromHosts(
        hosts: Map<ServerType, List<ClusterHost>>,
        serverType: ServerType,
        hostFilter: String = "",
        parallel: Boolean = false,
        action: (ClusterHost) -> T,
    ): List<HostResult<T>> {
        val filteredHosts = filteredHosts(hosts, serverType, hostFilter)

        if (!parallel || filteredHosts.size <= 1) {
            return filteredHosts.map { HostResult(it, runCatching { action(it) }) }
        }

        // Keyed by position, not alias: two hosts sharing an alias would silently overwrite
        // each other's outcome and misattribute a failure.
        val results = ConcurrentHashMap<Int, Result<T>>()
        filteredHosts
            .mapIndexed { index, host ->
                kotlin.concurrent.thread(start = true, isDaemon = false) {
                    results[index] = runCatching { action(host) }
                }
            }.forEach { it.join() }

        return filteredHosts.mapIndexed { index, host ->
            HostResult(
                host,
                results[index]
                    ?: Result.failure(IllegalStateException("No result recorded for host ${host.alias}")),
            )
        }
    }

    /**
     * Returns the hosts of a given server type after applying a comma-separated alias filter.
     *
     * This is the same selection logic [withHosts] uses to decide which hosts to act on, exposed
     * so callers (e.g. pre-flight validation) can inspect the exact target set without executing
     * an action against it.
     *
     * @param hosts Map of server types to their hosts
     * @param serverType The type of server to filter
     * @param hostFilter Comma-separated list of host aliases to include (empty means all)
     * @return The filtered hosts, in declaration order
     */
    fun filteredHosts(
        hosts: Map<ServerType, List<ClusterHost>>,
        serverType: ServerType,
        hostFilter: String = "",
    ): List<ClusterHost> {
        val hostSet =
            hostFilter
                .split(",")
                .filter { it.isNotBlank() }
                .toSet()

        return hosts[serverType]?.filter {
            hostSet.isEmpty() || it.alias in hostSet
        } ?: emptyList()
    }

    /**
     * Gets all hosts of a specific server type.
     *
     * @param serverType The type of server to get hosts for
     * @return List of ClusterHost for the given server type
     */
    fun getHosts(serverType: ServerType): List<ClusterHost> {
        val clusterState = clusterStateManager.load()
        return clusterState.hosts[serverType] ?: emptyList()
    }

    /**
     * Gets all hosts of a specific server type from a provided hosts map.
     *
     * @param hosts Map of server types to their hosts
     * @param serverType The type of server to get hosts for
     * @return List of ClusterHost for the given server type
     */
    fun getHosts(
        hosts: Map<ServerType, List<ClusterHost>>,
        serverType: ServerType,
    ): List<ClusterHost> = hosts[serverType] ?: emptyList()
}
