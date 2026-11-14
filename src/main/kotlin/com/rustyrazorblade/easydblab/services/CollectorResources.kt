package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ConfigHashAnnotator
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.PodLogSource
import com.rustyrazorblade.easydblab.configuration.otel.WorkloadScrapeConfig
import io.fabric8.kubernetes.api.model.HasMetadata

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
     * Builds the collector's resources for [clusterState] with [scrapeConfigs], hashed against its
     * own ConfigMaps and the `cluster-config` data for [controlHost].
     *
     * The export destinations are read from the cluster's recorded init config. The CNI scrape jobs
     * are the host add-on's, so the core does not depend on the CNI.
     *
     * [capabilities] decides which environment-bound scrape jobs exist (`yace` needs CloudWatch) and
     * whether the core tails pod log files (`HOST_PATH`);
     * both callers derive it with `NodeCapabilities.from` and pass it in, so the deploy and the kit sync render the same config.
     */
    fun build(
        otelManifestBuilder: OtelManifestBuilder,
        controlHost: ClusterHost,
        clusterState: ClusterState,
        region: String,
        scrapeConfigs: List<WorkloadScrapeConfig>,
        capabilities: NodeCapabilities,
    ): List<HasMetadata> {
        val initConfig = clusterState.initConfig
        return ConfigHashAnnotator.annotate(
            otelManifestBuilder.buildAllResources(
                capabilities,
                scrapeConfigs,
                initConfig?.telemetryRedirect,
                podLogSource(capabilities),
            ),
            mapOf(ClusterConfigData.NAME to ClusterConfigData.of(controlHost, clusterState, region)),
        )
    }

    /** Pod log files are on the node, so the core reads them only where hostPath volumes exist. */
    fun podLogSource(capabilities: NodeCapabilities): PodLogSource =
        if (NodeCapability.HOST_PATH in capabilities) PodLogSource.NodeFiles else PodLogSource.None
}
