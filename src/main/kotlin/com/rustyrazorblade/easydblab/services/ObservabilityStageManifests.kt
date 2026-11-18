package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
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
            reading.state,
            reading.region,
            scrapeConfigs,
            reading.contract.nodeCapabilities,
        )
    }
}

/**
 * Every manifest the observability stack applies: the core collector first, then each
 * [ObservabilityStage]. The one place a stage is mapped to the builder that renders it.
 */
class ObservabilityStageManifests(
    val coreCollector: CoreCollectorManifests,
    private val collectors: CollectorAgentManifests,
    private val backends: BackendManifests,
) {
    /** The resources [stage] applies for a cluster with [telemetryRedirect], [cni] and [hosting]. */
    fun resourcesOf(
        stage: ObservabilityStage,
        telemetryRedirect: TelemetryRedirect?,
        cni: CniMode,
        hosting: ObservabilityHosting,
    ): List<HasMetadata> =
        when (stage) {
            ObservabilityStage.OTEL_HOST_COLLECTOR -> collectors.otelHostCollector.buildAllResources(cni)
            ObservabilityStage.KUBE_STATE_METRICS -> collectors.kubeStateMetrics.buildAllResources(hosting.placement)
            ObservabilityStage.FLUENT_BIT_JOURNALD -> collectors.journaldOtel.buildAllResources()
            ObservabilityStage.EBPF_EXPORTER -> collectors.ebpfExporter.buildAllResources()
            ObservabilityStage.REGISTRY -> backends.registry.buildAllResources()
            ObservabilityStage.S3_MANAGER -> backends.s3Manager.buildAllResources()
            ObservabilityStage.BEYLA -> collectors.beyla.buildAllResources()
            ObservabilityStage.YACE -> collectors.yace.buildAllResources()
            ObservabilityStage.MIMIR -> backends.mimir.buildAllResources(hosting)
            ObservabilityStage.LOKI -> backends.loki.buildAllResources(hosting)
            ObservabilityStage.TEMPO -> backends.tempo.buildAllResources(hosting)
            ObservabilityStage.PYROSCOPE -> backends.pyroscope.buildServerResources(hosting)
            // Every node, in both modes; under a redirect it points at the external Pyroscope.
            ObservabilityStage.PYROSCOPE_AGENT -> backends.pyroscope.buildAgentResources(telemetryRedirect)
            // The core collector already left its pod-log receiver out (CollectorResources).
            ObservabilityStage.POD_LOGS -> emptyList()
        }
}
