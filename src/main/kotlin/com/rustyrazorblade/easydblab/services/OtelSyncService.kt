package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import io.github.oshai.kotlinlogging.KotlinLogging

interface OtelSyncService {
    fun syncConfigMap(controlHost: ClusterHost): Result<Unit>
}

class DefaultOtelSyncService(
    private val k8sClientProvider: K8sClientProvider,
    private val k8sService: K8sService,
    private val otelManifestBuilder: OtelManifestBuilder,
) : OtelSyncService {
    private val log = KotlinLogging.logger {}

    override fun syncConfigMap(controlHost: ClusterHost): Result<Unit> =
        runCatching {
            log.debug { "Syncing OTel collector ConfigMap" }
            k8sClientProvider.createClient(controlHost).use { client ->
                val scrapeConfigs = otelManifestBuilder.listWorkloadScrapeConfigs(client)
                val configMap = otelManifestBuilder.buildConfigMap(scrapeConfigs)
                k8sService.applyResource(controlHost = controlHost, resource = configMap).getOrThrow()
            }
        }
}
