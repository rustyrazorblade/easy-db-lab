package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.toHost
import com.rustyrazorblade.easydblab.output.OutputHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of K3s cluster setup operation.
 *
 * @property serverStarted Whether the K3s server was successfully started
 * @property nodeToken The node token for agent registration (null if server failed)
 * @property kubeconfigWritten Whether kubeconfig was successfully downloaded
 * @property kubeconfigBackedUp Whether kubeconfig was successfully backed up to S3
 * @property agentResults Results of agent setup per host alias
 * @property errors Map of error descriptions to exceptions
 */
data class K3sSetupResult(
    val serverStarted: Boolean,
    val nodeToken: String? = null,
    val kubeconfigWritten: Boolean = false,
    val kubeconfigBackedUp: Boolean = false,
    val agentResults: Map<String, AgentSetupResult> = emptyMap(),
    val errors: Map<String, Exception> = emptyMap(),
) {
    val isSuccessful: Boolean
        get() = serverStarted && errors.isEmpty() && agentResults.values.all { it.success }
}

/**
 * Result of setting up a single K3s agent.
 *
 * @property alias The host alias
 * @property success Whether agent setup succeeded
 * @property error Error if setup failed, null otherwise
 */
data class AgentSetupResult(
    val alias: String,
    val success: Boolean,
    val error: Exception? = null,
)

/**
 * Configuration for K3s cluster setup.
 *
 * @property controlHost The control node to run K3s server
 * @property workerHosts Map of ServerType to list of worker hosts
 * @property kubeconfigPath Local path to write kubeconfig
 * @property hostFilter Optional filter for which hosts to include (comma-separated aliases)
 * @property clusterState Optional ClusterState for backing up kubeconfig to S3
 */
data class K3sClusterConfig(
    val controlHost: ClusterHost,
    val workerHosts: Map<ServerType, List<ClusterHost>>,
    val kubeconfigPath: Path,
    val hostFilter: String = "",
    val clusterState: ClusterState? = null,
)

/**
 * Service for orchestrating K3s cluster setup.
 *
 * This service coordinates the K3sService (server) and K3sAgentService (workers)
 * to set up a complete K3s cluster. It handles:
 * - Starting the K3s server on the control node
 * - Retrieving the node token for agent registration
 * - Downloading and configuring kubeconfig for local kubectl access
 * - Configuring and starting K3s agents on worker nodes in parallel
 *
 * The orchestration follows this workflow:
 * 1. Start K3s server on control node
 * 2. Retrieve node token from server
 * 3. Download and configure kubeconfig
 * 4. For each worker server type (Cassandra, Stress):
 *    a. Configure K3s agent with server URL, token, and node labels
 *    b. Start K3s agent service
 */
interface K3sClusterService {
    /**
     * Sets up a complete K3s cluster.
     *
     * This method orchestrates the full cluster setup including:
     * - Server start on control node
     * - Token retrieval
     * - Kubeconfig download and configuration
     * - Agent configuration and start on all worker nodes
     *
     * @param config K3s cluster configuration
     * @return Result containing setup status and any errors
     */
    fun setupCluster(config: K3sClusterConfig): K3sSetupResult

    /**
     * Gets the node labels for a given server type.
     *
     * Labels are used by Kubernetes for node selection and scheduling.
     *
     * @param serverType The server type
     * @return Map of label key-value pairs
     */
    fun getNodeLabels(serverType: ServerType): Map<String, String>
}

/**
 * Default implementation of K3sClusterService.
 *
 * @property k3sService Service for K3s server operations
 * @property k3sAgentService Service for K3s agent operations
 * @property outputHandler Handler for user-facing messages
 * @property clusterBackupService Service for backing up cluster config to S3
 */
class DefaultK3sClusterService(
    private val k3sService: K3sService,
    private val k3sAgentService: K3sAgentService,
    private val outputHandler: OutputHandler,
    private val clusterBackupService: ClusterBackupService? = null,
) : K3sClusterService {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val K3S_SERVER_PORT = 6443
    }

    override fun setupCluster(config: K3sClusterConfig): K3sSetupResult {
        outputHandler.handleMessage("Starting K3s cluster...")

        val errors = mutableMapOf<String, Exception>()
        val controlHost = config.controlHost

        // Step 1: Start K3s server
        outputHandler.handleMessage("Starting K3s server on control node ${controlHost.alias}...")
        val serverStartResult = k3sService.start(controlHost.toHost())
        if (serverStartResult.isFailure) {
            val error = serverStartResult.exceptionOrNull()!!
            log.error(error) { "Failed to start K3s server on ${controlHost.alias}" }
            outputHandler.handleError("Failed to start K3s server: ${error.message}")
            errors["K3s server start"] = Exception(error.message, error)
            return K3sSetupResult(
                serverStarted = false,
                errors = errors,
            )
        }
        log.info { "Successfully started K3s server on ${controlHost.alias}" }

        // Step 2: Get node token
        val tokenResult = k3sService.getNodeToken(controlHost.toHost())
        if (tokenResult.isFailure) {
            val error = tokenResult.exceptionOrNull()!!
            log.error(error) { "Failed to retrieve K3s node token from ${controlHost.alias}" }
            outputHandler.handleError("Failed to retrieve K3s node token: ${error.message}")
            errors["K3s node token retrieval"] = Exception(error.message, error)
            return K3sSetupResult(
                serverStarted = true,
                errors = errors,
            )
        }
        val nodeToken = tokenResult.getOrThrow()
        log.info { "Retrieved K3s node token from ${controlHost.alias}" }

        // Step 3: Download and configure kubeconfig
        var kubeconfigWritten = false
        var kubeconfigBackedUp = false
        val kubeconfigResult =
            k3sService.downloadAndConfigureKubeconfig(
                controlHost.toHost(),
                config.kubeconfigPath,
            )
        kubeconfigResult
            .onFailure { error ->
                log.error(error) { "Failed to download kubeconfig from ${controlHost.alias}" }
                outputHandler.handleError("Failed to download kubeconfig: ${error.message}")
                errors["Kubeconfig download"] = Exception(error.message, error)
            }.onSuccess {
                kubeconfigWritten = true
                outputHandler.handleMessage("Kubeconfig written to ${config.kubeconfigPath.fileName}")
                outputHandler.handleMessage("Use 'source env.sh' to configure kubectl for cluster access")

                // Step 3b: Backup kubeconfig to S3 if ClusterState is available
                kubeconfigBackedUp = backupKubeconfigToS3(config, errors)
            }

        // Step 4: Configure and start agents on worker nodes
        val serverUrl = "https://${controlHost.privateIp}:$K3S_SERVER_PORT"
        val agentResults = setupAgents(config, serverUrl, nodeToken)

        // Collect agent errors
        agentResults.values
            .filter { !it.success }
            .forEach { result ->
                result.error?.let { error ->
                    errors["Agent ${result.alias}"] = error
                }
            }

        outputHandler.handleMessage("K3s cluster started successfully")

        return K3sSetupResult(
            serverStarted = true,
            nodeToken = nodeToken,
            kubeconfigWritten = kubeconfigWritten,
            kubeconfigBackedUp = kubeconfigBackedUp,
            agentResults = agentResults,
            errors = errors,
        )
    }

    /**
     * Backs up the kubeconfig to S3 if ClusterBackupService and ClusterState are available.
     *
     * @param config The K3s cluster configuration containing ClusterState
     * @param errors Mutable map to collect any backup errors
     * @return true if backup was successful, false otherwise
     */
    @Suppress("UnusedParameter")
    private fun backupKubeconfigToS3(
        config: K3sClusterConfig,
        errors: MutableMap<String, Exception>,
    ): Boolean {
        if (clusterBackupService == null) {
            log.debug { "ClusterBackupService not available, skipping S3 backup" }
            return false
        }

        val clusterState = config.clusterState
        if (clusterState == null) {
            log.debug { "ClusterState not provided, skipping kubeconfig S3 backup" }
            return false
        }

        if (clusterState.s3Bucket == null) {
            log.debug { "S3 bucket not configured, skipping kubeconfig S3 backup" }
            return false
        }

        return clusterBackupService
            .backupKubeconfig(config.kubeconfigPath, clusterState)
            .onFailure { error ->
                log.warn(error) { "Failed to backup kubeconfig to S3 (non-fatal)" }
                // Don't add to errors - this is a non-critical operation
            }.isSuccess
    }

    private fun setupAgents(
        config: K3sClusterConfig,
        serverUrl: String,
        nodeToken: String,
    ): Map<String, AgentSetupResult> {
        val results = ConcurrentHashMap<String, AgentSetupResult>()
        val workerServerTypes = listOf(ServerType.Cassandra, ServerType.Stress)

        val hostFilter =
            config.hostFilter
                .split(",")
                .filter { it.isNotBlank() }
                .toSet()

        val allThreads = mutableListOf<Thread>()

        workerServerTypes.forEach { serverType ->
            val nodeLabels = getNodeLabels(serverType)
            val hosts =
                config.workerHosts[serverType]?.filter {
                    hostFilter.isEmpty() || it.alias in hostFilter
                } ?: emptyList()

            hosts.forEach { clusterHost ->
                allThreads.add(
                    kotlin.concurrent.thread(start = true, name = "k3s-agent-${clusterHost.alias}") {
                        val result = setupSingleAgent(clusterHost, serverUrl, nodeToken, nodeLabels)
                        results[clusterHost.alias] = result
                    },
                )
            }
        }

        // Wait for all agents to complete
        allThreads.forEach { it.join() }

        return results.toMap()
    }

    private fun setupSingleAgent(
        clusterHost: ClusterHost,
        serverUrl: String,
        nodeToken: String,
        nodeLabels: Map<String, String>,
    ): AgentSetupResult {
        val host = clusterHost.toHost()
        outputHandler.handleMessage("Configuring K3s agent on ${host.alias} with labels: $nodeLabels...")

        // Configure agent
        val configResult = k3sAgentService.configure(host, serverUrl, nodeToken, nodeLabels)
        if (configResult.isFailure) {
            val error = configResult.exceptionOrNull()!!
            log.error(error) { "Failed to configure K3s agent on ${host.alias}" }
            outputHandler.handleError("Failed to configure K3s agent on ${host.alias}: ${error.message}")
            return AgentSetupResult(
                alias = host.alias,
                success = false,
                error = Exception("Failed to configure K3s agent: ${error.message}", error),
            )
        }

        // Start agent
        val startResult = k3sAgentService.start(host)
        if (startResult.isFailure) {
            val error = startResult.exceptionOrNull()!!
            log.error(error) { "Failed to start K3s agent on ${host.alias}" }
            outputHandler.handleError("Failed to start K3s agent on ${host.alias}: ${error.message}")
            return AgentSetupResult(
                alias = host.alias,
                success = false,
                error = Exception("Failed to start K3s agent: ${error.message}", error),
            )
        }

        log.info { "Successfully started K3s agent on ${host.alias}" }
        return AgentSetupResult(
            alias = host.alias,
            success = true,
        )
    }

    override fun getNodeLabels(serverType: ServerType): Map<String, String> =
        when (serverType) {
            ServerType.Cassandra -> mapOf("role" to "cassandra", "type" to "db")
            ServerType.Stress -> mapOf("role" to "stress", "type" to "app")
            ServerType.Control -> emptyMap()
        }
}
