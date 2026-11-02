package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.ContainerObservabilityHttp
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.PrometheusRemoteWrite
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.DefaultMimirQueryService
import com.rustyrazorblade.easydblab.services.TemplateService
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.DockerClientFactory
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration
import java.util.UUID

/**
 * Runs the `metrics stream` catalogue's queries against the pinned Mimir, with series written the
 * way the collector writes them, and checks the values each query gives through the stream's own
 * sample mapping.  Each test writes under its own cluster label, so the tests share one Mimir.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamMetricValuesIntegrationTest : BaseKoinTest() {
    private val s3 = SharedLocalStack.s3Client()
    private val docker = DockerClientFactory.instance().client()
    private val bucket = "stream-it-${UUID.randomUUID().toString().take(8)}"
    private val volume = "stream-${UUID.randomUUID().toString().take(8)}"
    private var mimir: GenericContainer<*>? = null

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(ClusterState(name = "stream-it", versions = mutableMapOf()))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @AfterAll
    fun stop() {
        mimir?.stop()
        runCatching { docker.removeVolumeCmd(volume).exec() }
    }

    private fun mimir(): GenericContainer<*> =
        mimir ?: run {
            SharedLocalStack.createBucketIfMissing(s3, bucket)
            Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
            docker.createVolumeCmd().withName(volume).exec()
            val rendered = MimirManifestBuilder(getKoin().get()).buildConfigMap().data.getValue(MimirManifestBuilder.CONFIG_FILE)
            ObservabilityBackends
                .startMimir(ObservabilityBackends.mimirConfig(rendered), volume, bucket, Constants.Observability.METRICS_ROOT)
                .also { mimir = it }
        }

    @Test
    fun `disk IO sums the whole disks of a host and leaves out partitions and loop devices`() {
        val cluster = newCluster()
        val counter = "system_disk_io_bytes_total"
        writeCounter(cluster, counter, mapOf("host_name" to "db0", "device" to "nvme0n1", "direction" to "read"), delta = 1000.0)
        writeCounter(cluster, counter, mapOf("host_name" to "db0", "device" to "nvme1n1", "direction" to "read"), delta = 2000.0)
        writeCounter(cluster, counter, mapOf("host_name" to "db0", "device" to "nvme0n1p1", "direction" to "read"), delta = 100_000.0)
        writeCounter(cluster, counter, mapOf("host_name" to "db0", "device" to "loop0", "direction" to "read"), delta = 1_000_000.0)

        val samples = await(StreamMetric.NODE_DISK_READ, cluster) { it.isNotEmpty() }
        val oneDisk = reference("sum(rate($counter{device=\"nvme0n1\", cluster=\"$cluster\"}[1m]))", cluster)

        assertThat(samples).hasSize(1)
        assertThat(samples.single().entity).isEqualTo("db0")
        assertThat(samples.single().value).isCloseTo(3 * oneDisk, within(oneDisk * TOLERANCE))
    }

    @Test
    fun `network IO sums the ens and eth interfaces and leaves out lo and Cilium`() {
        val cluster = newCluster()
        val counter = "system_network_io_bytes_total"
        listOf("ens5" to 1000.0, "ens6" to 2000.0, "lo" to 50_000.0, "lxc1a2b" to 70_000.0, "cilium_host" to 9000.0)
            .forEach { (device, delta) ->
                writeCounter(cluster, counter, mapOf("host_name" to "db0", "device" to device, "direction" to "receive"), delta)
            }

        val samples = await(StreamMetric.NODE_NETWORK_RECEIVE, cluster) { it.isNotEmpty() }
        val oneInterface = reference("sum(rate($counter{device=\"ens5\", cluster=\"$cluster\"}[1m]))", cluster)

        assertThat(samples).hasSize(1)
        assertThat(samples.single().value).isCloseTo(3 * oneInterface, within(oneInterface * TOLERANCE))
    }

    @Test
    fun `CPU on a host with several cores is a percentage between 0 and 100`() {
        val cluster = newCluster()
        listOf("cpu0", "cpu1", "cpu2", "cpu3").forEach { core ->
            writeCounter(cluster, CPU, mapOf("host_name" to "db0", "cpu" to core, "state" to "idle"), delta = 27.0)
            writeCounter(cluster, CPU, mapOf("host_name" to "db0", "cpu" to core, "state" to "user"), delta = 18.0)
        }

        val samples = await(StreamMetric.NODE_CPU, cluster) { it.isNotEmpty() }

        assertThat(samples.single().entity).isEqualTo("db0")
        assertThat(samples.single().value).isBetween(0.0, 100.0).isCloseTo(40.0, within(0.5))
    }

    @Test
    fun `filesystem use covers only the data mount, and a host without it gives no sample`() {
        val cluster = newCluster()
        val gauge = "system_filesystem_usage_bytes"
        writeGauge(cluster, gauge, mapOf("host_name" to "db0", "mountpoint" to Constants.K8s.DB_MOUNT_PATH, "state" to "used"), 25.0)
        writeGauge(cluster, gauge, mapOf("host_name" to "db0", "mountpoint" to Constants.K8s.DB_MOUNT_PATH, "state" to "free"), 75.0)
        writeGauge(cluster, gauge, mapOf("host_name" to "db0", "mountpoint" to "/", "state" to "used"), 90.0)
        writeGauge(cluster, gauge, mapOf("host_name" to "db0", "mountpoint" to "/", "state" to "free"), 10.0)
        writeGauge(cluster, gauge, mapOf("host_name" to "app0", "mountpoint" to "/", "state" to "used"), 50.0)
        writeGauge(cluster, gauge, mapOf("host_name" to "app0", "mountpoint" to "/", "state" to "free"), 50.0)

        val samples = await(StreamMetric.NODE_FILESYSTEM_USED, cluster) { it.isNotEmpty() }

        assertThat(samples).containsExactly(Event.Metrics.Sample("node.filesystem_used", "db0", 25.0))
    }

    @Test
    fun `p99 publishes the lowest node above 0 and the highest node, in milliseconds`() {
        val cluster = newCluster()
        writeLatency(cluster, "read", "db0", 2000.0)
        writeLatency(cluster, "read", "db1", 5000.0)
        writeLatency(cluster, "read", "db2", 9000.0)
        writeLatency(cluster, "write", "db0", 0.0)
        writeLatency(cluster, "write", "db1", 2500.0)
        writeLatency(cluster, "write", "db2", 7000.0)

        assertThat(await(StreamMetric.CASSANDRA_READ_P99, cluster) { it.size == 2 })
            .containsExactlyInAnyOrder(
                Event.Metrics.Sample("cassandra.read_p99", "cluster.min", 2.0),
                Event.Metrics.Sample("cassandra.read_p99", "cluster.max", 9.0),
            )
        assertThat(await(StreamMetric.CASSANDRA_WRITE_P99, cluster) { it.size == 2 })
            .containsExactlyInAnyOrder(
                Event.Metrics.Sample("cassandra.write_p99", "cluster.min", 2.5),
                Event.Metrics.Sample("cassandra.write_p99", "cluster.max", 7.0),
            )
    }

    @Test
    fun `when every node reports a p99 of 0 there is no minimum and the maximum is 0`() {
        val cluster = newCluster()
        writeLatency(cluster, "read", "db0", 0.0)
        writeLatency(cluster, "read", "db1", 0.0)

        assertThat(await(StreamMetric.CASSANDRA_READ_P99, cluster) { it.isNotEmpty() })
            .containsExactly(Event.Metrics.Sample("cassandra.read_p99", "cluster.max", 0.0))
    }

    @Test
    fun `a gauge sample older than 30 seconds next to a fresh one counts only the fresh one`() {
        val cluster = newCluster()
        val now = System.currentTimeMillis()
        writeGauge(cluster, "cassandra_compaction_tasks_pending", mapOf("host_name" to "db0"), 7.0, now - STALE_AGE_MS)
        writeGauge(cluster, "cassandra_compaction_tasks_pending", mapOf("host_name" to "db1"), 3.0, now - FRESH_AGE_MS)
        writeLatency(cluster, "read", "db0", 1000.0, now - STALE_AGE_MS)
        writeLatency(cluster, "read", "db1", 4000.0, now - FRESH_AGE_MS)
        writeLatency(cluster, "read", "db2", 6000.0, now - FRESH_AGE_MS)

        assertThat(await(StreamMetric.CASSANDRA_COMPACTION_PENDING, cluster) { it.isNotEmpty() })
            .containsExactly(Event.Metrics.Sample("cassandra.compaction_pending", "cluster", 3.0))
        assertThat(await(StreamMetric.CASSANDRA_READ_P99, cluster) { it.size == 2 })
            .containsExactlyInAnyOrder(
                Event.Metrics.Sample("cassandra.read_p99", "cluster.min", 4.0),
                Event.Metrics.Sample("cassandra.read_p99", "cluster.max", 6.0),
            )
    }

    private fun newCluster(): String = "lab-${UUID.randomUUID().toString().take(8)}"

    private fun writeLatency(
        cluster: String,
        operation: String,
        host: String,
        micros: Double,
        timestampMillis: Long = System.currentTimeMillis() - FRESH_AGE_MS,
    ) = writeGauge(
        cluster,
        "cassandra_client_request_latency_p99_microseconds",
        mapOf("host_name" to host, "cassandra_operation" to operation),
        micros,
        timestampMillis,
    )

    private fun writeGauge(
        cluster: String,
        metric: String,
        labels: Map<String, String>,
        value: Double,
        timestampMillis: Long = System.currentTimeMillis() - FRESH_AGE_MS,
    ) = push(listOf(PrometheusRemoteWrite.Series(seriesLabels(cluster, metric, labels), value, timestampMillis)))

    // Two samples 45 seconds apart, so a 1-minute rate has a slope; every counter of one test uses
    // the same two times, so their rates scale with their deltas alone.
    private fun writeCounter(
        cluster: String,
        metric: String,
        labels: Map<String, String>,
        delta: Double,
    ) {
        val now = System.currentTimeMillis()
        val all = seriesLabels(cluster, metric, labels)
        push(
            listOf(
                PrometheusRemoteWrite.Series(all, COUNTER_START, now - COUNTER_SPAN_MS - FRESH_AGE_MS),
                PrometheusRemoteWrite.Series(all, COUNTER_START + delta, now - FRESH_AGE_MS),
            ),
        )
    }

    private fun seriesLabels(
        cluster: String,
        metric: String,
        labels: Map<String, String>,
    ): Map<String, String> = labels + mapOf("__name__" to metric, "cluster" to cluster)

    private fun push(series: List<PrometheusRemoteWrite.Series>) {
        val response =
            ObservabilityBackends.send(
                HttpRequest
                    .newBuilder(URI("${ObservabilityBackends.baseUrl(mimir(), Constants.K8s.MIMIR_HTTP_PORT)}/api/v1/push"))
                    .header("Content-Type", "application/x-protobuf")
                    .header("Content-Encoding", "snappy")
                    .header("X-Prometheus-Remote-Write-Version", "0.1.0")
                    .header(Constants.Observability.TENANT_HEADER, TENANT)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(PrometheusRemoteWrite.body(series)))
                    .build(),
            )
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
    }

    private fun queries() =
        DefaultMimirQueryService(ContainerObservabilityHttp(TENANT) { port -> ObservabilityBackends.baseUrl(mimir(), port) })

    // A sample is stamped with the host's clock and the query runs at Docker's, so the result is
    // read until it arrives rather than once.
    private fun await(
        metric: StreamMetric,
        cluster: String,
        ready: (List<Event.Metrics.Sample>) -> Boolean,
    ): List<Event.Metrics.Sample> {
        val config =
            RetryConfig
                .custom<List<Event.Metrics.Sample>>()
                .maxAttempts(ANSWER_ATTEMPTS)
                .waitDuration(POLL)
                .retryOnResult { !ready(it) }
                .build()
        return Retry.of("stream-values", config).executeSupplier {
            metric.samples(queries().query(metric.promql(cluster)).getOrThrow())
        }
    }

    private fun reference(
        promql: String,
        cluster: String,
    ): Double =
        queries()
            .query(promql)
            .getOrThrow()
            .single()
            .numericValue()
            ?: error("no value for $promql in $cluster")

    private companion object {
        const val TENANT = "acme"
        const val CPU = "system_cpu_time_seconds_total"
        const val COUNTER_START = 1_000_000.0
        const val COUNTER_SPAN_MS = 45_000L
        const val FRESH_AGE_MS = 5_000L
        const val STALE_AGE_MS = 40_000L
        const val TOLERANCE = 0.001
        const val ANSWER_ATTEMPTS = 15
        val POLL: Duration = Duration.ofSeconds(2)
    }
}
