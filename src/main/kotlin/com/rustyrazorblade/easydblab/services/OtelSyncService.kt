package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import io.github.oshai.kotlinlogging.KotlinLogging

interface OtelSyncService {
    fun syncConfigMap(controlHost: ClusterHost): Result<Unit>
}

class DefaultOtelSyncService(
    private val k8sClientProvider: K8sClientProvider,
    private val k8sService: K8sService,
    private val otelManifestBuilder: OtelManifestBuilder,
    private val clusterStateManager: ClusterStateManager,
) : OtelSyncService {
    private val log = KotlinLogging.logger {}

    override fun syncConfigMap(controlHost: ClusterHost): Result<Unit> =
        runCatching {
            log.debug { "Syncing OTel collector ConfigMap" }
            // Preserve the cluster's export destinations when the ConfigMap is regenerated on a
            // kit start/stop. Without this, a redirect cluster would silently revert to the
            // in-cluster backends every time the dynamic scrape jobs change.
            val initConfig = clusterStateManager.load().initConfig
            val telemetryRedirect = initConfig?.telemetryRedirect
            // Likewise the CNI: a regenerated ConfigMap must keep the Cilium scrape jobs it had.
            // A cluster with no recorded CNI predates Cilium and runs Flannel.
            val cni = initConfig?.cni ?: CniMode.Flannel
            k8sClientProvider.createClient(controlHost).use { client ->
                val scrapeConfigs = otelManifestBuilder.listWorkloadScrapeConfigs(client)
                val configMap = otelManifestBuilder.buildConfigMap(scrapeConfigs, telemetryRedirect, cni)
                k8sService.applyResource(controlHost = controlHost, resource = configMap).getOrThrow()
            }
            // The configmap is mounted with subPath, so pods don't see live updates.
            // Rolling restart is required for the new scrape config to take effect.
            k8sService
                .rolloutRestartDaemonSet(
                    controlHost = controlHost,
                    name = Constants.OtelCollector.SERVICE_NAME,
                ).getOrThrow()
        }
}
