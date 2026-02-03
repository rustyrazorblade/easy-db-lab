package com.rustyrazorblade.easydblab.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.kubernetes.DefaultKubernetesService
import com.rustyrazorblade.easydblab.kubernetes.KubernetesJob
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import com.rustyrazorblade.easydblab.kubernetes.ProxiedKubernetesClientFactory
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
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
     * @return Result indicating success or failure
     */
    fun downloadAndConfigureKubeconfig(
        host: Host,
        localPath: Path,
    ): Result<Unit>

    /**
     * Lists Kubernetes jobs from the K3s cluster.
     *
     * This method handles the complete connection chain:
     * 1. Establishes SOCKS proxy to the control node
     * 2. Creates proxied Kubernetes client
     * 3. Lists all jobs across namespaces
     *
     * @param controlHost The control node running K3s
     * @param kubeconfigPath Local path to the kubeconfig file
     * @return Result containing list of jobs or an error
     */
    fun listJobs(
        controlHost: ClusterHost,
        kubeconfigPath: Path,
    ): Result<List<KubernetesJob>>

    /**
     * Lists Kubernetes pods from the K3s cluster.
     *
     * This method handles the complete connection chain:
     * 1. Establishes SOCKS proxy to the control node
     * 2. Creates proxied Kubernetes client
     * 3. Lists all pods across namespaces
     *
     * @param controlHost The control node running K3s
     * @param kubeconfigPath Local path to the kubeconfig file
     * @return Result containing list of pods or an error
     */
    fun listPods(
        controlHost: ClusterHost,
        kubeconfigPath: Path,
    ): Result<List<KubernetesPod>>
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
 * @property outputHandler Handler for user-facing output messages
 */
class DefaultK3sService(
    remoteOps: RemoteOperationsService,
    outputHandler: OutputHandler,
    private val socksProxyService: SocksProxyService,
) : AbstractSystemDServiceManager("k3s", remoteOps, outputHandler),
    K3sService {
    override val log: KLogger = KotlinLogging.logger {}

    private val yamlMapper =
        ObjectMapper(YAMLFactory())
            .registerKotlinModule()

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
    override fun start(host: Host): Result<Unit> =
        runCatching {
            log.debug { "Starting K3s server on ${host.alias}" }

            // Call pre-installed script to install and start k3s
            remoteOps.executeRemotely(
                host,
                "sudo /usr/local/bin/start-k3s-server.sh",
                output = true,
            )

            log.info { "Successfully started K3s server on ${host.alias}" }
        }

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
    ): Result<Unit> =
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

                // Parse YAML
                val kubeconfigMap = yamlMapper.readValue(tempFile, Map::class.java) as Map<String, Any>

                // Modify server URL from 127.0.0.1 to control node's private IP
                val newServerUrl = Constants.K3s.DEFAULT_SERVER_URL.replace("127.0.0.1", host.private)

                @Suppress("UNCHECKED_CAST")
                val clusters = kubeconfigMap["clusters"] as? List<Map<String, Any>>
                clusters?.firstOrNull()?.let { cluster ->
                    val clusterData = cluster["cluster"] as? MutableMap<String, Any>
                    clusterData?.put("server", newServerUrl)
                }

                // Write modified kubeconfig to local path
                yamlMapper.writeValue(localPath.toFile(), kubeconfigMap)

                log.info { "Successfully configured kubeconfig at $localPath with server URL $newServerUrl" }
            } finally {
                tempFile.delete()
            }
        }

    override fun listJobs(
        controlHost: ClusterHost,
        kubeconfigPath: Path,
    ): Result<List<KubernetesJob>> =
        runCatching {
            socksProxyService.ensureRunning(controlHost)

            val clientFactory =
                ProxiedKubernetesClientFactory(
                    proxyHost = "localhost",
                    proxyPort = socksProxyService.getLocalPort(),
                )
            val kubeService = DefaultKubernetesService(clientFactory, kubeconfigPath)

            // List all jobs
            kubeService.listJobs().getOrThrow()
        }

    override fun listPods(
        controlHost: ClusterHost,
        kubeconfigPath: Path,
    ): Result<List<KubernetesPod>> =
        runCatching {
            socksProxyService.ensureRunning(controlHost)

            val clientFactory =
                ProxiedKubernetesClientFactory(
                    proxyHost = "localhost",
                    proxyPort = socksProxyService.getLocalPort(),
                )
            val kubeService = DefaultKubernetesService(clientFactory, kubeconfigPath)

            // List all pods
            kubeService.listPods().getOrThrow()
        }
}
