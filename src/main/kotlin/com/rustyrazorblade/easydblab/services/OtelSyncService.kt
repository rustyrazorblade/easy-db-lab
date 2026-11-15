package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Regenerates the OTel collector's configuration when the metrics registry changes (a kit
 * start/stop), so the collector scrapes exactly the running kits.
 */
interface OtelSyncService {
    /**
     * Re-applies the collector. The K8s API is reached through the workspace kubeconfig; the control
     * node in state is needed only where the backends publish their ports on it (see
     * [ClusterConfigData.publishedNodeIp]).
     */
    fun syncConfigMap(): Result<Unit>
}

/**
 * Re-applies the collector's resources — its ConfigMap and DaemonSet among them — built and hashed
 * by [CollectorResources], the same function the observability stack deploy uses.
 *
 * The ConfigMap is mounted with subPath, so running pods never see it change; the collector must
 * roll to pick up new scrape jobs. It rolls because its template's config hash changes with the
 * ConfigMap, not because of a forced restart. A forced restart would leave the old hash on the
 * template, and the next `up` or `grafana update-config` would see a "changed" collector and roll
 * it again for nothing.
 *
 * @property capabilitiesOf derives what the environment provides from cluster state; a test seam
 *   like the one on [DefaultObservabilityStackService] (consolidated by #1040).
 * @property hostingOf derives how the stack is hosted from cluster state, the same seam. Where the
 *   backends are not published on a node, the collector this renders carries no node IP, so a sync
 *   needs nothing from the control host but the K8s API it reaches.
 */
class DefaultOtelSyncService(
    private val k8sClientProvider: K8sClientProvider,
    private val k8sService: K8sService,
    private val otelManifestBuilder: OtelManifestBuilder,
    private val clusterStateManager: ClusterStateManager,
    private val user: User,
    private val configChangeReport: ConfigChangeReport,
    private val capabilitiesOf: (ClusterState) -> NodeCapabilities = NodeCapabilities::from,
    private val hostingOf: (ClusterState) -> ObservabilityHosting = ObservabilityHosting::from,
) : OtelSyncService {
    private val log = KotlinLogging.logger {}

    override fun syncConfigMap(): Result<Unit> =
        runCatching {
            log.debug { "Syncing OTel collector configuration" }
            val clusterState = clusterStateManager.load()
            val scrapeConfigs =
                k8sClientProvider.createClient().use { client ->
                    otelManifestBuilder.listWorkloadScrapeConfigs(client)
                }
            // The cluster's export destinations and CNI come from its recorded init config, so a
            // regenerated ConfigMap keeps a redirect cluster's external endpoints and a Cilium
            // cluster's Cilium scrape jobs.
            val resources =
                CollectorResources.build(
                    otelManifestBuilder,
                    hostingOf(clusterState),
                    clusterState,
                    user.region,
                    scrapeConfigs,
                    capabilitiesOf(clusterState),
                )
            configChangeReport.report(resources, Constants.K8s.NAMESPACE)
            for (resource in resources) {
                k8sService.applyResource(resource = resource).getOrThrow()
            }
        }
}
