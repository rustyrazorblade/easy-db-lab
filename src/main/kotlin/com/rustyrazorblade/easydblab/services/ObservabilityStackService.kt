package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ConfigHashAnnotator
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.kubestatemetrics.KubeStateMetricsManifestBuilder
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.JournaldOtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.registry.RegistryManifestBuilder
import com.rustyrazorblade.easydblab.configuration.s3manager.S3ManagerManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.configuration.yace.YaceManifestBuilder
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.HasMetadata

/**
 * Builds and applies the full observability stack to the K8s cluster.
 *
 * This is the orchestration extracted out of the `grafana update-config` command so that both the
 * command and cluster bring-up (`up`) drive one code path. It owns the whole sequence: the runtime
 * `cluster-config` ConfigMap, every Fabric8-built resource, the Pyroscope, Tempo, Mimir, Loki and
 * Grafana on-node data directories, dashboard upload, the wait for the applied workloads' rollouts to complete, and
 * the final namespace-wide readiness gate.
 *
 * No workload is force-restarted. Each workload's pod template carries a hash of the ConfigMaps it
 * reads ([ConfigHashAnnotator]), so Kubernetes rolls exactly the workloads whose configuration
 * changed and leaves the rest running — a dashboard edit no longer restarts Tempo or Pyroscope.
 * [ConfigChangeReport] tells the operator which workloads that is.
 *
 * Redirect mode is a first-class branch, not a set of skipped steps bolted on: when the cluster's
 * init config records a [TelemetryRedirect], the four local backends (Mimir, Loki, Tempo,
 * the Pyroscope server) plus Grafana and its dashboards are never built, and the collectors and the
 * Pyroscope eBPF agent are pointed at the external stack instead. The rollout-wait list is derived
 * from the resources actually applied, so redirect never waits on a workload it did not deploy.
 */
interface ObservabilityStackService {
    /**
     * Deploys the observability stack to [controlNode].
     *
     * The mode comes from the cluster state: when its init config records a [TelemetryRedirect],
     * the collectors are pointed at that external stack and the local backends, Grafana, and
     * dashboards are skipped. Reading it from the state, not from the caller, is what keeps the
     * collector this deploy applies identical to the one a kit sync applies ([CollectorResources]).
     *
     * @param controlNode the cluster's control node running K3s.
     * @return success once the stack is applied and the namespace reaches Ready; failure otherwise.
     */
    fun deploy(controlNode: ClusterHost): Result<Unit>
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
    private val mimirManifestBuilder: MimirManifestBuilder,
    private val lokiManifestBuilder: LokiManifestBuilder,
    private val tempoManifestBuilder: TempoManifestBuilder,
    private val registryManifestBuilder: RegistryManifestBuilder,
    private val s3ManagerManifestBuilder: S3ManagerManifestBuilder,
    private val beylaManifestBuilder: BeylaManifestBuilder,
    private val pyroscopeManifestBuilder: PyroscopeManifestBuilder,
    private val yaceManifestBuilder: YaceManifestBuilder,
    private val kubeStateMetricsManifestBuilder: KubeStateMetricsManifestBuilder,
    private val configChangeReport: ConfigChangeReport,
) : ObservabilityStackService {
    companion object {
        private const val DEFAULT_NAMESPACE = "default"
    }

    /** One deploy stage: a human label and the resources it applies. */
    private data class Stage(
        val label: String,
        val resources: List<HasMetadata>,
    )

    override fun deploy(controlNode: ClusterHost): Result<Unit> =
        runCatching {
            val clusterState = clusterStateManager.load()
            val telemetryRedirect = clusterState.initConfig?.telemetryRedirect

            val clusterConfig = createClusterConfigMap(controlNode, clusterState)

            val scrapeConfigs =
                k8sClientProvider.createClient(controlNode).use { client ->
                    otelManifestBuilder.listWorkloadScrapeConfigs(client)
                }

            // The collector arrives already hashed, by the same function the kit sync uses, so both
            // stamp it with the same config hash.
            val collector =
                Stage(
                    "OTel Collector",
                    CollectorResources.build(otelManifestBuilder, controlNode, clusterState, user.region, scrapeConfigs),
                )

            // Collectors and supporting infra deploy in both modes; the local telemetry backends and
            // Grafana are local-mode only. The Pyroscope server directory and dashboard upload hang
            // off those, so they are guarded the same way.
            val stages =
                buildList {
                    add(Stage("kube-state-metrics", kubeStateMetricsManifestBuilder.buildAllResources()))
                    add(Stage("Fluent Bit Journald", journaldOtelManifestBuilder.buildAllResources()))
                    add(Stage("ebpf_exporter", ebpfExporterManifestBuilder.buildAllResources()))
                    add(Stage("Registry", registryManifestBuilder.buildAllResources()))
                    add(Stage("S3 Manager", s3ManagerManifestBuilder.buildAllResources()))
                    add(Stage("Beyla", beylaManifestBuilder.buildAllResources()))
                    add(Stage("YACE", yaceManifestBuilder.buildAllResources()))
                    if (telemetryRedirect == null) {
                        add(Stage("Mimir", mimirManifestBuilder.buildAllResources()))
                        add(Stage("Loki", lokiManifestBuilder.buildAllResources()))
                        add(Stage("Tempo", tempoManifestBuilder.buildAllResources()))
                        add(Stage("Pyroscope", pyroscopeManifestBuilder.buildAllResources()))
                    } else {
                        // Redirect: the eBPF agent still runs on every node, pointed at the external
                        // Pyroscope; the server does not exist here.
                        add(Stage("Pyroscope Agent", pyroscopeManifestBuilder.buildAgentResources(telemetryRedirect)))
                    }
                }

            // The Pyroscope server, Tempo, Mimir and Loki write to host directories; the agents do
            // not, so prepare them only in local mode.
            if (telemetryRedirect == null) {
                preparePyroscopeDirectory(controlNode)
                prepareTempoDirectory(controlNode)
                prepareMimirDirectory(controlNode)
                prepareLokiDirectory(controlNode)
            }

            // Every ConfigMap this deploy knows the contents of, so a workload that reads one from
            // another stage, or the runtime cluster-config, is hashed on it too.
            val knownConfigMaps =
                mapOf(ClusterConfigData.NAME to clusterConfig) +
                    (listOf(collector) + stages)
                        .flatMap { it.resources }
                        .filterIsInstance<ConfigMap>()
                        .associate { it.metadata.name to it.data.orEmpty() }

            // Hash every stage before applying any, so a workload reading a ConfigMap this deploy
            // cannot hash fails the deploy before it touches the cluster.
            val hashedStages =
                listOf(collector) + stages.map { it.copy(resources = ConfigHashAnnotator.annotate(it.resources, knownConfigMaps)) }
            val appliedResources = hashedStages.flatMap { it.resources }
            configChangeReport.report(controlNode, appliedResources, DEFAULT_NAMESPACE)
            for (stage in hashedStages) {
                applyStage(stage, controlNode)
            }

            if (telemetryRedirect == null) {
                prepareGrafanaDirectory(controlNode)
                dashboardService.uploadDashboards(controlNode, clusterState.tenant()).getOrElse { exception ->
                    error("Failed to upload dashboards: ${exception.message}")
                }
            }

            // Grafana is applied by the dashboard service, not a stage, and rolls on a datasource change.
            val grafana =
                when (telemetryRedirect) {
                    null -> listOf(WorkloadRef(WorkloadKind.Deployment, GrafanaManifestBuilder.DEPLOYMENT_NAME))
                    else -> emptyList()
                }
            val workloads = appliedWorkloads(appliedResources) + grafana

            // Wait for every applied workload's rollout to finish before the readiness gate. Right
            // after a changed workload starts rolling the old pods are still Ready and the
            // replacements may not exist yet, so a pod-readiness check alone reports the stack ready
            // with new pods at 0/1.
            k8sService
                .waitForRollouts(controlNode, workloads, DEFAULT_NAMESPACE, Constants.K8s.OBSERVABILITY_READY_TIMEOUT_SECONDS)
                .getOrElse { exception ->
                    error("Observability stack did not finish rolling out: ${exception.message}")
                }

            // Gate success on the whole stack reaching Ready. waitForPodsReady is namespace-wide and
            // fail-fast: it aborts on CrashLoopBackOff / ImagePullBackOff and on timeout, so `up`
            // never reports success while the observability stack is broken or still coming up.
            // Pods that ran to completion (a kit's Job pods share this namespace) are not awaited.
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
     * Every Deployment and DaemonSet among [appliedResources]. Deriving the list from what was
     * applied means redirect never waits on a backend it did not deploy.
     */
    private fun appliedWorkloads(appliedResources: List<HasMetadata>): List<WorkloadRef> =
        appliedResources
            .mapNotNull { resource ->
                val name = resource.metadata?.name ?: return@mapNotNull null
                when (resource.kind) {
                    "Deployment" -> WorkloadRef(WorkloadKind.Deployment, name)
                    "DaemonSet" -> WorkloadRef(WorkloadKind.DaemonSet, name)
                    else -> null
                }
            }.distinct()

    private fun preparePyroscopeDirectory(controlNode: ClusterHost) {
        eventBus.emit(Event.Grafana.PyroscopeDirectoryPreparing)
        remoteOps.executeRemotely(
            controlNode.toHost(),
            "sudo mkdir -p /mnt/db1/pyroscope && " +
                "sudo chown -R ${PyroscopeManifestBuilder.PYROSCOPE_UID}:${PyroscopeManifestBuilder.PYROSCOPE_UID} /mnt/db1/pyroscope",
        )
    }

    /**
     * Tempo keeps both write-ahead logs under [TempoManifestBuilder.DATA_HOST_PATH], so spans that
     * were received but not yet cut into a block survive a pod restart. The image runs as
     * [TempoManifestBuilder.TEMPO_UID] and must own the directory.
     */
    private fun prepareTempoDirectory(controlNode: ClusterHost) {
        eventBus.emit(Event.Grafana.TempoDirectoryPreparing)
        val path = TempoManifestBuilder.DATA_HOST_PATH
        val uid = TempoManifestBuilder.TEMPO_UID
        remoteOps.executeRemotely(controlNode.toHost(), "sudo mkdir -p $path && sudo chown -R $uid:$uid $path")
    }

    /**
     * Mimir keeps its TSDB — head, WAL and every local block — under
     * [MimirManifestBuilder.DATA_HOST_PATH]. The image runs as root, so only the directory is needed.
     */
    private fun prepareMimirDirectory(controlNode: ClusterHost) {
        eventBus.emit(Event.Grafana.MimirDirectoryPreparing)
        remoteOps.executeRemotely(controlNode.toHost(), "sudo mkdir -p ${MimirManifestBuilder.DATA_HOST_PATH}")
    }

    /**
     * Loki keeps its WAL, TSDB index head, index cache and compactor directory under
     * [LokiManifestBuilder.DATA_HOST_PATH]. The image runs as [LokiManifestBuilder.LOKI_UID] and must
     * own the directory.
     */
    private fun prepareLokiDirectory(controlNode: ClusterHost) {
        eventBus.emit(Event.Grafana.LokiDirectoryPreparing)
        val path = LokiManifestBuilder.DATA_HOST_PATH
        val uid = LokiManifestBuilder.LOKI_UID
        remoteOps.executeRemotely(controlNode.toHost(), "sudo mkdir -p $path && sudo chown -R $uid:$uid $path")
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
     * Creates the cluster-config ConfigMap with runtime values needed by OTel and Tempo
     * ([ClusterConfigData]), returning its data.
     */
    private fun createClusterConfigMap(
        controlNode: ClusterHost,
        clusterState: ClusterState,
    ): Map<String, String> {
        val configData = ClusterConfigData.of(controlNode, clusterState, user.region)

        k8sService
            .createConfigMap(
                controlHost = controlNode,
                namespace = DEFAULT_NAMESPACE,
                name = ClusterConfigData.NAME,
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
        return configData
    }
}
