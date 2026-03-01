package com.rustyrazorblade.easydblab.services

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files

/**
 * Service for managing K3s agent (worker node) lifecycle operations.
 *
 * This service manages K3s agent nodes that connect to a K3s server to form
 * a Kubernetes cluster. Agents run workloads but do not run control plane components.
 *
 * Before starting an agent, it must be configured with:
 * - The K3s server URL (https://server-ip:6443)
 * - A valid node token from the server
 *
 * All operations return Result types for explicit error handling.
 */
interface K3sAgentService : SystemDServiceManager {
    /**
     * Configures the K3s agent with server connection details and node labels.
     *
     * This writes the K3s agent configuration file at /etc/rancher/k3s/config.yaml
     * with the server URL, token, and Kubernetes node labels. This must be done before
     * starting the agent service.
     *
     * @param host The agent node to configure
     * @param serverUrl The K3s server URL (e.g., "https://10.0.1.5:6443")
     * @param token The node token from the K3s server
     * @param labels Map of Kubernetes node labels to apply (e.g., mapOf("role" to "cassandra", "type" to "db"))
     * @return Result indicating success or failure
     */
    fun configure(
        host: Host,
        serverUrl: String,
        token: String,
        labels: Map<String, String> = emptyMap(),
    ): Result<Unit>
}

/**
 * Default implementation of K3sAgentService using SSH for remote operations.
 *
 * This implementation extends AbstractSystemDServiceManager to leverage common
 * systemd service management functionality. The K3s agent service connects to
 * a K3s server and runs workloads.
 *
 * The service name "k3s-agent" corresponds to the K3s agent mode systemd service,
 * which is installed at runtime using the airgap installation method. The base
 * image contains pre-downloaded k3s artifacts, and this service installs them
 * in agent mode on first start.
 *
 * @property remoteOps Service for executing SSH commands on remote hosts
 */
class DefaultK3sAgentService(
    remoteOps: RemoteOperationsService,
    eventBus: EventBus,
) : AbstractSystemDServiceManager("k3s-agent", remoteOps, eventBus),
    K3sAgentService {
    override val log: KLogger = KotlinLogging.logger {}

    private companion object {
        const val CONFIG_FILE_PATH = "/etc/rancher/k3s/config.yaml"
        const val CONFIG_TEMP_PATH = "/tmp/k3s-config.yaml"
        const val CONFIG_DIR_PATH = "/etc/rancher/k3s"
        const val AGENT_SCRIPT_PATH = "/usr/local/bin/start-k3s-agent.sh"
        const val AGENT_SCRIPT_RESOURCE = "/com/rustyrazorblade/easydblab/services/start-k3s-agent.sh"

        val yaml =
            Yaml(
                configuration = YamlConfiguration(encodeDefaults = false),
            )
    }

    /**
     * Starts the K3s agent service using the pre-installed startup script.
     *
     * The start-k3s-agent.sh script handles:
     * - Checking if k3s-agent.service already exists
     * - Running airgap installation if needed
     * - Starting the k3s-agent.service
     *
     * The agent must be configured via configure() before calling start().
     *
     * @param host The worker node where the K3s agent should run
     * @return Result indicating success or failure
     */
    override fun start(host: Host): Result<Unit> =
        runCatching {
            log.debug { "Starting K3s agent on ${host.alias}" }

            // Upload the startup script from classpath resources
            uploadScript(host, AGENT_SCRIPT_RESOURCE, AGENT_SCRIPT_PATH)

            // Read and parse config to get server URL and token
            val configResult =
                remoteOps.executeRemotely(
                    host,
                    "cat $CONFIG_FILE_PATH",
                    output = false,
                )

            val (serverUrl, token) = parseAgentConfig(configResult.text, host.alias)

            // Call the uploaded script with server URL and token
            remoteOps.executeRemotely(
                host,
                "sudo $AGENT_SCRIPT_PATH '$serverUrl' '$token'",
                output = true,
            )

            log.info { "Successfully started K3s agent on ${host.alias}" }
        }

    /**
     * Parses K3s agent configuration YAML to extract server URL and token.
     *
     * @param configText The YAML configuration file content
     * @param hostAlias The host alias for error messages
     * @return Pair of (serverUrl, token)
     * @throws IllegalStateException if parsing fails or required fields are missing
     */
    private fun parseAgentConfig(
        configText: String,
        hostAlias: String,
    ): Pair<String, String> {
        val config =
            try {
                yaml.decodeFromString<K3sAgentConfig>(configText)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Failed to parse k3s config on $hostAlias. " +
                        "Ensure configure() was called before start().",
                    e,
                )
            }

        return config.server to config.token
    }

    override fun configure(
        host: Host,
        serverUrl: String,
        token: String,
        labels: Map<String, String>,
    ): Result<Unit> =
        runCatching {
            require(serverUrl.isNotBlank()) { "Server URL cannot be blank" }
            require(token.isNotBlank()) { "Token cannot be blank" }

            log.debug { "Configuring K3s agent on ${host.alias} to connect to $serverUrl with labels: $labels" }

            val configContent = buildAgentConfig(serverUrl, token, labels)

            // Write config to temporary local file
            val tempFile = File.createTempFile("k3s-agent-config-", ".yaml")
            try {
                tempFile.writeText(configContent)

                // Ensure the config directory exists
                remoteOps.executeRemotely(
                    host,
                    "sudo mkdir -p $CONFIG_DIR_PATH",
                    output = false,
                )

                // Upload config file
                remoteOps.upload(
                    host,
                    tempFile.toPath(),
                    CONFIG_TEMP_PATH,
                )

                // Move to final location with proper permissions
                val moveCommand =
                    "sudo mv $CONFIG_TEMP_PATH $CONFIG_FILE_PATH && " +
                        "sudo chmod 600 $CONFIG_FILE_PATH"
                remoteOps.executeRemotely(
                    host,
                    moveCommand,
                    output = false,
                )

                log.info { "Successfully configured K3s agent on ${host.alias}" }
            } finally {
                tempFile.delete()
            }
        }

    /**
     * Uploads a script from classpath resources to a remote host.
     */
    private fun uploadScript(
        host: Host,
        resourcePath: String,
        remotePath: String,
    ) {
        val scriptContent =
            this::class.java.getResourceAsStream(resourcePath)
                ?: error("Script not found: $resourcePath")

        val remoteFilename = remotePath.substringAfterLast("/")
        val tempFile = Files.createTempFile("k3s-", "-script.sh")
        try {
            scriptContent.use { input ->
                Files.newOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            remoteOps.upload(host, tempFile, remoteFilename)
            remoteOps.executeRemotely(host, "sudo mv $remoteFilename $remotePath && sudo chmod +x $remotePath", output = false)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    /**
     * Builds K3s agent configuration YAML content with server URL, token, and optional node labels.
     *
     * @param serverUrl The K3s server URL
     * @param token The node authentication token
     * @param labels Map of Kubernetes node labels to apply
     * @return YAML configuration content as a string
     */
    private fun buildAgentConfig(
        serverUrl: String,
        token: String,
        labels: Map<String, String>,
    ): String {
        val nodeLabels =
            if (labels.isNotEmpty()) {
                labels.map { (key, value) -> "$key=$value" }
            } else {
                null
            }

        val config = K3sAgentConfig(server = serverUrl, token = token, nodeLabel = nodeLabels)
        return yaml.encodeToString(config)
    }
}

/**
 * K3s agent configuration data class, serialized to YAML for /etc/rancher/k3s/config.yaml.
 */
@Serializable
data class K3sAgentConfig(
    val server: String,
    val token: String,
    @SerialName("node-label")
    val nodeLabel: List<String>? = null,
)
