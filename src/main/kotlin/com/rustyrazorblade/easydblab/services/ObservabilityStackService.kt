package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.ConfigHashAnnotator
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.kubestatemetrics.KubeStateMetricsManifestBuilder
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.JournaldOtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelHostCollectorManifestBuilder
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
    fun deploy(): Result<Unit>
}

/**
 * Default implementation that applies the stack over the K8s API and configures on-node
 * directories over SSH.
 *
 * @property capabilitiesOf derives what the environment provides from cluster state. It is a test
 *   seam so a unit test can run a non-AWS environment; production takes the default. The four such
 *   seams (here, [DefaultOtelSyncService], [com.rustyrazorblade.easydblab.commands.Status] and
 *   [com.rustyrazorblade.easydblab.server.StatusCache]) are consolidated by #1040 into one provider.
 */
@Suppress("LongParameterList")
class DefaultObservabilityStackService(
    private val k8sService: K8sService,
    private val k8sClientProvider: K8sClientProvider,
    private val remoteOps: RemoteOperationsService,
    private val clusterStateManager: ClusterStateManager,
    private val user: User,
    private val eventBus: EventBus,
    private val grafanaDeployService: GrafanaDeployService,
    private val otelManifestBuilder: OtelManifestBuilder,
    private val otelHostCollectorManifestBuilder: OtelHostCollectorManifestBuilder,
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
    private val installContextFactory: DashboardInstallContextFactory,
    private val capabilitiesOf: (ClusterState) -> NodeCapabilities = NodeCapabilities::from,
) : ObservabilityStackService {
    companion object {
        private const val DEFAULT_NAMESPACE = "default"
    }

    /**
     * One deploy stage: a human label and the resources it applies. A stage is applied only where
     * the environment provides every capability in [requires]; a stage that needs more than one
     * (e.g. journald needs a systemd journal *and* a privileged pod to read it) is skipped if any is
     * absent. Every workload in [rolledOutFirst] has finished rolling out before the stage is applied.
     */
    private data class Stage(
        val label: String,
        val resources: List<HasMetadata>,
        val requires: Set<NodeCapability> = emptySet(),
        val rolledOutFirst: List<WorkloadRef> = emptyList(),
    )

    /**
     * The stages the environment can run. Not a pure filter: a stage it cannot run is reported as
     * skipped with one [Event.Grafana.StageSkipped] per capability it is missing, so the operator
     * sees every reason the stage was left out. Call it once, before anything is applied.
     */
    private fun applyCapabilityGate(
        stages: List<Stage>,
        capabilities: NodeCapabilities,
    ): List<Stage> =
        stages.filter { stage ->
            val missing = stage.requires.filter { it !in capabilities }
            missing.forEach { eventBus.emit(Event.Grafana.StageSkipped(stage.label, it)) }
            missing.isEmpty()
        }

    override fun deploy(): Result<Unit> =
        runCatching {
            val clusterState = clusterStateManager.load()
            val controlNode =
                checkNotNull(clusterState.getControlHost()) {
                    "No control node in cluster state; cannot deploy the observability stack."
                }
            val telemetryRedirect = clusterState.initConfig?.telemetryRedirect
            val capabilities = capabilitiesOf(clusterState)

            val clusterConfig = createClusterConfigMap(clusterState)

            val scrapeConfigs =
                k8sClientProvider.createClient().use { client ->
                    otelManifestBuilder.listWorkloadScrapeConfigs(client)
                }

            // The collector arrives already hashed, by the same function the kit sync uses, so both
            // stamp it with the same config hash.
            val collector =
                Stage(
                    "OTel Collector",
                    CollectorResources.build(otelManifestBuilder, controlNode, clusterState, user.region, scrapeConfigs, capabilities),
                )

            // The local backends and Grafana are local-mode only. The Pyroscope server directory and
            // dashboard upload hang off those, so they are guarded the same way.
            val stages = applyCapabilityGate(buildStages(telemetryRedirect, clusterState.initConfig?.cni ?: CniMode.Flannel), capabilities)

            // The Pyroscope server, Tempo, Mimir and Loki write to host directories; the agents do
            // not, so prepare them only in local mode.
            if (telemetryRedirect == null) {
                preparePyroscopeDirectory(controlNode.toHost())
                prepareTempoDirectory(controlNode.toHost())
                prepareMimirDirectory(controlNode.toHost())
                prepareLokiDirectory(controlNode.toHost())
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
            configChangeReport.report(appliedResources, DEFAULT_NAMESPACE)
            for (stage in hashedStages) {
                awaitRollouts(stage.rolledOutFirst)
                applyStage(stage)
            }

            if (telemetryRedirect == null) {
                prepareGrafanaDirectory(controlNode.toHost())
                uploadDashboards(clusterState)
            }

            // Grafana is applied by the Grafana deploy service, not a stage, and rolls on a datasource change.
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
                .waitForRollouts(workloads, DEFAULT_NAMESPACE, Constants.K8s.OBSERVABILITY_READY_TIMEOUT_SECONDS)
                .getOrElse { exception ->
                    error("Observability stack did not finish rolling out: ${exception.message}")
                }

            // Gate success on the whole stack reaching Ready. waitForPodsReady is namespace-wide and
            // fail-fast: it aborts on CrashLoopBackOff / ImagePullBackOff and on timeout, so `up`
            // never reports success while the observability stack is broken or still coming up.
            // Pods that ran to completion (a kit's Job pods share this namespace) are not awaited.
            k8sService
                .waitForPodsReady(Constants.K8s.OBSERVABILITY_READY_TIMEOUT_SECONDS)
                .getOrElse { exception ->
                    error("Observability stack did not become ready: ${exception.message}")
                }
        }

    /** Waits for [workloads] to finish rolling out, failing the deploy if they do not. */
    private fun awaitRollouts(workloads: List<WorkloadRef>) {
        if (workloads.isEmpty()) return
        k8sService
            .waitForRollouts(workloads, DEFAULT_NAMESPACE, Constants.K8s.OBSERVABILITY_READY_TIMEOUT_SECONDS)
            .getOrElse { exception -> error("$workloads did not finish rolling out: ${exception.message}") }
    }

    /**
     * The stages to apply after the collector. Collectors and supporting infra deploy in both modes;
     * the local telemetry backends are local-mode only.
     *
     * The host add-on comes first and waits for the core's rollout: on an in-place update the old
     * core pods held hostPorts 4317 and 4318, which the add-on binds on the host. The Pyroscope eBPF
     * agent is privileged and is its own stage, so an environment without privileged pods still
     * runs, and waits on, the Pyroscope server.
     */
    private fun buildStages(
        telemetryRedirect: TelemetryRedirect?,
        cni: CniMode,
    ): List<Stage> =
        ObservabilityStage.declared(telemetryRedirect).map { stage ->
            Stage(stage.label, resourcesOf(stage, telemetryRedirect, cni), stage.requires, rolledOutFirstFor(stage))
        }

    private fun rolledOutFirstFor(stage: ObservabilityStage): List<WorkloadRef> =
        when (stage) {
            ObservabilityStage.OTEL_HOST_COLLECTOR -> listOf(WorkloadRef(WorkloadKind.DaemonSet, Constants.K8s.OTEL_COLLECTOR_APP_LABEL))
            else -> emptyList()
        }

    private fun resourcesOf(
        stage: ObservabilityStage,
        telemetryRedirect: TelemetryRedirect?,
        cni: CniMode,
    ): List<HasMetadata> =
        when (stage) {
            ObservabilityStage.OTEL_HOST_COLLECTOR -> otelHostCollectorManifestBuilder.buildAllResources(cni)
            ObservabilityStage.KUBE_STATE_METRICS -> kubeStateMetricsManifestBuilder.buildAllResources()
            ObservabilityStage.FLUENT_BIT_JOURNALD -> journaldOtelManifestBuilder.buildAllResources()
            ObservabilityStage.EBPF_EXPORTER -> ebpfExporterManifestBuilder.buildAllResources()
            ObservabilityStage.REGISTRY -> registryManifestBuilder.buildAllResources()
            ObservabilityStage.S3_MANAGER -> s3ManagerManifestBuilder.buildAllResources()
            ObservabilityStage.BEYLA -> beylaManifestBuilder.buildAllResources()
            ObservabilityStage.YACE -> yaceManifestBuilder.buildAllResources()
            ObservabilityStage.MIMIR -> mimirManifestBuilder.buildAllResources()
            ObservabilityStage.LOKI -> lokiManifestBuilder.buildAllResources()
            ObservabilityStage.TEMPO -> tempoManifestBuilder.buildAllResources()
            ObservabilityStage.PYROSCOPE -> pyroscopeManifestBuilder.buildServerResources()
            // Every node, in both modes; under a redirect it points at the external Pyroscope.
            ObservabilityStage.PYROSCOPE_AGENT -> pyroscopeManifestBuilder.buildAgentResources(telemetryRedirect)
            // The core collector already left its pod-log receiver out (CollectorResources).
            ObservabilityStage.POD_LOGS -> emptyList()
        }

    /**
     * Deploys Grafana with a datasource for every tenant in the shared store, so each deploy picks up
     * new ones, and the dashboards with the install-time pass for this cluster.
     */
    private fun uploadDashboards(clusterState: ClusterState) {
        val controlNode =
            checkNotNull(clusterState.getControlHost()) {
                "No control node in cluster state; cannot upload dashboards."
            }
        grafanaDeployService
            .deploy(controlNode, installContextFactory.forCluster(clusterState, controlNode), ObservabilityStore.from(clusterState).bucket)
            .getOrElse { exception ->
                error("Failed to upload dashboards: ${exception.message}")
            }
    }

    private fun applyStage(stage: Stage) {
        eventBus.emit(Event.Grafana.LabelResourcesApplying(stage.label))
        for (resource in stage.resources) {
            k8sService.applyResource(resource).getOrElse { exception ->
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

    private fun preparePyroscopeDirectory(controlNode: Host) {
        eventBus.emit(Event.Grafana.PyroscopeDirectoryPreparing)
        remoteOps.executeRemotely(
            controlNode,
            "sudo mkdir -p /mnt/db1/pyroscope && " +
                "sudo chown -R ${PyroscopeManifestBuilder.PYROSCOPE_UID}:${PyroscopeManifestBuilder.PYROSCOPE_UID} /mnt/db1/pyroscope",
        )
    }

    /**
     * Tempo keeps both write-ahead logs under [TempoManifestBuilder.DATA_HOST_PATH], so spans that
     * were received but not yet cut into a block survive a pod restart. The image runs as
     * [TempoManifestBuilder.TEMPO_UID] and must own the directory.
     */
    private fun prepareTempoDirectory(controlNode: Host) {
        eventBus.emit(Event.Grafana.TempoDirectoryPreparing)
        val path = TempoManifestBuilder.DATA_HOST_PATH
        val uid = TempoManifestBuilder.TEMPO_UID
        remoteOps.executeRemotely(controlNode, "sudo mkdir -p $path && sudo chown -R $uid:$uid $path")
    }

    /**
     * Mimir keeps its TSDB — head, WAL and every local block — under
     * [MimirManifestBuilder.DATA_HOST_PATH]. The image runs as root, so only the directory is needed.
     */
    private fun prepareMimirDirectory(controlNode: Host) {
        eventBus.emit(Event.Grafana.MimirDirectoryPreparing)
        remoteOps.executeRemotely(controlNode, "sudo mkdir -p ${MimirManifestBuilder.DATA_HOST_PATH}")
    }

    /**
     * Loki keeps its WAL, TSDB index head, index cache and compactor directory under
     * [LokiManifestBuilder.DATA_HOST_PATH]. The image runs as [LokiManifestBuilder.LOKI_UID] and must
     * own the directory.
     */
    private fun prepareLokiDirectory(controlNode: Host) {
        eventBus.emit(Event.Grafana.LokiDirectoryPreparing)
        val path = LokiManifestBuilder.DATA_HOST_PATH
        val uid = LokiManifestBuilder.LOKI_UID
        remoteOps.executeRemotely(controlNode, "sudo mkdir -p $path && sudo chown -R $uid:$uid $path")
    }

    private fun prepareGrafanaDirectory(controlNode: Host) {
        eventBus.emit(Event.Grafana.GrafanaDirectoryPreparing)
        remoteOps.executeRemotely(
            controlNode,
            "sudo mkdir -p ${GrafanaManifestBuilder.GRAFANA_DATA_PATH} && " +
                "sudo chown -R ${GrafanaManifestBuilder.GRAFANA_UID}:${GrafanaManifestBuilder.GRAFANA_UID} ${GrafanaManifestBuilder.GRAFANA_DATA_PATH}",
        )
    }

    /**
     * Creates the cluster-config ConfigMap with runtime values needed by OTel and Tempo
     * ([ClusterConfigData]), returning its data.
     */
    private fun createClusterConfigMap(clusterState: ClusterState): Map<String, String> {
        val controlNode =
            checkNotNull(clusterState.getControlHost()) {
                "No control node in cluster state; cannot build the cluster-config ConfigMap."
            }
        val configData = ClusterConfigData.of(controlNode, clusterState, user.region)

        k8sService
            .createConfigMap(
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
