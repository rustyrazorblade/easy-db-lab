package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kubernetes.parseKubeconfig
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.fabric8.kubernetes.client.utils.Serialization
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Service for managing K3s server (control plane) lifecycle operations.
 *
 * This service manages the K3s server component which runs on control nodes.
 * The K3s server provides the Kubernetes control plane (API server, scheduler,
 * controller manager) and should be started before any agent nodes.
 *
 * K3s is a lightweight Kubernetes distribution designed for edge, IoT, and CI/CD
 * environments. It's installed on the base image but disabled by default, requiring
 * explicit start operations during cluster provisioning.
 *
 * All operations return Result types for explicit error handling.
 */
interface K3sService : SystemDServiceManager {
    /**
     * Retrieves the node token from the K3s server.
     *
     * This token is required for agent nodes to join the cluster. The token
     * is located at /var/lib/rancher/k3s/server/node-token on the server.
     *
     * @param host The control node running the K3s server
     * @return Result containing the node token string, or failure if retrieval failed
     */
    fun getNodeToken(host: Host): Result<String>

    /**
     * Starts the K3s server with optional custom CNI configuration.
     *
     * When useCustomCni is true, K3s starts with --flannel-backend=none so a custom CNI
     * (e.g. Cilium) can be installed. When false, built-in Flannel is used and nodes
     * become Ready without any additional CNI setup.
     *
     * @param host The control node where the K3s server should run
     * @param useCustomCni Whether to disable Flannel and use a custom CNI instead
     * @return Result indicating success or failure
     */
    fun startServer(
        host: Host,
        useCustomCni: Boolean,
    ): Result<Unit>

    /**
     * Downloads and configures the K3s kubeconfig for local kubectl access.
     *
     * This method:
     * 1. Downloads /etc/rancher/k3s/k3s.yaml from the control node
     * 2. Modifies the server URL from 127.0.0.1 to the control node's private IP
     * 3. Writes the configured kubeconfig to the local file system
     *
     * The modified kubeconfig allows kubectl to connect through a SOCKS5 proxy
     * using the control node's private IP address.
     *
     * @param host The control node running the K3s server
     * @param localPath Path where the kubeconfig should be written locally
     * @return Result containing the kubeconfig's current-context, which the caller records in its
     *   cluster state as the context every later command targets
     */
    fun downloadAndConfigureKubeconfig(
        host: Host,
        localPath: Path,
    ): Result<String>
}

/**
 * Default implementation of K3sService using SSH for remote operations.
 *
 * This implementation extends AbstractSystemDServiceManager to leverage common
 * systemd service management functionality. The K3s server service is managed
 * through standard systemctl commands.
 *
 * The service name "k3s" corresponds to the K3s server mode systemd service,
 * which is installed at runtime using the airgap installation method. The base
 * image contains pre-downloaded k3s artifacts, and this service installs them
 * in server mode on first start.
 *
 * @property remoteOps Service for executing SSH commands on remote hosts
 */
class DefaultK3sService(
    remoteOps: RemoteOperationsService,
    eventBus: EventBus,
) : AbstractSystemDServiceManager("k3s", remoteOps, eventBus),
    K3sService {
    override val log: KLogger = KotlinLogging.logger {}

    private companion object {
        const val SERVER_SCRIPT_RESOURCE = "/com/rustyrazorblade/easydblab/services/start-k3s-server.sh"
        const val SERVER_SCRIPT_PATH = "/usr/local/bin/start-k3s-server.sh"
    }

    /**
     * Starts the K3s server service using the pre-installed startup script.
     *
     * The start-k3s-server.sh script handles:
     * - Checking if k3s.service already exists
     * - Running airgap installation if needed
     * - Starting the k3s.service
     *
     * @param host The control node where the K3s server should run
     * @return Result indicating success or failure
     */
    override fun startServer(
        host: Host,
        useCustomCni: Boolean,
    ): Result<Unit> =
        runCatching {
            log.debug { "Starting K3s server on ${host.alias} (useCustomCni=$useCustomCni)" }

            uploadScript(host, SERVER_SCRIPT_RESOURCE, SERVER_SCRIPT_PATH)

            val args = if (useCustomCni) "--flannel-backend=none" else ""
            remoteOps.executeRemotely(
                host,
                "sudo $SERVER_SCRIPT_PATH $args".trim(),
                output = true,
            )

            log.info { "Successfully started K3s server on ${host.alias}" }
        }

    override fun start(host: Host): Result<Unit> = startServer(host, useCustomCni = false)

    override fun getNodeToken(host: Host): Result<String> =
        runCatching {
            log.debug { "Retrieving K3s node token from ${host.alias}" }

            val response =
                remoteOps.executeRemotely(
                    host,
                    "sudo cat ${Constants.K3s.NODE_TOKEN_PATH}",
                    output = false,
                )

            val token = response.text.trim()

            check(token.isNotBlank()) { "K3s node token is empty on ${host.alias}" }

            log.info { "Successfully retrieved K3s node token from ${host.alias}" }
            token
        }

    override fun downloadAndConfigureKubeconfig(
        host: Host,
        localPath: Path,
    ): Result<String> =
        runCatching {
            log.debug { "Downloading kubeconfig from ${host.alias}" }

            // Download kubeconfig to temporary file
            val tempFile = File.createTempFile("k3s-kubeconfig-", ".yaml")
            try {
                remoteOps.download(
                    host,
                    Constants.K3s.REMOTE_KUBECONFIG,
                    tempFile.toPath(),
                )

                // Parse kubeconfig into fabric8's typed model (no untyped map diving)
                val kubeConfig = parseKubeconfig(tempFile.toPath())

                // Modify server URL from 127.0.0.1 to control node's private IP
                val newServerUrl = Constants.K3s.DEFAULT_SERVER_URL.replace("127.0.0.1", host.private)

                val cluster =
                    kubeConfig.clusters.firstOrNull()?.cluster
                        ?: error("Kubeconfig downloaded from ${host.alias} contains no clusters")
                cluster.server = newServerUrl

                val context =
                    kubeConfig.currentContext?.takeIf { it.isNotBlank() }
                        ?: error("Kubeconfig downloaded from ${host.alias} names no current-context")

                // Write modified kubeconfig to local path
                Files.writeString(localPath, Serialization.asYaml(kubeConfig))

                log.info { "Successfully configured kubeconfig at $localPath with server URL $newServerUrl" }
                context
            } finally {
                tempFile.delete()
            }
        }

    /**
     * Uploads a script from classpath resources to a remote host.
     *
     * @param host The host to upload to
     * @param resourcePath The classpath resource path
     * @param remotePath The destination path on the remote host
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
}
