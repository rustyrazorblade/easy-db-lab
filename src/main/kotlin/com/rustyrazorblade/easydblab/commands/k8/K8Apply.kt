package com.rustyrazorblade.easydblab.commands.k8

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.grafana.GrafanaUpload
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.registry.RegistryManifestBuilder
import com.rustyrazorblade.easydblab.configuration.s3manager.S3ManagerManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.configuration.vector.VectorManifestBuilder
import com.rustyrazorblade.easydblab.configuration.victoria.VictoriaManifestBuilder
import com.rustyrazorblade.easydblab.output.displayObservabilityAccess
import com.rustyrazorblade.easydblab.services.K8sService
import io.fabric8.kubernetes.api.model.HasMetadata
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Apply observability stack to the K8s cluster.
 *
 * This command deploys the observability infrastructure to the K3s cluster
 * running on the lab environment. All K8s resources are built programmatically
 * using Fabric8 manifest builders.
 *
 * The observability stack includes:
 * - OTel collector DaemonSet (metrics, logs, traces collection)
 * - VictoriaMetrics + VictoriaLogs (metrics and log storage)
 * - Tempo (trace storage with S3 backend)
 * - Vector (node log collection + S3/EMR log ingestion)
 * - Beyla (L7 network eBPF metrics)
 * - ebpf_exporter (TCP, block I/O, VFS eBPF metrics)
 * - Grafana with pre-configured dashboards and Pyroscope profiling
 * - Docker registry and S3 manager
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
    private val otelManifestBuilder: OtelManifestBuilder by inject()
    private val ebpfExporterManifestBuilder: EbpfExporterManifestBuilder by inject()
    private val victoriaManifestBuilder: VictoriaManifestBuilder by inject()
    private val tempoManifestBuilder: TempoManifestBuilder by inject()
    private val vectorManifestBuilder: VectorManifestBuilder by inject()
    private val registryManifestBuilder: RegistryManifestBuilder by inject()
    private val s3ManagerManifestBuilder: S3ManagerManifestBuilder by inject()
    private val beylaManifestBuilder: BeylaManifestBuilder by inject()
    private val grafanaUpload: GrafanaUpload by inject()

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

    companion object {
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

        // Create runtime ConfigMap with dynamic values
        createClusterConfigMap(controlNode)

        // Apply all Fabric8-built observability resources
        applyFabric8Resources("OTel Collector", controlNode, otelManifestBuilder.buildAllResources())
        applyFabric8Resources("ebpf_exporter", controlNode, ebpfExporterManifestBuilder.buildAllResources())
        applyFabric8Resources("VictoriaMetrics/Logs", controlNode, victoriaManifestBuilder.buildAllResources())
        applyFabric8Resources("Tempo", controlNode, tempoManifestBuilder.buildAllResources())
        applyFabric8Resources("Vector", controlNode, vectorManifestBuilder.buildAllResources())
        applyFabric8Resources("Registry", controlNode, registryManifestBuilder.buildAllResources())
        applyFabric8Resources("S3 Manager", controlNode, s3ManagerManifestBuilder.buildAllResources())
        applyFabric8Resources("Beyla", controlNode, beylaManifestBuilder.buildAllResources())

        // Apply Grafana + Pyroscope resources (handles its own build and apply)
        grafanaUpload.execute()

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

    private fun applyFabric8Resources(
        label: String,
        controlNode: ClusterHost,
        resources: List<HasMetadata>,
    ) {
        outputHandler.handleMessage("Applying $label resources...")
        for (resource in resources) {
            k8sService.applyResource(controlNode, resource).getOrElse { exception ->
                error("Failed to apply $label ${resource.kind}/${resource.metadata?.name}: ${exception.message}")
            }
        }
        outputHandler.handleMessage("$label resources applied successfully")
    }

    /**
     * Creates the cluster-config ConfigMap with runtime values needed by Vector and OTel.
     *
     * This ConfigMap provides:
     * - control_node_ip: IP address for Vector DaemonSet to send logs to Victoria Logs
     * - aws_region: AWS region for Vector S3 source
     * - sqs_queue_url: SQS queue URL for EMR log notifications
     * - cluster_name: Cluster name for OTel Prometheus relabel_configs (Grafana dashboard labels)
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
                "s3_bucket" to (clusterState.s3Bucket ?: ""),
                "cluster_name" to (clusterState.initConfig?.name ?: "cluster"),
            )

        log.info {
            "Creating cluster-config ConfigMap with: control_node_ip=${controlNode.privateIp}, " +
                "region=$region, s3_bucket=${clusterState.s3Bucket}"
        }

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
