package com.rustyrazorblade.easydblab.commands.k8

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.output.displayObservabilityAccess
import com.rustyrazorblade.easydblab.services.K8sService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Path

/**
 * Apply observability stack to the K8s cluster.
 *
 * This command deploys the observability infrastructure (OTel collectors,
 * Prometheus, Grafana) to the K3s cluster running on the lab environment.
 *
 * The observability stack includes:
 * - OTel collector DaemonSet on control node (aggregator)
 * - OTel collector DaemonSet on worker nodes (forwarders)
 * - Prometheus for metrics storage and querying
 * - Grafana with pre-configured dashboards
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "apply",
    description = ["Apply observability stack to K8s cluster"],
)
class K8Apply : PicoBaseCommand() {
    private val log = KotlinLogging.logger {}
    private val k8sService: K8sService by inject()
    private val user: User by inject()

    @Suppress("MagicNumber")
    @Option(
        names = ["--timeout"],
        description = ["Timeout in seconds to wait for pods to be ready (default: 120)"],
    )
    var timeoutSeconds: Int = 120

    @Option(
        names = ["--skip-wait"],
        description = ["Skip waiting for pods to be ready"],
    )
    var skipWait: Boolean = false

    @Option(
        names = ["-f", "--file"],
        description = ["Path to manifest file or directory (default: core observability stack)"],
    )
    var manifestPath: Path? = null

    companion object {
        private const val K8S_CORE_MANIFEST_DIR = "k8s/core"
        private const val CLUSTER_CONFIG_NAME = "cluster-config"
        private const val DEFAULT_NAMESPACE = "default"
    }

    override fun execute() {
        // Get control node from cluster state (ClusterHost for SOCKS proxy)
        val controlHosts = clusterState.hosts[ServerType.Control]
        if (controlHosts.isNullOrEmpty()) {
            error("No control nodes found. Please ensure the environment is running.")
        }
        val controlNode = controlHosts.first()
        log.debug { "Using control node: ${controlNode.alias} (${controlNode.publicIp})" }

        // Create cluster-config ConfigMap with runtime values for Vector
        createClusterConfigMap(controlNode)

        // Determine manifest path - use provided path or default to core manifests
        val pathToApply = manifestPath ?: Path.of(K8S_CORE_MANIFEST_DIR)
        log.info { "Applying manifests from: $pathToApply" }

        // Apply manifests to cluster
        k8sService
            .applyManifests(controlNode, pathToApply)
            .getOrElse { exception ->
                error("Failed to apply K8s manifests: ${exception.message}")
            }

        // Wait for pods to be ready
        if (!skipWait) {
            k8sService
                .waitForPodsReady(controlNode, timeoutSeconds)
                .getOrElse { exception ->
                    outputHandler.handleError("Warning: Pods may not be ready: ${exception.message}")
                    outputHandler.handleMessage("You can check status with: kubectl get pods -n observability")
                }
        }

        // Display access information
        outputHandler.handleMessage("")
        outputHandler.handleMessage("Observability stack deployed successfully!")
        outputHandler.displayObservabilityAccess(controlNode.privateIp)
    }

    /**
     * Creates the cluster-config ConfigMap with runtime values needed by Vector.
     *
     * This ConfigMap provides:
     * - control_node_ip: IP address for Vector DaemonSet to send logs to Victoria Logs
     * - aws_region: AWS region for Vector S3 source
     * - sqs_queue_url: SQS queue URL for EMR log notifications
     */
    private fun createClusterConfigMap(controlNode: ClusterHost) {
        val region = clusterState.initConfig?.region ?: user.region
        val sqsQueueUrl = clusterState.sqsQueueUrl

        // Fail fast if Spark is enabled but SQS queue is not configured
        if (clusterState.initConfig?.sparkEnabled == true && sqsQueueUrl.isNullOrBlank()) {
            throw IllegalStateException(
                "SQS queue URL is required when Spark is enabled but was not configured. " +
                    "The log ingestion pipeline cannot function without it. " +
                    "Re-run 'easy-db-lab up' to create the SQS queue.",
            )
        }

        val configData =
            mapOf(
                "control_node_ip" to controlNode.privateIp,
                "aws_region" to region,
                "sqs_queue_url" to (sqsQueueUrl ?: ""),
            )

        log.info { "Creating cluster-config ConfigMap with: control_node_ip=${controlNode.privateIp}, region=$region" }

        k8sService
            .createConfigMap(
                controlHost = controlNode,
                namespace = DEFAULT_NAMESPACE,
                name = CLUSTER_CONFIG_NAME,
                data = configData,
                labels = mapOf("app.kubernetes.io/managed-by" to "easy-db-lab"),
            ).getOrElse { exception ->
                log.warn { "Failed to create cluster-config ConfigMap: ${exception.message}" }
                // Don't fail the command - the ConfigMap may already exist or Vector may not need it
            }
    }
}
