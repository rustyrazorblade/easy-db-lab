package com.rustyrazorblade.easydblab.configuration.loki

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.TemplateService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.DockerClientFactory
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.time.Duration
import java.util.UUID

/**
 * Runs Loki at the image the cluster deploys, with the configuration the cluster renders, against
 * S3 (LocalStack). The test proves:
 *
 * - a line only in the WAL survives a SIGKILL and a restart on the same data volume;
 * - the compactor never compacts, and a delete request is refused with the lines kept.
 */
class LokiIntegrationTest : BaseKoinTest() {
    private companion object {
        const val PREFIX = "loki"
    }

    private val s3 = SharedLocalStack.s3Client()
    private val docker = DockerClientFactory.instance().client()
    private val bucket = "loki-it-${UUID.randomUUID().toString().take(8)}"
    private val volumes = mutableListOf<String>()
    private val containers = mutableListOf<GenericContainer<*>>()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(ClusterState(name = "loki-it", versions = mutableMapOf()))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun prepare() {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
    }

    @AfterEach
    fun cleanUp() {
        containers.forEach { it.stop() }
        volumes.forEach { runCatching { docker.removeVolumeCmd(it).exec() } }
    }

    private fun newVolume(): String =
        "loki-data-${UUID.randomUUID().toString().take(8)}".also {
            docker.createVolumeCmd().withName(it).exec()
            volumes.add(it)
        }

    private fun startLoki(
        volume: String,
        tenant: String,
        cluster: String,
    ): GenericContainer<*> {
        val rendered = LokiManifestBuilder(getKoin().get()).buildConfigMap().data.getValue(LokiManifestBuilder.CONFIG_FILE)
        return ObservabilityBackends
            .startLoki(ObservabilityBackends.lokiConfig(rendered), volume, bucket, PREFIX, tenant, cluster)
            .also { containers.add(it) }
    }

    private fun url(loki: GenericContainer<*>): String = ObservabilityBackends.baseUrl(loki, Constants.K8s.LOKI_HTTP_PORT)

    private fun push(
        loki: GenericContainer<*>,
        tenant: String,
        cluster: String,
        line: String,
    ) {
        val nanos = System.currentTimeMillis() * 1_000_000
        val body =
            """
            {"resourceLogs":[{"resource":{"attributes":[
              {"key":"cluster","value":{"stringValue":"$cluster"}},
              {"key":"host.name","value":{"stringValue":"db0"}},
              {"key":"node_role","value":{"stringValue":"db"}},
              {"key":"source","value":{"stringValue":"cassandra"}}]},
             "scopeLogs":[{"logRecords":[{"timeUnixNano":"$nanos","body":{"stringValue":"$line"}}]}]}]}
            """.trimIndent()
        val response =
            ObservabilityBackends.send(
                HttpRequest
                    .newBuilder(URI("${url(loki)}/otlp/v1/logs"))
                    .header("Content-Type", "application/json")
                    .header(Constants.Observability.TENANT_HEADER, tenant)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
            )
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(204)
    }

    /** Every stream a LogQL [query] over the last hour returns for [tenant]. */
    private fun streams(
        loki: GenericContainer<*>,
        tenant: String,
        query: String,
    ): List<JsonObject> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8)
        val response = ObservabilityBackends.get("${url(loki)}/loki/api/v1/query_range?query=$encoded&since=1h&limit=1000", tenant)
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
        return Json
            .parseToJsonElement(response.body())
            .jsonObject
            .getValue("data")
            .jsonObject
            .getValue("result")
            .jsonArray
            .map { it.jsonObject }
    }

    /**
     * The streams of [query] once they hold at least [expected] lines (a busy Loki may take a moment
     * to answer a just-pushed line), or after 30s.
     */
    private fun awaitStreams(
        loki: GenericContainer<*>,
        tenant: String,
        query: String,
        expected: Int,
    ): List<JsonObject> {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        var found = streams(loki, tenant, query)
        while (lines(found).size < expected && System.nanoTime() < deadline) {
            Thread.sleep(Duration.ofSeconds(1).toMillis())
            found = streams(loki, tenant, query)
        }
        return found
    }

    private fun lines(streams: List<JsonObject>): List<String> =
        streams.flatMap { stream -> stream.getValue("values").jsonArray.map { it.jsonArray[1].jsonPrimitive.content } }

    private fun metric(
        loki: GenericContainer<*>,
        name: String,
    ): Double =
        ObservabilityBackends
            .get("${url(loki)}/metrics", "unused")
            .body()
            .lines()
            .single { it.startsWith("$name ") }
            .substringAfter(' ')
            .toDouble()

    @Test
    fun `a line only in the WAL survives a SIGKILL, nothing compacts, and a delete is refused`() {
        val volume = newVolume()
        val first = startLoki(volume, "acme", "lab-x")
        push(first, "acme", "lab-x", "kept across a kill")
        docker.killContainerCmd(first.containerId).withSignal("KILL").exec()

        val second = startLoki(volume, "acme", "lab-x")
        assertThat(lines(awaitStreams(second, "acme", """{cluster="lab-x"}""", expected = 1))).containsExactly("kept across a kill")

        assertThat(metric(second, "loki_boltdb_shipper_compact_tables_operation_last_successful_run_timestamp_seconds"))
            .describedAs("the compactor has never compacted")
            .isZero()

        val delete =
            ObservabilityBackends.send(
                HttpRequest
                    .newBuilder(
                        URI("${url(second)}/loki/api/v1/delete?query=${URLEncoder.encode("{cluster=\"lab-x\"}", Charsets.UTF_8)}&start=0"),
                    ).header(Constants.Observability.TENANT_HEADER, "acme")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
            )
        assertThat(delete.statusCode()).describedAs("the delete API is not served").isIn(403, 404)
        assertThat(lines(streams(second, "acme", """{cluster="lab-x"}"""))).containsExactly("kept across a kill")
    }
}
