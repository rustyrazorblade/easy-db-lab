package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import io.github.oshai.kotlinlogging.KotlinLogging

interface MetricsRegistryService {
    fun register(
        controlHost: ClusterHost,
        workloadName: String,
        port: Int,
        path: String,
    ): Result<Unit>

    fun deregister(
        controlHost: ClusterHost,
        workloadName: String,
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
        workloadName: String,
        port: Int,
        path: String,
    ): Result<Unit> =
        runCatching {
            val configMapName = "$CONFIG_MAP_PREFIX$workloadName"
            k8sService
                .createConfigMap(
                    controlHost = controlHost,
                    namespace = NAMESPACE,
                    name = configMapName,
                    data =
                        mapOf(
                            "job-name" to workloadName,
                            "port" to port.toString(),
                            "path" to path,
                        ),
                    labels = mapOf(WORKLOAD_METRICS_LABEL to "true"),
                ).getOrThrow()

            eventBus.emit(Event.Workload.MetricsRegistered(workload = workloadName, port = port))
            otelSyncService.syncConfigMap(controlHost).getOrThrow()
            eventBus.emit(Event.Workload.OtelSynced)
        }

    override fun deregister(
        controlHost: ClusterHost,
        workloadName: String,
    ): Result<Unit> =
        runCatching {
            val configMapName = "$CONFIG_MAP_PREFIX$workloadName"
            k8sService
                .deleteConfigMap(
                    controlHost = controlHost,
                    namespace = NAMESPACE,
                    name = configMapName,
                ).onFailure { e ->
                    log.warn { "Could not delete metrics ConfigMap for $workloadName: ${e.message}" }
                }

            eventBus.emit(Event.Workload.MetricsDeregistered(workload = workloadName))
            otelSyncService.syncConfigMap(controlHost).getOrThrow()
            eventBus.emit(Event.Workload.OtelSynced)
        }
}
