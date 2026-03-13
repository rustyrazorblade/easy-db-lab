package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import org.koin.core.component.KoinComponent
import org.slf4j.MDC

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
        val hostSet =
            hostFilter
                .split(",")
                .filter { it.isNotBlank() }
                .toSet()

        val filteredHosts =
            hosts[serverType]?.filter {
                hostSet.isEmpty() || it.alias in hostSet
            } ?: emptyList()

        if (parallel && filteredHosts.size > 1) {
            val threads =
                filteredHosts.map { host ->
                    kotlin.concurrent.thread(start = true, isDaemon = false) {
                        MDC.put("host", host.alias)
                        try {
                            action(host)
                        } finally {
                            MDC.remove("host")
                        }
                    }
                }
            threads.forEach { it.join() }
        } else {
            filteredHosts.forEach { host ->
                MDC.putCloseable("host", host.alias).use {
                    action(host)
                }
            }
        }
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
