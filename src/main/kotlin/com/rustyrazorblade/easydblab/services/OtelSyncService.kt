package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.github.oshai.kotlinlogging.KotlinLogging

/** What a collector sync did. */
enum class CollectorSync {
    /** The collector's config was regenerated and applied; it rolls when its config hash changed. */
    Applied,

    /** No observability stack is deployed: nothing was applied, and the registry applies at its deploy. */
    Deferred,
}

/**
 * Regenerates the OTel collector's configuration when the metrics registry changes (a kit
 * start/stop), so the collector scrapes exactly the running kits.
 */
interface OtelSyncService {
    /**
     * Re-applies the collector's configuration where the observability stack is deployed, and applies
     * nothing where it is not. The K8s API is reached through the workspace kubeconfig; nothing is
     * needed from the control host in state.
     */
    fun syncConfigMap(): Result<CollectorSync>
}

/**
 * Re-applies the collector's ConfigMap and DaemonSet, built and hashed by [CollectorResources], the
 * same function the observability stack deploy uses, so both stamp the same config hash.
 *
 * Only those two: a scrape job changes the ConfigMap, and the DaemonSet carries the ConfigMap's hash
 * on its template. The ConfigMap is mounted with subPath, so running pods never see it change; the
 * collector rolls because that hash changes, not because of a forced restart (which would leave the
 * old hash behind for the next deploy to roll again). The ServiceAccount, the cluster-wide RBAC and the
 * Service depend on nothing a kit changes, so they stay the deploy's, and a kit start never creates or
 * changes them.
 *
 * Where the stack is not deployed ([CollectorPresence]) it applies nothing and returns
 * [CollectorSync.Deferred]: the registry ConfigMaps are already written, and the deploy (`up`,
 * `grafana update-config`) reads them all when it builds the collector.
 *
 * @property inputs the same reading of state, contract and region the stack deploy takes, so both render
 *   the collector from the same capabilities.
 */
class DefaultOtelSyncService(
    private val k8sClientProvider: K8sClientProvider,
    private val k8sService: K8sManifestOperations,
    private val otelManifestBuilder: OtelManifestBuilder,
    private val inputs: ObservabilityDeployInputs,
    private val configChangeReport: ConfigChangeReport,
    private val presence: CollectorPresence,
) : OtelSyncService {
    private val log = KotlinLogging.logger {}

    override fun syncConfigMap(): Result<CollectorSync> =
        runCatching {
            log.debug { "Syncing OTel collector configuration" }
            val reading = inputs.read()
            if (!presence.isDeployed(reading.scope)) return@runCatching CollectorSync.Deferred
            val scrapeConfigs =
                k8sClientProvider.createClient().use { client ->
                    otelManifestBuilder.listWorkloadScrapeConfigs(client, reading.scope)
                }
            // The cluster's export destinations, CNI and region come from its recorded init config,
            // so a regenerated ConfigMap keeps a redirect cluster's external endpoints and a Cilium
            // cluster's Cilium scrape jobs.
            val resources =
                CollectorResources
                    .build(
                        otelManifestBuilder,
                        CollectorInputs(reading.state, reading.region, reading.contract.nodeCapabilities, reading.scope),
                        scrapeConfigs,
                    ).filter { it is ConfigMap || it is DaemonSet }
            configChangeReport.report(resources, reading.scope.namespace.value)
            for (resource in resources) {
                k8sService.applyResource(resource = resource).getOrThrow()
            }
            CollectorSync.Applied
        }
}
