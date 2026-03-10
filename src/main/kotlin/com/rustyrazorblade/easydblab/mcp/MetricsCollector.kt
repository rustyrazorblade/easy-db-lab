package com.rustyrazorblade.easydblab.mcp

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.getControlHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.PromQueryResult
import com.rustyrazorblade.easydblab.services.VictoriaMetricsQueryService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Timer
import kotlin.concurrent.fixedRateTimer

private val log = KotlinLogging.logger {}

/**
 * Collects metrics from VictoriaMetrics and emits them as events on the EventBus.
 *
 * Runs a daemon timer at 5-second intervals. Queries are the same PromQL expressions
 * used by the Grafana dashboards, ensuring consistency between the live stream and
 * dashboard views.
 *
 * System metrics are always collected. Cassandra metrics are only collected when the
 * cluster is running Cassandra. Each category is independent — a failed Cassandra query
 * does not prevent system metrics from being emitted.
 */
class MetricsCollector(
    private val queryService: VictoriaMetricsQueryService,
    private val clusterStateManager: ClusterStateManager,
    private val eventBus: EventBus,
) {
    private var timer: Timer? = null

    fun start() {
        if (timer != null) {
            log.warn { "MetricsCollector already started" }
            return
        }

        log.info { "Starting MetricsCollector with ${Constants.Victoria.METRICS_COLLECTION_INTERVAL_SECONDS}s interval" }
        timer =
            fixedRateTimer(
                name = "metrics-collector",
                daemon = true,
                initialDelay = Constants.Victoria.METRICS_COLLECTION_INTERVAL_SECONDS * Constants.Time.MILLIS_PER_SECOND,
                period = Constants.Victoria.METRICS_COLLECTION_INTERVAL_SECONDS * Constants.Time.MILLIS_PER_SECOND,
            ) {
                collect()
            }
    }

    fun stop() {
        timer?.cancel()
        timer = null
        log.info { "MetricsCollector stopped" }
    }

    private fun collect() {
        try {
            val clusterState = clusterStateManager.load()
            val controlHost = clusterState.getControlHost() ?: return
            val dbHosts = clusterState.hosts[ServerType.Cassandra]
            if (dbHosts.isNullOrEmpty()) return

            collectSystemMetrics(controlHost)

            // Only collect Cassandra metrics if the cluster is running Cassandra (not ClickHouse)
            if (clusterState.clickHouseConfig == null) {
                collectCassandraMetrics(controlHost)
            }
        } catch (e: Exception) {
            log.debug { "Metrics collection cycle failed: ${e.message}" }
        }
    }

    private fun collectSystemMetrics(controlHost: ClusterHost) {
        try {
            val cpuResults = queryService.query(controlHost, SYSTEM_CPU_QUERY).getOrNull()
            val memResults = queryService.query(controlHost, SYSTEM_MEMORY_QUERY).getOrNull()
            val diskReadResults = queryService.query(controlHost, SYSTEM_DISK_READ_QUERY).getOrNull()
            val diskWriteResults = queryService.query(controlHost, SYSTEM_DISK_WRITE_QUERY).getOrNull()
            val fsResults = queryService.query(controlHost, SYSTEM_FILESYSTEM_QUERY).getOrNull()

            if (cpuResults.isNullOrEmpty() && memResults.isNullOrEmpty()) {
                return // No system metrics available yet
            }

            val cpuByHost = indexByHost(cpuResults)
            val memByHost = indexByHost(memResults)
            val diskReadByHost = indexByHost(diskReadResults)
            val diskWriteByHost = indexByHost(diskWriteResults)
            val fsByHost = indexByHost(fsResults)

            val hostNames = (cpuByHost.keys + memByHost.keys + diskReadByHost.keys + diskWriteByHost.keys + fsByHost.keys)
            if (hostNames.isEmpty()) return

            val nodes =
                hostNames.associateWith { hostName ->
                    Event.Metrics.Node(
                        cpuUsagePct = cpuByHost[hostName] ?: 0.0,
                        memoryUsedBytes = memByHost[hostName]?.toLong() ?: 0L,
                        diskReadBytesPerSec = diskReadByHost[hostName] ?: 0.0,
                        diskWriteBytesPerSec = diskWriteByHost[hostName] ?: 0.0,
                        filesystemUsedPct = fsByHost[hostName] ?: 0.0,
                    )
                }

            if (nodes.isNotEmpty()) {
                eventBus.emit(Event.Metrics.System(nodes))
            }
        } catch (e: Exception) {
            log.debug { "System metrics collection failed: ${e.message}" }
        }
    }

    private fun collectCassandraMetrics(controlHost: ClusterHost) {
        try {
            val readP99 = querySingleValue(controlHost, CASSANDRA_READ_P99_QUERY)
            val writeP99 = querySingleValue(controlHost, CASSANDRA_WRITE_P99_QUERY)
            val readOps = querySingleValue(controlHost, CASSANDRA_READ_OPS_QUERY)
            val writeOps = querySingleValue(controlHost, CASSANDRA_WRITE_OPS_QUERY)
            val compactionPending = querySingleValue(controlHost, CASSANDRA_COMPACTION_PENDING_QUERY)
            val compactionCompleted = querySingleValue(controlHost, CASSANDRA_COMPACTION_COMPLETED_QUERY)
            val compactionBytes = querySingleValue(controlHost, CASSANDRA_COMPACTION_BYTES_QUERY)

            val noCassandraMetrics = readP99 == null && writeP99 == null && readOps == null && writeOps == null
            if (noCassandraMetrics) {
                return
            }

            eventBus.emit(
                Event.Metrics.Cassandra(
                    readP99Ms = readP99 ?: 0.0,
                    writeP99Ms = writeP99 ?: 0.0,
                    readOpsPerSec = readOps ?: 0.0,
                    writeOpsPerSec = writeOps ?: 0.0,
                    compactionPending = compactionPending?.toLong() ?: 0L,
                    compactionCompletedPerSec = compactionCompleted ?: 0.0,
                    compactionBytesWrittenPerSec = compactionBytes ?: 0.0,
                ),
            )
        } catch (e: Exception) {
            log.debug { "Cassandra metrics collection failed: ${e.message}" }
        }
    }

    private fun querySingleValue(
        controlHost: ClusterHost,
        promql: String,
    ): Double? =
        queryService
            .query(controlHost, promql)
            .getOrNull()
            ?.firstOrNull()
            ?.numericValue()

    private fun indexByHost(results: List<PromQueryResult>?): Map<String, Double> =
        results
            ?.mapNotNull { result ->
                val host = result.metric["host_name"] ?: return@mapNotNull null
                val value = result.numericValue() ?: return@mapNotNull null
                host to value
            }?.toMap() ?: emptyMap()

    companion object {
        // System queries — match Grafana system-overview.json dashboard
        const val SYSTEM_CPU_QUERY =
            """100 - (avg by(host_name) (rate(system_cpu_time_seconds_total{state="idle"}[1m])) * 100)"""

        const val SYSTEM_MEMORY_QUERY =
            """system_memory_usage_bytes{state="used"}"""

        const val SYSTEM_DISK_READ_QUERY =
            """rate(system_disk_io_bytes_total{direction="read"}[1m])"""

        const val SYSTEM_DISK_WRITE_QUERY =
            """rate(system_disk_io_bytes_total{direction="write"}[1m])"""

        const val SYSTEM_FILESYSTEM_QUERY =
            """100 * system_filesystem_usage_bytes{state="used"} / (system_filesystem_usage_bytes{state="used"} + system_filesystem_usage_bytes{state="free"})"""

        // Cassandra queries — match Grafana cassandra-condensed.json dashboard
        @Suppress("ktlint:standard:max-line-length")
        const val CASSANDRA_READ_P99_QUERY =
            """histogram_quantile(0.99, sum(rate({__name__=~"org_apache_cassandra_metrics_client_request_latency_read_.+_bucket"}[1m])) by (le))"""

        @Suppress("ktlint:standard:max-line-length")
        const val CASSANDRA_WRITE_P99_QUERY =
            """histogram_quantile(0.99, sum(rate({__name__=~"org_apache_cassandra_metrics_client_request_latency_write_.+_bucket"}[1m])) by (le))"""

        @Suppress("ktlint:standard:max-line-length")
        const val CASSANDRA_READ_OPS_QUERY =
            """sum(irate({__name__=~"org_apache_cassandra_metrics_client_request_latency_read_.+_count"}[1m]))"""

        @Suppress("ktlint:standard:max-line-length")
        const val CASSANDRA_WRITE_OPS_QUERY =
            """sum(irate({__name__=~"org_apache_cassandra_metrics_client_request_latency_write_.+_count"}[1m]))"""

        const val CASSANDRA_COMPACTION_PENDING_QUERY =
            """sum(org_apache_cassandra_metrics_table_pending_compactions)"""

        @Suppress("ktlint:standard:max-line-length")
        const val CASSANDRA_COMPACTION_COMPLETED_QUERY =
            """sum(irate(org_apache_cassandra_metrics_compaction_completed_tasks[1m]))"""

        @Suppress("ktlint:standard:max-line-length")
        const val CASSANDRA_COMPACTION_BYTES_QUERY =
            """sum(irate(org_apache_cassandra_metrics_table_compaction_bytes_written[1m]))"""
    }
}
