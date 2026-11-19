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
        for ((resource, exporter) in listOf(
            "/com/rustyrazorblade/easydblab/configuration/emr/otel-collector-config.yaml" to "otlp_grpc/control",
            "/com/rustyrazorblade/easydblab/configuration/cassandra/otel-stress-sidecar-config.yaml" to "otlp_grpc",
        )) {
            val yaml = checkNotNull(javaClass.getResource(resource)).readText()
            val path = arrayOf("exporters", exporter)
            assertThat(scalarAt(yaml, *path, "retry_on_failure", "enabled")).describedAs(resource).isEqualTo("true")
            assertThat(scalarAt(yaml, *path, "retry_on_failure", "max_elapsed_time")).describedAs(resource).isEqualTo("0")
            assertThat(scalarAt(yaml, *path, "sending_queue", "enabled")).describedAs(resource).isEqualTo("true")
            assertThat(scalarAt(yaml, *path, "sending_queue", "queue_size")).describedAs(resource).isEqualTo("20000")
            assertThat(keysAt(yaml, *path, "sending_queue")).describedAs(resource).doesNotContain("storage")
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
     * into an in-memory queue sized for about an hour.
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
                arrayOf(*prw, "remote_write_queue"),
                arrayOf("exporters", "otlp_http/logs", "sending_queue"),
                arrayOf("exporters", "otlp_grpc/tempo", "sending_queue"),
            )
        for (queue in queues) {
            assertThat(scalarAt(yaml, *queue, "enabled")).describedAs(queue.joinToString("/")).isEqualTo("true")
            assertThat(scalarAt(yaml, *queue, "queue_size")).describedAs(queue.joinToString("/")).isEqualTo("20000")
            assertThat(keysAt(yaml, *queue)).describedAs("no on-disk queue").doesNotContain("storage")
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
}
