package com.rustyrazorblade.easydblab.mcp

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.MimirQueryService
import com.rustyrazorblade.easydblab.services.PromQueryResult
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Timer
import kotlin.concurrent.fixedRateTimer

private val log = KotlinLogging.logger {}

/**
 * Collects metrics from Mimir and emits them as events on the EventBus.
 *
 * Runs a daemon timer at 5-second intervals. Queries are the PromQL expressions the Grafana
 * dashboards use, scoped to the current cluster ([MetricsQueries]), so the live stream and the
 * dashboards agree and another cluster's series in the same tenant never leak in.
 *
 * System metrics are always collected. Cassandra metrics are only collected when the
 * cluster is running Cassandra. Each category is independent — a failed Cassandra query
 * does not prevent system metrics from being emitted.
 */
class MetricsCollector(
    private val queryService: MimirQueryService,
    private val clusterStateManager: ClusterStateManager,
    private val eventBus: EventBus,
) {
    private var timer: Timer? = null

    @Synchronized
    fun start() {
        if (timer != null) {
            log.warn { "MetricsCollector already started" }
            return
        }

        val intervalMillis = Constants.LiveMetrics.COLLECTION_INTERVAL_SECONDS * Constants.Time.MILLIS_PER_SECOND
        log.info { "Starting MetricsCollector with ${Constants.LiveMetrics.COLLECTION_INTERVAL_SECONDS}s interval" }
        timer =
            fixedRateTimer(
                name = "metrics-collector",
                daemon = true,
                initialDelay = intervalMillis,
                period = intervalMillis,
            ) {
                collect()
            }
    }

    @Synchronized
    fun stop() {
        timer?.cancel()
        timer = null
        log.info { "MetricsCollector stopped" }
    }

    internal fun collect() {
        try {
            val clusterState = clusterStateManager.load()
            if (clusterState.getControlHost() == null) return
            val queries = MetricsQueries.forCluster(clusterState.clusterLabelName())

            collectSystemMetrics(queries)

            if (clusterState.isRunningCassandra()) {
                collectCassandraMetrics(queries)
            }
        } catch (e: Exception) {
            log.debug { "Metrics collection cycle failed: ${e.message}" }
        }
    }

    private fun collectSystemMetrics(queries: MetricsQueries) {
        try {
            val cpuResults = queryService.query(queries.systemCpu).getOrNull()
            val memResults = queryService.query(queries.systemMemory).getOrNull()
            val diskReadResults = queryService.query(queries.systemDiskRead).getOrNull()
            val diskWriteResults = queryService.query(queries.systemDiskWrite).getOrNull()
            val fsResults = queryService.query(queries.systemFilesystem).getOrNull()

            if (cpuResults.isNullOrEmpty() && memResults.isNullOrEmpty()) {
                return // No system metrics available yet
            }

            val cpuByHost = indexByHost(cpuResults)
            val memByHost = indexByHost(memResults)
            val diskReadByHost = indexByHost(diskReadResults)
            val diskWriteByHost = indexByHost(diskWriteResults)
            val fsByHost = indexByHost(fsResults)

            val hostNames = (cpuByHost.keys + memByHost.keys + diskReadByHost.keys + diskWriteByHost.keys + fsByHost.keys).toSet()
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

    private fun collectCassandraMetrics(queries: MetricsQueries) {
        try {
            val readP99 = querySingleValue(queries.cassandraReadP99)
            val writeP99 = querySingleValue(queries.cassandraWriteP99)
            val readOps = querySingleValue(queries.cassandraReadOps)
            val writeOps = querySingleValue(queries.cassandraWriteOps)
            val compactionPending = querySingleValue(queries.cassandraCompactionPending)
            val compactionCompleted = querySingleValue(queries.cassandraCompactionCompleted)
            val compactionBytes = querySingleValue(queries.cassandraCompactionBytes)

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

    private fun querySingleValue(promql: String): Double? =
        queryService
            .query(promql)
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
}

/**
 * The PromQL the live metrics stream runs for one cluster: the expressions of the Grafana
 * system-overview and cassandra-overview dashboards, with every selector narrowed to
 * `cluster="<name>-<id>"`.
 */
@Suppress("LongParameterList")
data class MetricsQueries(
    val systemCpu: String,
    val systemMemory: String,
    val systemDiskRead: String,
    val systemDiskWrite: String,
    val systemFilesystem: String,
    val cassandraReadP99: String,
    val cassandraWriteP99: String,
    val cassandraReadOps: String,
    val cassandraWriteOps: String,
    val cassandraCompactionPending: String,
    val cassandraCompactionCompleted: String,
    val cassandraCompactionBytes: String,
) {
    /** The five host-level queries. */
    fun system(): List<String> = listOf(systemCpu, systemMemory, systemDiskRead, systemDiskWrite, systemFilesystem)

    /** The seven Cassandra queries. */
    fun cassandra(): List<String> =
        listOf(
            cassandraReadP99,
            cassandraWriteP99,
            cassandraReadOps,
            cassandraWriteOps,
            cassandraCompactionPending,
            cassandraCompactionCompleted,
            cassandraCompactionBytes,
        )

    companion object {
        /** The queries for the cluster labelled [cluster] (`<name>-<id>`). */
        @Suppress("ktlint:standard:max-line-length", "MaxLineLength")
        fun forCluster(cluster: String): MetricsQueries {
            val c = """cluster="$cluster""""
            val readLatency = """{__name__=~"org_apache_cassandra_metrics_client_request_latency_read_.+_bucket", $c}"""
            val writeLatency = """{__name__=~"org_apache_cassandra_metrics_client_request_latency_write_.+_bucket", $c}"""
            val readCount = """{__name__=~"org_apache_cassandra_metrics_client_request_latency_read_.+_count", $c}"""
            val writeCount = """{__name__=~"org_apache_cassandra_metrics_client_request_latency_write_.+_count", $c}"""
            return MetricsQueries(
                systemCpu = """100 - (avg by(host_name) (rate(system_cpu_time_seconds_total{state="idle", $c}[1m])) * 100)""",
                systemMemory = """system_memory_usage_bytes{state="used", $c}""",
                systemDiskRead = """rate(system_disk_io_bytes_total{direction="read", $c}[1m])""",
                systemDiskWrite = """rate(system_disk_io_bytes_total{direction="write", $c}[1m])""",
                systemFilesystem =
                    """100 * system_filesystem_usage_bytes{state="used", $c} / (system_filesystem_usage_bytes{state="used", $c} + system_filesystem_usage_bytes{state="free", $c})""",
                cassandraReadP99 = """histogram_quantile(0.99, sum(rate($readLatency[1m])) by (le))""",
                cassandraWriteP99 = """histogram_quantile(0.99, sum(rate($writeLatency[1m])) by (le))""",
                cassandraReadOps = """sum(irate($readCount[1m]))""",
                cassandraWriteOps = """sum(irate($writeCount[1m]))""",
                cassandraCompactionPending = """sum(org_apache_cassandra_metrics_table_pending_compactions{$c})""",
                cassandraCompactionCompleted = """sum(irate(org_apache_cassandra_metrics_compaction_completed_tasks{$c}[1m]))""",
                cassandraCompactionBytes = """sum(irate(org_apache_cassandra_metrics_table_compaction_bytes_written{$c}[1m]))""",
            )
        }
    }
}
