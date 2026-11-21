package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ProfileRegion
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContractSource
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.region
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Regenerates the OTel collector's configuration when the metrics registry changes (a kit
 * start/stop), so the collector scrapes exactly the running kits.
 */
interface OtelSyncService {
    /**
     * Re-applies the collector. The K8s API is reached through the workspace kubeconfig; nothing is
     * needed from the control host in state.
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
 * @property contractSource the workspace's cluster contract, the same source
 *   [DefaultObservabilityStackService] reads, so both render the collector from the same capabilities.
 */
class DefaultOtelSyncService(
    private val k8sClientProvider: K8sClientProvider,
    private val k8sService: K8sManifestOperations,
    private val otelManifestBuilder: OtelManifestBuilder,
    private val clusterStateManager: ClusterStateManager,
    private val configChangeReport: ConfigChangeReport,
    private val contractSource: ClusterContractSource,
    private val profileRegion: ProfileRegion,
) : OtelSyncService {
    private val log = KotlinLogging.logger {}

    override fun syncConfigMap(): Result<Unit> =
        runCatching {
            log.debug { "Syncing OTel collector configuration" }
            val clusterState = clusterStateManager.load()
            val contract = contractSource.current()
            val scope = InstallScope.of(clusterState, contract)
            val scrapeConfigs =
                k8sClientProvider.createClient().use { client ->
                    otelManifestBuilder.listWorkloadScrapeConfigs(client)
                }
            // The cluster's export destinations, CNI and region come from its recorded init config,
            // so a regenerated ConfigMap keeps a redirect cluster's external endpoints and a Cilium
            // cluster's Cilium scrape jobs.
            val resources =
                CollectorResources.build(
                    otelManifestBuilder,
                    CollectorInputs(clusterState, clusterState.region(profileRegion), contract.nodeCapabilities, scope),
                    scrapeConfigs,
                )
            configChangeReport.report(resources, scope.namespace.value)
            for (resource in resources) {
                k8sService.applyResource(resource = resource).getOrThrow()
            }
        }
}
