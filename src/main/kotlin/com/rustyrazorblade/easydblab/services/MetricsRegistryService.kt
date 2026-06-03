package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import io.github.oshai.kotlinlogging.KotlinLogging

interface MetricsRegistryService {
    fun register(
        controlHost: ClusterHost,
        kitName: String,
        port: Int,
        path: String,
    ): Result<Unit>

    fun deregister(
        controlHost: ClusterHost,
        kitName: String,
    ): Result<Unit>
}

class DefaultMetricsRegistryService(
    private val k8sService: K8sService,
    private val otelSyncService: OtelSyncService,
    private val eventBus: EventBus,
) : MetricsRegistryService {
    private val log = KotlinLogging.logger {}

    companion object {
        private const val NAMESPACE = "default"
        private const val WORKLOAD_METRICS_LABEL = "easydblab.com/workload-metrics"
        private const val CONFIG_MAP_PREFIX = "easydblab-metrics-"
    }

    override fun register(
        controlHost: ClusterHost,
        kitName: String,
        port: Int,
        path: String,
    ): Result<Unit> =
        runCatching {
            val configMapName = "$CONFIG_MAP_PREFIX$kitName"
            k8sService
                .createConfigMap(
                    controlHost = controlHost,
                    namespace = NAMESPACE,
                    name = configMapName,
                    data =
                        mapOf(
                            "job-name" to kitName,
                            "port" to port.toString(),
                            "path" to path,
                        ),
                    labels = mapOf(WORKLOAD_METRICS_LABEL to "true"),
                ).getOrThrow()

            otelSyncService.syncConfigMap(controlHost).getOrThrow()
            eventBus.emit(Event.Kit.MetricsRegistered(kit = kitName, port = port))
            println("OTel collector config synced")
        }

    override fun deregister(
        controlHost: ClusterHost,
        kitName: String,
    ): Result<Unit> =
        runCatching {
            val configMapName = "$CONFIG_MAP_PREFIX$kitName"
            k8sService
                .deleteConfigMap(
                    controlHost = controlHost,
                    namespace = NAMESPACE,
                    name = configMapName,
                ).onFailure { e ->
                    log.warn { "Could not delete metrics ConfigMap for $kitName: ${e.message}" }
                }

            otelSyncService.syncConfigMap(controlHost).getOrThrow()
            eventBus.emit(Event.Kit.MetricsDeregistered(kit = kitName))
            println("OTel collector config synced")
        }
}
