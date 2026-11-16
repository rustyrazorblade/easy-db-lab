package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.ConfigHashAnnotator
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.WorkloadScrapeConfig
import io.fabric8.kubernetes.api.model.HasMetadata

/**
 * The OTel collector's resources for one cluster, hashed by [ConfigHashAnnotator].
 *
 * Two paths apply the collector: the observability stack deploy (`up`, `grafana update-config`)
 * and the kit-driven sync ([OtelSyncService]). They must stamp the same config hash on the
 * collector's pod template, or the next deploy after a kit start sees a "changed" collector and
 * rolls it for nothing. Both build it here, so every input to that hash — the export
 * destinations, the CNI, the runtime `cluster-config` — comes from the same place.
 */
object CollectorResources {
    /**
     * Builds the collector's resources for [clusterState] with [scrapeConfigs], hashed against its
     * own ConfigMaps and the `cluster-config` data for [controlHost].
     *
     * The export destinations and the CNI are read from the cluster's recorded init config. A
     * cluster with no recorded CNI predates Cilium and runs Flannel.
     */
    fun build(
        otelManifestBuilder: OtelManifestBuilder,
        controlHost: ClusterHost,
        clusterState: ClusterState,
        region: String,
        scrapeConfigs: List<WorkloadScrapeConfig>,
    ): List<HasMetadata> {
        val initConfig = clusterState.initConfig
        return ConfigHashAnnotator.annotate(
            otelManifestBuilder.buildAllResources(
                scrapeConfigs,
                initConfig?.telemetryRedirect,
                initConfig?.cni ?: CniMode.Flannel,
            ),
            mapOf(ClusterConfigData.NAME to ClusterConfigData.of(controlHost, clusterState, region)),
        )
    }
}
