package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder
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
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.configuration.yace.YaceManifestBuilder
import io.fabric8.kubernetes.api.model.HasMetadata

/**
 * The manifest builders of the collectors that gather signals in the cluster: the host collector
 * add-on, journald, the eBPF exporter, Beyla, YACE and kube-state-metrics. Grouped so the stack
 * service depends on two groups rather than on every builder.
 */
class CollectorAgentManifests(
    val otelHostCollector: OtelHostCollectorManifestBuilder,
    val kubeStateMetrics: KubeStateMetricsManifestBuilder,
    val journaldOtel: JournaldOtelManifestBuilder,
    val ebpfExporter: EbpfExporterManifestBuilder,
    val beyla: BeylaManifestBuilder,
    val yace: YaceManifestBuilder,
)

/**
 * The manifest builders of what stores and serves the signals: Mimir, Loki, Tempo, Pyroscope (server
 * and eBPF agent), and the registry and S3 manager that support them.
 */
class BackendManifests(
    val mimir: MimirManifestBuilder,
    val loki: LokiManifestBuilder,
    val tempo: TempoManifestBuilder,
    val pyroscope: PyroscopeManifestBuilder,
    val registry: RegistryManifestBuilder,
    val s3Manager: S3ManagerManifestBuilder,
)

/**
 * The core OTel collector's resources, built by [CollectorResources] from the workload scrape configs
 * the running kits registered, so the deploy and a kit sync stamp the same config hash.
 */
class CoreCollectorManifests(
    private val k8sClientProvider: K8sClientProvider,
    private val otelManifestBuilder: OtelManifestBuilder,
) {
    /** The collector's hashed resources for [reading]. */
    fun resources(reading: ObservabilityDeployInputs.Reading): List<HasMetadata> {
        val scrapeConfigs = k8sClientProvider.createClient().use { otelManifestBuilder.listWorkloadScrapeConfigs(it) }
        return CollectorResources.build(
            otelManifestBuilder,
            CollectorInputs(reading.state, reading.region, reading.contract.nodeCapabilities, reading.scope),
            scrapeConfigs,
        )
    }
}

/**
 * Every manifest the observability stack applies: the core collector first, then each
 * [ObservabilityStage], the account compactor ([CompactorStage]) included. The one place a stage
 * is mapped to the builder that renders it.
 */
class ObservabilityStageManifests(
    val coreCollector: CoreCollectorManifests,
    private val collectors: CollectorAgentManifests,
    private val backends: BackendManifests,
    private val compactor: CompactorStage,
) {
    /**
     * The resources [stage] applies for [clusterState] with [hosting]. Call it only for a stage the
     * environment can run: the account compactor's prepares the control node over SSH first.
     */
    fun resourcesOf(
        stage: ObservabilityStage,
        clusterState: ClusterState,
        hosting: ObservabilityHosting,
        scope: InstallScope,
    ): List<HasMetadata> {
        val cni = clusterState.initConfig?.cni ?: CniMode.Flannel
        return when (stage) {
            ObservabilityStage.OTEL_HOST_COLLECTOR -> collectors.otelHostCollector.buildAllResources(scope, cni)
            ObservabilityStage.KUBE_STATE_METRICS -> collectors.kubeStateMetrics.buildAllResources(scope, hosting.placement)
            ObservabilityStage.FLUENT_BIT_JOURNALD -> collectors.journaldOtel.buildAllResources(scope)
            ObservabilityStage.EBPF_EXPORTER -> collectors.ebpfExporter.buildAllResources(scope)
            ObservabilityStage.BEYLA -> collectors.beyla.buildAllResources(scope)
            ObservabilityStage.YACE -> collectors.yace.buildAllResources(scope)
            ObservabilityStage.ACCOUNT_COMPACTOR -> compactor.prepare(compactorNode(clusterState), clusterState, scope)
            // The core collector already left its pod-log receiver out (CollectorResources).
            ObservabilityStage.POD_LOGS -> emptyList()
            else -> backendResourcesOf(stage, clusterState, hosting, scope)
        }
    }

    /** The resources of a backend [stage]: storage, serving and the registry and S3 manager. */
    private fun backendResourcesOf(
        stage: ObservabilityStage,
        clusterState: ClusterState,
        hosting: ObservabilityHosting,
        scope: InstallScope,
    ): List<HasMetadata> =
        when (stage) {
            ObservabilityStage.REGISTRY -> backends.registry.buildAllResources(scope)
            ObservabilityStage.S3_MANAGER -> backends.s3Manager.buildAllResources(scope)
            ObservabilityStage.MIMIR -> backends.mimir.buildAllResources(scope, hosting)
            ObservabilityStage.LOKI -> backends.loki.buildAllResources(scope, hosting)
            ObservabilityStage.TEMPO -> backends.tempo.buildAllResources(scope, hosting)
            ObservabilityStage.PYROSCOPE -> backends.pyroscope.buildServerResources(scope, hosting)
            // Every node, in both modes; under a redirect it points at the external Pyroscope.
            ObservabilityStage.PYROSCOPE_AGENT ->
                backends.pyroscope.buildAgentResources(scope, clusterState.initConfig?.telemetryRedirect)
            else -> error("$stage is not a backend stage")
        }

    private fun compactorNode(clusterState: ClusterState): ClusterHost =
        checkNotNull(clusterState.getControlHost()) {
            "The account compactor is prepared on the control node, but the cluster state has no control node."
        }
}
