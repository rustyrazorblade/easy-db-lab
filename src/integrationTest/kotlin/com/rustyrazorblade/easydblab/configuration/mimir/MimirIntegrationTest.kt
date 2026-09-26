package com.rustyrazorblade.easydblab.configuration.mimir

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.PrometheusRemoteWrite
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
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.time.Duration
import java.util.UUID

/**
 * Runs Mimir at the image the cluster deploys, with the configuration the cluster renders, against
 * S3 (LocalStack). Under decision D1 Mimir writes every block to S3 and reads only its own ingester.
 * The test proves:
 *
 * - two tenants write by remote write, carrying their cluster label, and each reads only its own;
 * - one query naming `a|b` returns both tenants' series, each marked with `__tenant_id__`;
 * - after the head is compacted into a block, the block lands under `observabilitymetrics/<tenant>/`
 *   and the samples are still answered, now from the local block;
 * - after a SIGKILL and a restart on the same data volume, every sample written before the kill is
 *   still answered: the WAL replays and the local block is kept;
 * - the set of objects in the bucket only grows: nothing Mimir runs deletes an object.
 */
class MimirIntegrationTest : BaseKoinTest() {
    private companion object {
        const val METRIC = "edl_it_probe"
        val BLOCK_WAIT: Duration = Duration.ofMinutes(3)
        val POLL: Duration = Duration.ofSeconds(2)
    }

    private val s3 = SharedLocalStack.s3Client()
    private val docker = DockerClientFactory.instance().client()
    private val bucket = "mimir-it-${UUID.randomUUID().toString().take(8)}"
    private val volume = "mimir-data-${UUID.randomUUID().toString().take(8)}"
    private val containers = mutableListOf<GenericContainer<*>>()
    private val objectSnapshots = mutableListOf<Set<String>>()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(ClusterState(name = "mimir-it", versions = mutableMapOf()))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun prepare() {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
        docker.createVolumeCmd().withName(volume).exec()
    }

    @AfterEach
    fun cleanUp() {
        containers.forEach { it.stop() }
        runCatching { docker.removeVolumeCmd(volume).exec() }
    }

    private fun startMimir(): GenericContainer<*> {
        val rendered = MimirManifestBuilder(getKoin().get()).buildConfigMap().data.getValue(MimirManifestBuilder.CONFIG_FILE)
        return ObservabilityBackends
            .startMimir(ObservabilityBackends.mimirConfig(rendered), volume, bucket, Constants.Observability.METRICS_ROOT)
            .also { containers.add(it) }
    }

    private fun url(mimir: GenericContainer<*>): String = ObservabilityBackends.baseUrl(mimir, Constants.K8s.MIMIR_HTTP_PORT)

    private fun write(
        mimir: GenericContainer<*>,
        tenant: String,
        cluster: String,
        value: Double,
    ) {
        val body =
            PrometheusRemoteWrite.body(
                listOf(
                    PrometheusRemoteWrite.Series(
                        labels = mapOf("__name__" to METRIC, "cluster" to cluster),
                        value = value,
                        timestampMillis = System.currentTimeMillis(),
                    ),
                ),
            )
        val response =
            ObservabilityBackends.send(
                HttpRequest
                    .newBuilder(URI("${url(mimir)}/api/v1/push"))
                    .header("Content-Type", "application/x-protobuf")
                    .header("Content-Encoding", "snappy")
                    .header("X-Prometheus-Remote-Write-Version", "0.1.0")
                    .header(Constants.Observability.TENANT_HEADER, tenant)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build(),
            )
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
    }

    /** The result vector of an instant PromQL [query] for [tenant]. */
    private fun query(
        mimir: GenericContainer<*>,
        tenant: String,
        query: String,
    ): List<JsonObject> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8)
        val response = ObservabilityBackends.get("${url(mimir)}/prometheus/api/v1/query?query=$encoded", tenant)
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

    private fun labels(series: JsonObject): Map<String, String> =
        series.getValue("metric").jsonObject.mapValues { it.value.jsonPrimitive.content }

    /** The number of samples of the tenant's probe series in the last hour. */
    private fun samples(
        mimir: GenericContainer<*>,
        tenant: String,
    ): Int =
        query(mimir, tenant, "count_over_time($METRIC[1h])")
            .sumOf {
                it
                    .getValue("value")
                    .jsonArray[1]
                    .jsonPrimitive.content
                    .toDouble()
                    .toInt()
            }

    private fun objectKeys(): Set<String> =
        s3
            .listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).build())
            .contents()
            .map { it.key() }
            .toSet()
            .also { objectSnapshots.add(it) }

    private fun blockMetas(tenant: String): List<String> =
        objectKeys().filter { it.startsWith("${Constants.Observability.METRICS_ROOT}/$tenant/") && it.endsWith("/meta.json") }

    private fun awaitBlock(tenant: String) {
        val deadline = System.nanoTime() + BLOCK_WAIT.toNanos()
        while (blockMetas(tenant).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis())
        }
        assertThat(blockMetas(tenant)).describedAs("blocks under ${Constants.Observability.METRICS_ROOT}/$tenant/").isNotEmpty()
    }

    /** Compacts every tenant's head into a block and ships it, the way a cut does after two hours. */
    private fun compactHead(mimir: GenericContainer<*>) {
        val response =
            ObservabilityBackends.send(
                HttpRequest
                    .newBuilder(URI("${url(mimir)}/ingester/flush?wait=true"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
            )
        assertThat(response.statusCode()).describedAs(response.body()).isIn(200, 204)
    }

    @Test
    fun `each tenant reads its own series, and a federated query reads both, marked with their tenant`() {
        val mimir = startMimir()
        write(mimir, "a", "lab-a", 1.0)
        write(mimir, "b", "lab-b", 2.0)

        assertThat(query(mimir, "a", METRIC).map { labels(it)["cluster"] }).containsExactly("lab-a")
        assertThat(query(mimir, "b", METRIC).map { labels(it)["cluster"] }).containsExactly("lab-b")

        val federated = query(mimir, "a|b", METRIC).map { labels(it) }
        assertThat(federated.map { it["__tenant_id__"] to it["cluster"] })
            .containsExactlyInAnyOrder("a" to "lab-a", "b" to "lab-b")
    }

    @Test
    fun `samples stay queryable after head compaction and a SIGKILL, and no object is ever deleted`() {
        val first = startMimir()
        write(first, "a", "lab-a", 1.0)
        write(first, "b", "lab-b", 1.0)

        // The head becomes a block, which ships under the tenant's directory, and is still read locally.
        compactHead(first)
        awaitBlock("a")
        awaitBlock("b")
        assertThat(samples(first, "a")).describedAs("read from the local block after head compaction").isEqualTo(1)

        // A sample only in the WAL survives a kill with no shutdown at all.
        write(first, "a", "lab-a", 2.0)
        objectKeys()
        docker.killContainerCmd(first.containerId).withSignal("KILL").exec()
        val second = startMimir()
        assertThat(samples(second, "a")).describedAs("the block and the replayed WAL, after SIGKILL").isEqualTo(2)
        assertThat(samples(second, "b")).isEqualTo(1)

        objectKeys()
        objectSnapshots.zipWithNext().forEach { (earlier, later) ->
            assertThat(later).describedAs("objects only accumulate").containsAll(earlier)
        }
    }
}
