package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import io.github.oshai.kotlinlogging.KotlinLogging

interface MetricsRegistryService {
    fun register(
        controlHost: ClusterHost,
        kitName: String,
        targets: List<KitMetrics.Scrape>,
    ): Result<Unit>

    fun deregister(
        controlHost: ClusterHost,
        kitName: String,
    ): Result<Unit>
}

/**
 * Manages the per-kit metrics ConfigMaps that drive OTel collector Prometheus scrape jobs.
 *
 * On [register], writes one ConfigMap named `easydblab-metrics-<kitName>-<job>` per scrape target,
 * each carrying [Constants.K8s.WORKLOAD_METRICS_LABEL] and [Constants.K8s.KIT_LABEL] so
 * [OtelSyncService] can regenerate the collector config and [deregister] can bulk-delete
 * all targets for a kit by label selector. Including [kitName] in the ConfigMap name ensures
 * multiple kit instances with the same job name (e.g. postgres-duckdb and postgres-postgis
 * both declaring job "postgres") get separate ConfigMaps and separate OTel scrape jobs.
 */
class DefaultMetricsRegistryService(
    private val k8sService: K8sService,
    private val otelSyncService: OtelSyncService,
    private val eventBus: EventBus,
) : MetricsRegistryService {
    private val log = KotlinLogging.logger {}

    companion object {
        private const val CONFIG_MAP_PREFIX = "easydblab-metrics-"
    }

    override fun register(
        controlHost: ClusterHost,
        kitName: String,
        targets: List<KitMetrics.Scrape>,
    ): Result<Unit> =
        runCatching {
            val effectiveJobNames = targets.map { it.job.ifBlank { kitName } }
            val duplicates =
                effectiveJobNames
                    .groupingBy { it }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
            require(duplicates.isEmpty()) {
                "Kit '$kitName' has duplicate metrics job names: ${duplicates.joinToString()}. Each scrape target must declare a unique job name."
            }

            for (target in targets) {
                val jobName = target.job.ifBlank { kitName }
                val configMapName = "$CONFIG_MAP_PREFIX$kitName-$jobName"
                k8sService
                    .createConfigMap(
                        controlHost = controlHost,
                        namespace = Constants.K8s.NAMESPACE,
                        name = configMapName,
                        data =
                            buildMap {
                                put("kit-name", kitName)
                                put("job-name", jobName)
                                put("port", target.port.toString())
                                put("path", target.path)
                                if (target.username.isNotBlank()) put("username", target.username)
                                if (target.podSelector.isNotBlank()) put("pod-selector", target.podSelector)
                            },
                        labels =
                            mapOf(
                                Constants.K8s.WORKLOAD_METRICS_LABEL to "true",
                                Constants.K8s.KIT_LABEL to kitName,
                            ),
                    ).getOrThrow()
            }

            otelSyncService.syncConfigMap(controlHost).getOrThrow()
            eventBus.emit(Event.Kit.MetricsRegistered(kit = kitName, ports = targets.map { it.port }))
        }.onFailure {
            deregister(controlHost, kitName)
                .onFailure { e -> log.warn(e) { "Cleanup deregister also failed for $kitName" } }
        }

    override fun deregister(
        controlHost: ClusterHost,
        kitName: String,
    ): Result<Unit> =
        runCatching {
            k8sService
                .deleteConfigMapsByLabels(
                    controlHost = controlHost,
                    namespace = Constants.K8s.NAMESPACE,
                    labels =
                        mapOf(
                            Constants.K8s.WORKLOAD_METRICS_LABEL to "true",
                            Constants.K8s.KIT_LABEL to kitName,
                        ),
                ).onFailure { e ->
                    log.warn { "Could not delete metrics ConfigMaps for $kitName: ${e.message}" }
                }

            otelSyncService.syncConfigMap(controlHost).getOrThrow()
            eventBus.emit(Event.Kit.MetricsDeregistered(kit = kitName))
        }
}
