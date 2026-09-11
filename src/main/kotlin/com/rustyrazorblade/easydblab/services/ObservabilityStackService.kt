package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.JournaldOtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.registry.RegistryManifestBuilder
import com.rustyrazorblade.easydblab.configuration.s3manager.S3ManagerManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.configuration.victoria.VictoriaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.yace.YaceManifestBuilder
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.fabric8.kubernetes.api.model.HasMetadata
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Builds and applies the full observability stack to the K8s cluster.
 *
 * This is the orchestration extracted out of the `grafana update-config` command so that both the
 * command and cluster bring-up (`up`) drive one code path. It owns the whole sequence: the runtime
 * `cluster-config` ConfigMap, every Fabric8-built resource, the Pyroscope and Grafana on-node data
 * directories, dashboard upload, the rollout-restart of the workloads it applied, and the final
 * namespace-wide readiness gate.
 *
 * Redirect mode is a first-class branch, not a set of skipped steps bolted on: when
 * [TelemetryRedirect] is present, the four local backends (VictoriaMetrics, VictoriaLogs, Tempo,
 * the Pyroscope server) plus Grafana and its dashboards are never built, and the collectors and the
 * Pyroscope eBPF agent are pointed at the external stack instead. The rollout-restart list is
 * derived from the resources actually applied, so redirect emits no spurious warnings about
 * workloads that were never deployed.
 */
interface ObservabilityStackService {
    /**
     * Deploys the observability stack to [controlNode].
     *
     * @param controlNode the cluster's control node running K3s.
     * @param telemetryRedirect when non-null, deploy collectors pointed at this external stack and
     *   skip the local backends, Grafana, and dashboards.
     * @return success once the stack is applied and the namespace reaches Ready; failure otherwise.
     */
    fun deploy(
        controlNode: ClusterHost,
        telemetryRedirect: TelemetryRedirect?,
    ): Result<Unit>
}

/**
 * Default implementation that applies the stack over the K8s API and configures on-node
 * directories over SSH.
 */
@Suppress("LongParameterList")
class DefaultObservabilityStackService(
    private val k8sService: K8sService,
    private val k8sClientProvider: K8sClientProvider,
    private val remoteOps: RemoteOperationsService,
    private val clusterStateManager: ClusterStateManager,
    private val user: User,
    private val eventBus: EventBus,
    private val dashboardService: GrafanaDashboardService,
    private val otelManifestBuilder: OtelManifestBuilder,
    private val journaldOtelManifestBuilder: JournaldOtelManifestBuilder,
    private val ebpfExporterManifestBuilder: EbpfExporterManifestBuilder,
    private val victoriaManifestBuilder: VictoriaManifestBuilder,
    private val tempoManifestBuilder: TempoManifestBuilder,
    private val registryManifestBuilder: RegistryManifestBuilder,
    private val s3ManagerManifestBuilder: S3ManagerManifestBuilder,
    private val beylaManifestBuilder: BeylaManifestBuilder,
    private val pyroscopeManifestBuilder: PyroscopeManifestBuilder,
    private val yaceManifestBuilder: YaceManifestBuilder,
) : ObservabilityStackService {
    private val log = KotlinLogging.logger {}

    companion object {
        private const val CLUSTER_CONFIG_NAME = "cluster-config"
        private const val DEFAULT_NAMESPACE = "default"
    }

    /** One deploy stage: a human label and the resources it applies. */
    private data class Stage(
        val label: String,
        val resources: List<HasMetadata>,
    )

    override fun deploy(
        controlNode: ClusterHost,
        telemetryRedirect: TelemetryRedirect?,
    ): Result<Unit> =
        runCatching {
            val clusterState = clusterStateManager.load()
            val region = clusterState.initConfig?.region ?: user.region

            createClusterConfigMap(controlNode, region)

            val scrapeConfigs =
                k8sClientProvider.createClient(controlNode).use { client ->
                    otelManifestBuilder.listWorkloadScrapeConfigs(client)
                }

            // Collectors and supporting infra deploy in both modes; the local telemetry backends and
            // Grafana are local-mode only. The Pyroscope server directory and dashboard upload hang
            // off those, so they are guarded the same way.
            val stages =
                buildList {
                    add(Stage("OTel Collector", otelManifestBuilder.buildAllResources(scrapeConfigs, telemetryRedirect)))
                    add(Stage("Fluent Bit Journald", journaldOtelManifestBuilder.buildAllResources()))
                    add(Stage("ebpf_exporter", ebpfExporterManifestBuilder.buildAllResources()))
                    add(Stage("Registry", registryManifestBuilder.buildAllResources()))
                    add(Stage("S3 Manager", s3ManagerManifestBuilder.buildAllResources()))
                    add(Stage("Beyla", beylaManifestBuilder.buildAllResources()))
                    add(Stage("YACE", yaceManifestBuilder.buildAllResources()))
                    if (telemetryRedirect == null) {
                        add(Stage("VictoriaMetrics/Logs", victoriaManifestBuilder.buildAllResources()))
                        add(Stage("Tempo", tempoManifestBuilder.buildAllResources()))
                        add(Stage("Pyroscope", pyroscopeManifestBuilder.buildAllResources()))
                    } else {
                        // Redirect: the eBPF agent still runs on every node, pointed at the external
                        // Pyroscope; the server does not exist here.
                        add(Stage("Pyroscope Agent", pyroscopeManifestBuilder.buildAgentResources(telemetryRedirect)))
                    }
                }

            // The Pyroscope server writes to a host directory; the agent does not, so prepare it
            // only in local mode.
            if (telemetryRedirect == null) {
                preparePyroscopeDirectory(controlNode)
            }

            val appliedResources = mutableListOf<HasMetadata>()
            for (stage in stages) {
                applyStage(stage, controlNode)
                appliedResources.addAll(stage.resources)
            }

            if (telemetryRedirect == null) {
                prepareGrafanaDirectory(controlNode)
                dashboardService.uploadDashboards(controlNode).getOrElse { exception ->
                    error("Failed to upload dashboards: ${exception.message}")
                }
            }

            restartAppliedWorkloads(controlNode, appliedResources)

            // Gate success on the whole stack reaching Ready. waitForPodsReady is namespace-wide and
            // fail-fast: it aborts on CrashLoopBackOff / ImagePullBackOff and on timeout, so `up`
            // never reports success while the observability stack is broken or still coming up.
            k8sService
                .waitForPodsReady(controlNode, Constants.K8s.OBSERVABILITY_READY_TIMEOUT_SECONDS)
                .getOrElse { exception ->
                    error("Observability stack did not become ready: ${exception.message}")
                }
        }

    private fun applyStage(
        stage: Stage,
        controlNode: ClusterHost,
    ) {
        eventBus.emit(Event.Grafana.LabelResourcesApplying(stage.label))
        for (resource in stage.resources) {
            k8sService.applyResource(controlNode, resource).getOrElse { exception ->
                error("Failed to apply ${stage.label} ${resource.kind}/${resource.metadata?.name}: ${exception.message}")
            }
        }
        eventBus.emit(Event.Grafana.LabelResourcesApplied(stage.label))
    }

    /**
     * Rolling-restarts every Deployment and DaemonSet that was applied, so each picks up its new
     * ConfigMap. Deriving the list from [appliedResources] means redirect never tries to restart a
     * backend it did not deploy — the source of the earlier spurious failure warnings.
     */
    private fun restartAppliedWorkloads(
        controlNode: ClusterHost,
        appliedResources: List<HasMetadata>,
    ) {
        eventBus.emit(Event.Grafana.WorkloadsRestarting)

        val deployments = appliedResources.filter { it.kind == "Deployment" }.mapNotNull { it.metadata?.name }.distinct()
        val daemonSets = appliedResources.filter { it.kind == "DaemonSet" }.mapNotNull { it.metadata?.name }.distinct()

        for (name in deployments) {
            k8sService
                .rolloutRestartDeployment(controlNode, name, DEFAULT_NAMESPACE)
                .onSuccess { eventBus.emit(Event.Grafana.WorkloadRestarted("Deployment", name)) }
                .onFailure { exception -> log.warn { "Failed to restart Deployment/$name: ${exception.message}" } }
        }
        for (name in daemonSets) {
            k8sService
                .rolloutRestartDaemonSet(controlNode, name, DEFAULT_NAMESPACE)
                .onSuccess { eventBus.emit(Event.Grafana.WorkloadRestarted("DaemonSet", name)) }
                .onFailure { exception -> log.warn { "Failed to restart DaemonSet/$name: ${exception.message}" } }
        }
    }

    private fun preparePyroscopeDirectory(controlNode: ClusterHost) {
        eventBus.emit(Event.Grafana.PyroscopeDirectoryPreparing)
        remoteOps.executeRemotely(
            controlNode.toHost(),
            "sudo mkdir -p /mnt/db1/pyroscope && " +
                "sudo chown -R ${PyroscopeManifestBuilder.PYROSCOPE_UID}:${PyroscopeManifestBuilder.PYROSCOPE_UID} /mnt/db1/pyroscope",
        )
    }

    private fun prepareGrafanaDirectory(controlNode: ClusterHost) {
        eventBus.emit(Event.Grafana.GrafanaDirectoryPreparing)
        remoteOps.executeRemotely(
            controlNode.toHost(),
            "sudo mkdir -p ${GrafanaManifestBuilder.GRAFANA_DATA_PATH} && " +
                "sudo chown -R ${GrafanaManifestBuilder.GRAFANA_UID}:${GrafanaManifestBuilder.GRAFANA_UID} ${GrafanaManifestBuilder.GRAFANA_DATA_PATH}",
        )
    }

    /**
     * Creates the cluster-config ConfigMap with runtime values needed by OTel: the control node IP
     * (log/OTLP destination), AWS region, S3 bucket + prefix, and cluster name (metric label).
     */
    private fun createClusterConfigMap(
        controlNode: ClusterHost,
        region: String,
    ) {
        val clusterState = clusterStateManager.load()
        val configData =
            mapOf(
                "control_node_ip" to controlNode.privateIp,
                "aws_region" to region,
                "s3_bucket" to (clusterState.s3Bucket ?: ""),
                "cluster_s3_prefix" to clusterState.clusterPrefix(),
                "cluster_name" to clusterState.clusterLabelName(),
            )

        k8sService
            .createConfigMap(
                controlHost = controlNode,
                namespace = DEFAULT_NAMESPACE,
                name = CLUSTER_CONFIG_NAME,
                data = configData,
                labels = mapOf("app.kubernetes.io/managed-by" to "easy-db-lab"),
            ).getOrElse { exception ->
                // The cluster-config ConfigMap is the source of the `cluster` label every signal
                // ships with (origin identity). If it is missing, metrics/logs/traces/profiles
                // ship unlabeled and two DCs become indistinguishable on one Grafana — so fail
                // fast rather than report a successful `up` over a broken identity mechanism.
                eventBus.emit(Event.Provision.ClusterConfigMapFailed(exception.message ?: exception.toString()))
                error("Failed to create cluster-config ConfigMap: ${exception.message}")
            }
    }
}
