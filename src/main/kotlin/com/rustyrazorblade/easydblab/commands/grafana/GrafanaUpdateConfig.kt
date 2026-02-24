package com.rustyrazorblade.easydblab.commands.grafana

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.registry.RegistryManifestBuilder
import com.rustyrazorblade.easydblab.configuration.s3manager.S3ManagerManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.configuration.toHost
import com.rustyrazorblade.easydblab.configuration.vector.VectorManifestBuilder
import com.rustyrazorblade.easydblab.configuration.victoria.VictoriaManifestBuilder
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import com.rustyrazorblade.easydblab.services.K8sService
import io.fabric8.kubernetes.api.model.HasMetadata
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Build and apply the full observability stack to the K8s cluster.
 *
 * Deploys all observability infrastructure including collectors, storage backends,
 * profiling, dashboards, and supporting services. This is the single command to
 * update the entire observability stack.
 *
 * The stack includes:
 * - OTel collector DaemonSet (metrics, logs, traces collection)
 * - VictoriaMetrics + VictoriaLogs (metrics and log storage)
 * - Tempo (trace storage with S3 backend)
 * - Vector (node log collection + S3/EMR log ingestion)
 * - Beyla (L7 network eBPF metrics)
 * - ebpf_exporter (TCP, block I/O, VFS eBPF metrics)
 * - Pyroscope (continuous profiling server + eBPF agent)
 * - Grafana with pre-configured dashboards
 * - Docker registry and S3 manager
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "update-config",
    description = ["Build and apply the full observability stack to K8s cluster"],
)
class GrafanaUpdateConfig : PicoBaseCommand() {
    private val log = KotlinLogging.logger {}
    private val dashboardService: GrafanaDashboardService by inject()
    private val user: User by inject()
    private val k8sService: K8sService by inject()
    private val otelManifestBuilder: OtelManifestBuilder by inject()
    private val ebpfExporterManifestBuilder: EbpfExporterManifestBuilder by inject()
    private val victoriaManifestBuilder: VictoriaManifestBuilder by inject()
    private val tempoManifestBuilder: TempoManifestBuilder by inject()
    private val vectorManifestBuilder: VectorManifestBuilder by inject()
    private val registryManifestBuilder: RegistryManifestBuilder by inject()
    private val s3ManagerManifestBuilder: S3ManagerManifestBuilder by inject()
    private val beylaManifestBuilder: BeylaManifestBuilder by inject()
    private val pyroscopeManifestBuilder: PyroscopeManifestBuilder by inject()

    companion object {
        private const val CLUSTER_CONFIG_NAME = "cluster-config"
        private const val DEFAULT_NAMESPACE = "default"
    }

    override fun execute() {
        val controlHosts = clusterState.hosts[ServerType.Control]
        if (controlHosts.isNullOrEmpty()) {
            error("No control nodes found. Please ensure the environment is running.")
        }
        val controlNode = controlHosts.first()

        val region = clusterState.initConfig?.region ?: user.region

        // Create runtime ConfigMap with dynamic values needed by Vector and OTel
        createClusterConfigMap(controlNode, region)

        // Apply all Fabric8-built observability resources
        applyFabric8Resources("OTel Collector", controlNode, otelManifestBuilder.buildAllResources())
        applyFabric8Resources("ebpf_exporter", controlNode, ebpfExporterManifestBuilder.buildAllResources())
        applyFabric8Resources("VictoriaMetrics/Logs", controlNode, victoriaManifestBuilder.buildAllResources())
        applyFabric8Resources("Tempo", controlNode, tempoManifestBuilder.buildAllResources())
        applyFabric8Resources("Vector", controlNode, vectorManifestBuilder.buildAllResources())
        applyFabric8Resources("Registry", controlNode, registryManifestBuilder.buildAllResources())
        applyFabric8Resources("S3 Manager", controlNode, s3ManagerManifestBuilder.buildAllResources())
        applyFabric8Resources("Beyla", controlNode, beylaManifestBuilder.buildAllResources())

        // Apply Pyroscope resources (requires directory setup via SSH first)
        applyPyroscopeResources(controlNode)

        // Apply Grafana dashboards
        dashboardService.uploadDashboards(controlNode, region).getOrElse { exception ->
            error("Failed to upload dashboards: ${exception.message}")
        }
    }

    private fun applyFabric8Resources(
        label: String,
        controlNode: ClusterHost,
        resources: List<HasMetadata>,
    ) {
        eventBus.emit(Event.Grafana.LabelResourcesApplying(label))
        for (resource in resources) {
            k8sService.applyResource(controlNode, resource).getOrElse { exception ->
                error("Failed to apply $label ${resource.kind}/${resource.metadata?.name}: ${exception.message}")
            }
        }
        eventBus.emit(Event.Grafana.LabelResourcesApplied(label))
    }

    private fun applyPyroscopeResources(controlNode: ClusterHost) {
        eventBus.emit(Event.Grafana.PyroscopeDirectoryPreparing)
        remoteOps.executeRemotely(
            controlNode.toHost(),
            "sudo mkdir -p /mnt/db1/pyroscope && " +
                "sudo chown -R ${PyroscopeManifestBuilder.PYROSCOPE_UID}:${PyroscopeManifestBuilder.PYROSCOPE_UID} /mnt/db1/pyroscope",
        )

        applyFabric8Resources("Pyroscope", controlNode, pyroscopeManifestBuilder.buildAllResources())
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
    private fun createClusterConfigMap(
        controlNode: ClusterHost,
        region: String,
    ) {
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
            }
    }
}
