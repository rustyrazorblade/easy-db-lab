package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ConfigHashAnnotator
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.PodLogSource
import com.rustyrazorblade.easydblab.configuration.otel.WorkloadScrapeConfig
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import io.fabric8.kubernetes.api.model.HasMetadata

/**
 * What one cluster contributes to its collector config: its state (export destinations and the
 * `cluster-config` data), its region, the capabilities that decide the environment-bound scrape jobs
 * and the pod-log source, and the scope the resources are installed with. The deploy and the kit sync
 * each build one from the same readings, so they render the same config.
 */
data class CollectorInputs(
    val clusterState: ClusterState,
    val region: String,
    val capabilities: NodeCapabilities,
    val scope: InstallScope,
)

/**
 * The OTel collector's resources for one cluster, hashed by [ConfigHashAnnotator].
 *
 * Two paths apply the collector: the observability stack deploy (`up`, `grafana update-config`)
 * and the kit-driven sync ([OtelSyncService]). They must stamp the same config hash on the
 * collector's pod template, or the next deploy after a kit start sees a "changed" collector and
 * rolls it for nothing. Both build it here, so every input to that hash — the export
 * destinations, the pod-log source, the runtime `cluster-config` — comes from the same place.
 */
object CollectorResources {
    /**
     * Builds the collector's resources for [inputs] with [scrapeConfigs], hashed against its own
     * ConfigMaps and the cluster's `cluster-config` data.
     *
     * The export destinations are read from the cluster's recorded init config. The CNI scrape jobs
     * are the host add-on's, so the core does not depend on the CNI.
     *
     * The capabilities decide which environment-bound scrape jobs exist (`yace` needs CloudWatch) and
     * whether the core tails pod log files (`HOST_PATH`);
     * both callers derive it with `NodeCapabilities.from` and pass it in, so the deploy and the kit sync render the same config.
     */
    fun build(
        otelManifestBuilder: OtelManifestBuilder,
        inputs: CollectorInputs,
        scrapeConfigs: List<WorkloadScrapeConfig>,
    ): List<HasMetadata> {
        val initConfig = inputs.clusterState.initConfig
        return ConfigHashAnnotator.annotate(
            otelManifestBuilder.buildAllResources(
                inputs.scope,
                inputs.capabilities,
                scrapeConfigs,
                initConfig?.telemetryRedirect,
                podLogSource(inputs.capabilities),
            ),
            mapOf(ClusterConfigData.NAME to ClusterConfigData.of(inputs.clusterState, inputs.region)),
        )
    }

    /** Pod log files are on the node, so the core reads them only where hostPath volumes exist. */
    fun podLogSource(capabilities: NodeCapabilities): PodLogSource =
        if (NodeCapability.HOST_PATH in capabilities) PodLogSource.NodeFiles else PodLogSource.None
}
