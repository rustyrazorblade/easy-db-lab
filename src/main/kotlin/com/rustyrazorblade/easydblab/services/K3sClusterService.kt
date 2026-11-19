package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.messageOrString
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path

/**
 * A started K3s server as an agent needs it: [url] on the control node's private IP, and the
 * node [token] agents register with. [kubeContext] is the context of the kubeconfig the server
 * step wrote, which `up` pins in the cluster state.
 */
data class K3sServer(
    val url: String,
    val token: String,
    val kubeContext: String? = null,
) {
    override fun toString(): String = "K3sServer(url=$url, token=***, kubeContext=$kubeContext)"
}

/**
 * The K3s steps of `up`'s per-node bring-up, over [K3sService] (server) and [K3sAgentService]
 * (agents): the control pipeline's server step, and each node's join step. Every node joins in
 * its own pipeline, so there is no cluster-wide setup call; the caller reads each node's
 * registration from the K8s API.
 */
interface K3sClusterService {
    /**
     * The control pipeline's server step: starts the K3s server on [control], reads the node
     * token, writes the kubeconfig to [kubeconfigPath] and backs it up to S3 when [clusterState]
     * names a bucket. A failed backup is logged and does not fail the step; any other failure
     * throws, naming the part that failed.
     */
    fun startServer(
        control: ClusterHost,
        kubeconfigPath: Path,
        clusterState: ClusterState?,
        useCustomCni: Boolean,
    ): K3sServer

    /**
     * One node's join: writes [host]'s agent config with [server] and its [serverType]'s labels,
     * then starts the agent in [mode] without waiting on systemd. The caller reads the node's
     * registration from the K8s API. A failure throws, naming the part that failed.
     */
    fun joinAgent(
        host: ClusterHost,
        serverType: ServerType,
        server: K3sServer,
        mode: AgentStartMode,
    )

    /**
     * Gets the node labels for a given server type and host.
     *
     * Labels are used by Kubernetes for node selection and scheduling. The agent registers its
     * node with them (`node-label` in the agent config), so a node carries them from the moment it
     * exists and `up` needs no per-node label call afterwards. db and app nodes include
     * [Constants.NODE_ORDINAL_LABEL] parsed from the host alias (e.g., "db0" → 0) to support node
     * pinning for Local Persistent Volumes.
     *
     * @param serverType The server type
     * @param host The cluster host to get labels for
     * @return Map of label key-value pairs
     */
    fun getNodeLabels(
        serverType: ServerType,
        host: ClusterHost,
    ): Map<String, String>
}

/**
 * Default implementation of K3sClusterService.
 *
 * @property k3sService Service for K3s server operations
 * @property k3sAgentService Service for K3s agent operations
 * @property clusterBackupService Service for backing up cluster config to S3
 */
class DefaultK3sClusterService(
    private val k3sService: K3sService,
    private val k3sAgentService: K3sAgentService,
    private val eventBus: EventBus = EventBus(),
    private val clusterBackupService: ClusterBackupService? = null,
) : K3sClusterService {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val K3S_SERVER_PORT = 6443
    }

    override fun startServer(
        control: ClusterHost,
        kubeconfigPath: Path,
        clusterState: ClusterState?,
        useCustomCni: Boolean,
    ): K3sServer {
        val host = control.toHost()
        eventBus.emit(Event.K3s.ServerStarting(control.alias))
        k3sService.startServer(host, useCustomCni).orFail("K3s server start") { eventBus.emit(Event.K3s.ServerStartFailed(it)) }
        val token = k3sService.getNodeToken(host).orFail("K3s node token retrieval") { eventBus.emit(Event.K3s.NodeTokenFailed(it)) }
        val kubeContext =
            k3sService
                .downloadAndConfigureKubeconfig(host, kubeconfigPath)
                .orFail("Kubeconfig download") { eventBus.emit(Event.K3s.KubeconfigFailed(it)) }
        eventBus.emit(Event.K3s.KubeconfigWritten(kubeconfigPath.fileName.toString()))
        eventBus.emit(Event.K3s.KubeconfigInstruction)
        backupKubeconfig(kubeconfigPath, clusterState)
        return K3sServer("https://${control.privateIp}:$K3S_SERVER_PORT", token, kubeContext)
    }

    override fun joinAgent(
        host: ClusterHost,
        serverType: ServerType,
        server: K3sServer,
        mode: AgentStartMode,
    ) {
        val agent = host.toHost()
        val labels = getNodeLabels(serverType, host)
        eventBus.emit(Event.K3s.AgentConfiguring(agent.alias, labels.toString()))
        k3sAgentService
            .configure(agent, server.url, server.token, labels)
            .orFail("Failed to configure K3s agent", separator = ": ") {
                eventBus.emit(Event.K3s.AgentConfigFailed(agent.alias, it))
            }
        k3sAgentService
            .start(agent, mode)
            .orFail("Failed to start K3s agent", separator = ": ") { eventBus.emit(Event.K3s.AgentStartFailed(agent.alias, it)) }
        log.info { "Started K3s agent on ${agent.alias}" }
    }

    /** The value, or [report] the failure and throw it, prefixed by [part]. */
    private fun <T> Result<T>.orFail(
        part: String,
        separator: String = " failed: ",
        report: (String) -> Unit,
    ): T =
        getOrElse { e ->
            val message = e.messageOrString()
            log.error(e) { "$part$separator$message" }
            report(message)
            throw IllegalStateException("$part$separator$message", e)
        }

    /** A copy of the kubeconfig in the cluster's bucket, when it has one. A failure is logged, not fatal. */
    private fun backupKubeconfig(
        kubeconfigPath: Path,
        clusterState: ClusterState?,
    ) {
        val backup = clusterBackupService ?: return
        val state = clusterState?.takeIf { it.s3Bucket != null } ?: return
        backup
            .backupKubeconfig(kubeconfigPath, state)
            .onFailure { log.warn(it) { "Failed to back up the kubeconfig to S3 (non-fatal)" } }
    }

    override fun getNodeLabels(
        serverType: ServerType,
        host: ClusterHost,
    ): Map<String, String> =
        when (serverType) {
            ServerType.Cassandra, ServerType.Stress -> {
                // Parse ordinal from alias: "db0" → 0, "app3" → 3, etc.
                val ordinal = serverType.ordinalOf(host.alias) ?: 0
                mapOf(
                    Constants.NODE_TYPE_LABEL to serverType.serverType,
                    Constants.NODE_ORDINAL_LABEL to ordinal.toString(),
                )
            }
            ServerType.Control -> emptyMap()
        }
}
