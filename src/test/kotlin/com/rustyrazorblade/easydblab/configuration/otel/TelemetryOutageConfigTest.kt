package com.rustyrazorblade.easydblab.configuration.otel

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.YamlTestSupport.keysAt
import com.rustyrazorblade.easydblab.YamlTestSupport.nodeAt
import com.rustyrazorblade.easydblab.YamlTestSupport.scalarAt
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.Quantity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Issue 991: every telemetry sender holds what it cannot send in memory and retries without a
 * limit, so a backend or collector restart drops no metric, log or trace. These are the sender
 * settings that make that true: the cluster collector's exporters, the EMR and stress-sidecar
 * collectors and Fluent Bit. The end-to-end replay is `CollectorReplayIntegrationTest`.
 */
class TelemetryOutageConfigTest : BaseKoinTest() {
    private lateinit var builder: OtelManifestBuilder

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<ClusterStateManager>() }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        whenever(getKoin().get<ClusterStateManager>().load()).thenReturn(ClusterState(name = "test"))
        builder = OtelManifestBuilder(getKoin().get())
    }

    private fun yamlFrom(configMap: ConfigMap): String = checkNotNull(configMap.data["otel-collector-config.yaml"])

    /**
     * The EMR and stress-sidecar collectors send to a cluster collector that restarts on every kit
     * start and stop, so each holds what it cannot send in memory and retries without a limit.
     */
    @Test
    fun `the EMR and stress-sidecar exporters queue in memory and retry without a limit`() {
        for ((resource, exporter, bytes) in listOf(
            Triple("/com/rustyrazorblade/easydblab/configuration/emr/otel-collector-config.yaml", "otlp_grpc/control", QUEUE_BYTES),
            Triple(
                "/com/rustyrazorblade/easydblab/configuration/cassandra/otel-stress-sidecar-config.yaml",
                "otlp_grpc",
                SIDECAR_QUEUE_BYTES,
            ),
        )) {
            val yaml = checkNotNull(javaClass.getResource(resource)).readText()
            val path = arrayOf("exporters", exporter)
            assertThat(scalarAt(yaml, *path, "retry_on_failure", "enabled")).describedAs(resource).isEqualTo("true")
            assertThat(scalarAt(yaml, *path, "retry_on_failure", "max_elapsed_time")).describedAs(resource).isEqualTo("0")
            assertThat(scalarAt(yaml, *path, "sending_queue", "enabled")).describedAs(resource).isEqualTo("true")
            assertThat(scalarAt(yaml, *path, "sending_queue", "sizer")).describedAs(resource).isEqualTo("bytes")
            assertThat(scalarAt(yaml, *path, "sending_queue", "queue_size")).describedAs(resource).isEqualTo("$bytes")
            assertThat(keysAt(yaml, *path, "sending_queue")).describedAs(resource).doesNotContain("storage", "block_on_overflow")
        }
    }

    /**
     * Fluent Bit sends the journal to the same node collector. Its default is one retry, so a
     * collector roll would drop journal chunks; it retries without a limit and buffers in memory.
     */
    @Test
    fun `the journald sender retries without a limit and buffers in memory`() {
        val yaml =
            checkNotNull(javaClass.getResource("/com/rustyrazorblade/easydblab/configuration/otel/fluent-bit-journald.yaml"))
                .readText()
        val output = (nodeAt(yaml, "pipeline", "outputs") as YamlList).items.single() as YamlMap

        assertThat(output.get<YamlScalar>("retry_limit")?.content).isEqualTo("no_limits")
        assertThat(scalarAt(yaml, "service", "scheduler.cap")).isEqualTo("30")
        assertThat(keysAt(yaml, "service")).noneMatch { it.startsWith("storage.") }
        assertThat(output.entries.keys.map { it.content }).noneMatch { it.startsWith("storage.") }
    }

    /**
     * Once `http` is set on the remote write exporter, the collector ignores the deprecated
     * top-level endpoint, tls and headers. A setting left at the top level would silently not
     * apply, so none may remain there.
     */
    @Test
    fun `remote write sets its connection only under http, locally and under redirect`() {
        val local = yamlFrom(builder.buildConfigMap(emptyList(), telemetryRedirect = null))
        val redirected = yamlFrom(builder.buildConfigMap(emptyList(), TelemetryRedirect.fromBaseHost("10.0.0.9")))

        for (yaml in listOf(local, redirected)) {
            assertThat(keysAt(yaml, "exporters", "prometheus_remote_write")).doesNotContain("endpoint", "tls", "headers")
            assertThat(keysAt(yaml, "exporters", "prometheus_remote_write", "http")).contains("endpoint", "tls", "headers")
        }
        assertThat(scalarAt(redirected, "exporters", "prometheus_remote_write", "http", "endpoint")).contains("10.0.0.9")
    }

    /**
     * A backend outage must not drop telemetry. Remote write has no whole-push deadline (its 5s
     * default dropped every batch while Mimir was down) and each exporter retries without a limit
     * into an in-memory queue bounded by bytes (issue 1002).
     */
    @Test
    fun `every backend exporter queues in memory and retries without a limit, locally and under redirect`() {
        val local = yamlFrom(builder.buildConfigMap(emptyList(), telemetryRedirect = null))
        val redirected = yamlFrom(builder.buildConfigMap(emptyList(), TelemetryRedirect.fromBaseHost("10.0.0.9")))
        assertQueuesAndRetriesWithoutLimit(local)
        assertQueuesAndRetriesWithoutLimit(redirected)
    }

    private fun assertQueuesAndRetriesWithoutLimit(yaml: String) {
        val prw = arrayOf("exporters", "prometheus_remote_write")

        assertThat(scalarAt(yaml, *prw, "timeout")).isEqualTo("0")
        assertThat(scalarAt(yaml, *prw, "http", "timeout")).isEqualTo("30s")
        assertThat(scalarAt(yaml, *prw, "retry_on_failure", "initial_interval")).isEqualTo("5s")
        assertThat(keysAt(yaml, *prw, "retry_on_failure")).doesNotContain("max_interval")
        assertThat(keysAt(yaml, *prw)).doesNotContain("sending_queue")

        val queues =
            listOf(
                QueueBound(listOf(*prw, "remote_write_queue"), sizer = null, size = PRW_QUEUE_BATCHES),
                QueueBound(listOf("exporters", "otlp_http/logs", "sending_queue"), sizer = "bytes", size = QUEUE_BYTES),
                QueueBound(listOf("exporters", "otlp_grpc/tempo", "sending_queue"), sizer = "bytes", size = QUEUE_BYTES),
            )
        for ((queue, sizer, size) in queues) {
            val name = queue.joinToString("/")
            val path = queue.toTypedArray()
            assertThat(scalarAt(yaml, *path, "enabled")).describedAs(name).isEqualTo("true")
            assertThat(scalarAt(yaml, *path, "queue_size")).describedAs(name).isEqualTo("$size")
            sizer?.let { assertThat(scalarAt(yaml, *path, "sizer")).describedAs(name).isEqualTo(it) }
            assertThat(keysAt(yaml, *path)).describedAs("no on-disk queue").doesNotContain("storage")
        }
        for (exporter in listOf("prometheus_remote_write", "otlp_http/logs", "otlp_grpc/tempo")) {
            assertThat(scalarAt(yaml, "exporters", exporter, "retry_on_failure", "enabled")).describedAs(exporter).isEqualTo("true")
            assertThat(scalarAt(yaml, "exporters", exporter, "retry_on_failure", "max_elapsed_time")).describedAs(exporter).isEqualTo("0")
        }
        assertThat(keysAt(yaml, *prw)).describedAs("no remote-write WAL").doesNotContain("wal")
        val components = keysAt(yaml, "processors") + keysAt(yaml, "extensions")
        assertThat(components)
            .describedAs("no memory limiter or on-disk storage, under any name")
            .noneMatch { it.startsWith("memory_limiter") || it.startsWith("file_storage") }
    }

    /**
     * Issue 1002: a count-bounded queue let one Tempo backlog grow the app node's collector to
     * 12.2 GB until the kernel killed it, losing every queue. The queues are bounded so that all of
     * them, full, fit inside the collector's memory limit, and the limit fits the smallest default
     * node.
     *
     * The bound rests on a measurement, not a model: on 0.161.0, with every backend down and every
     * queue at the sizes below, the collector held [MEASURED_FULL_QUEUES_RSS] resident under its
     * limit and GOMEMLIMIT. A queue raised past what was measured fails here until it is measured
     * again. The remote write queue counts batches, so it is a size bound only while each batch is
     * capped at [BATCH_ITEMS].
     */
    @Test
    fun `the node collector's full queues fit inside its memory limit, which fits the smallest default node`() {
        val yaml = yamlFrom(builder.buildConfigMap(emptyList(), telemetryRedirect = null))
        val container =
            builder
                .buildDaemonSet()
                .spec.template.spec.containers
                .single()

        assertThat(longAt(yaml, "processors", "batch", "send_batch_max_size")).isLessThanOrEqualTo(BATCH_ITEMS)
        for (exporter in listOf("otlp_http/logs", "otlp_grpc/tempo")) {
            assertThat(longAt(yaml, "exporters", exporter, "sending_queue", "queue_size"))
                .describedAs(exporter)
                .isLessThanOrEqualTo(QUEUE_BYTES)
        }
        assertThat(longAt(yaml, "exporters", "prometheus_remote_write", "remote_write_queue", "queue_size"))
            .isLessThanOrEqualTo(PRW_QUEUE_BATCHES)

        val limit = Quantity.getAmountInBytes(container.resources.limits.getValue("memory")).toLong()
        val goMemLimit = Quantity.getAmountInBytes(Quantity(OtelManifestBuilder.GO_MEM_LIMIT.removeSuffix("B"))).toLong()
        assertThat(container.env.single { it.name == "GOMEMLIMIT" }.value).isEqualTo(OtelManifestBuilder.GO_MEM_LIMIT)
        assertThat(container.resources.limits.keys).containsExactly("memory")
        assertThat(goMemLimit).isLessThan(limit)
        assertThat(MEASURED_FULL_QUEUES_RSS * HEADROOM_PERCENT / 100).isLessThanOrEqualTo(limit)
        assertThat(limit).isLessThanOrEqualTo(SMALLEST_DEFAULT_NODE_BYTES / 4)
    }

    /** The whole number at [path] in [yaml]; fails naming the path when it is missing or not a number. */
    private fun longAt(
        yaml: String,
        vararg path: String,
    ): Long =
        checkNotNull(scalarAt(yaml, *path)?.toLongOrNull()) {
            "no whole number at ${path.joinToString("/")}"
        }

    private data class QueueBound(
        val path: List<String>,
        val sizer: String?,
        val size: Long,
    )

    private companion object {
        /** Each of the node collector's byte-bounded queues, and the EMR collector's: 256 MiB. */
        const val QUEUE_BYTES = 268_435_456L

        /** The stress sidecar's queue: 64 MiB, since it shares the app node with the stress job. */
        const val SIDECAR_QUEUE_BYTES = 67_108_864L

        /** The remote write queue, in batches: its 0.161.0 factory fixes the requests sizer. */
        const val PRW_QUEUE_BATCHES = 500L

        /** The batch processor's cap on items per batch. */
        const val BATCH_ITEMS = 1024L

        /** Peak resident memory measured on 0.161.0 with every queue above full: 3.22 GB. */
        const val MEASURED_FULL_QUEUES_RSS = 3_221_630_976L

        /** The limit must leave a quarter on top of the measured peak, for the collector itself. */
        const val HEADROOM_PERCENT = 125L

        /** The smallest default node the collector runs on: 16 GiB (m8id.xlarge control, c8id.2xlarge app). */
        const val SMALLEST_DEFAULT_NODE_BYTES = 16L * 1024 * 1024 * 1024
    }
}
