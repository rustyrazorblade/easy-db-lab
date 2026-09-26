package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.ContainerObservabilityHttp
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.events.EventBus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
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
import org.testcontainers.containers.wait.strategy.Wait
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.time.Duration
import java.util.UUID

/**
 * Runs the annotation mirror against the Loki and Grafana images the cluster deploys, with Loki's
 * rendered configuration on S3 (LocalStack). It proves:
 *
 * - mirroring the same annotation twice leaves one line in Loki;
 * - an annotation backdated two hours is accepted after newer ones;
 * - [AnnotationMirror.syncAll] copies an annotation made directly in Grafana, as a UI one is;
 * - a region annotation keeps its end time.
 */
class AnnotationMirrorIntegrationTest : BaseKoinTest() {
    private companion object {
        const val TENANT = "acme"
        const val CLUSTER = "lab-x1"
        const val GRAFANA_PORT = 3000
    }

    private val s3 = SharedLocalStack.s3Client()
    private val docker = DockerClientFactory.instance().client()
    private val bucket = "annotations-it-${UUID.randomUUID().toString().take(8)}"
    private val volume = "loki-annot-${UUID.randomUUID().toString().take(8)}"
    private val containers = mutableListOf<GenericContainer<*>>()
    private val control = ClusterHost("127.0.0.1", "10.0.0.1", "control0", "us-west-2a")
    private lateinit var loki: GenericContainer<*>
    private lateinit var grafana: GenericContainer<*>
    private lateinit var mirror: DefaultAnnotationMirror

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(ClusterState(name = "lab", clusterId = "x1", versions = mutableMapOf()))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun start() {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
        docker.createVolumeCmd().withName(volume).exec()
        val rendered = LokiManifestBuilder(getKoin().get()).buildConfigMap().data.getValue(LokiManifestBuilder.CONFIG_FILE)
        loki =
            ObservabilityBackends
                .startLoki(ObservabilityBackends.lokiConfig(rendered), volume, bucket, "observability/logs", TENANT, CLUSTER)
                .also { containers.add(it) }
        grafana =
            GenericContainer(GrafanaManifestBuilder.GRAFANA_IMAGE)
                .withEnv("GF_AUTH_ANONYMOUS_ENABLED", "true")
                .withEnv("GF_AUTH_ANONYMOUS_ORG_ROLE", "Admin")
                .withExposedPorts(GRAFANA_PORT)
                .waitingFor(Wait.forHttp("/api/health").forPort(GRAFANA_PORT).withStartupTimeout(Duration.ofMinutes(3)))
                .apply { start() }
                .also { containers.add(it) }

        val http = ContainerObservabilityHttp(TENANT) { port -> ObservabilityBackends.baseUrl(loki, port) }
        mirror = DefaultAnnotationMirror(LokiPushClient(http), grafanaService(), getKoin().get(), getKoin().get())
    }

    @AfterEach
    fun stop() {
        containers.forEach { it.stop() }
        runCatching { docker.removeVolumeCmd(volume).exec() }
    }

    /** The real Grafana client, with every request redirected to the Grafana container. */
    private fun grafanaService(): GrafanaDashboardService {
        val redirect =
            Interceptor { chain ->
                val url =
                    chain
                        .request()
                        .url
                        .newBuilder()
                        .host(grafana.host)
                        .port(grafana.getMappedPort(GRAFANA_PORT))
                        .build()
                chain.proceed(
                    chain
                        .request()
                        .newBuilder()
                        .url(url)
                        .build(),
                )
            }
        val eventBus = getKoin().get<EventBus>()
        return DefaultGrafanaDashboardService(
            k8sService = mock(),
            manifestBuilder = mock(),
            treeUploader = mock(),
            eventBus = eventBus,
            okHttpClient = OkHttpClient.Builder().addInterceptor(redirect).build(),
            configChangeReport = ConfigChangeReport(mock(), eventBus),
        )
    }

    /**
     * Every mirrored annotation entry of the cluster in the last 3 hours, read once at least
     * [expected] entries are visible (a busy Loki may take a moment to answer a just-pushed line), or
     * after 30s.
     */
    private fun mirrored(expected: Int): List<Pair<Map<String, String>, String>> {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        var entries = mirroredNow()
        while (entries.size < expected && System.nanoTime() < deadline) {
            Thread.sleep(Duration.ofSeconds(1).toMillis())
            entries = mirroredNow()
        }
        return entries
    }

    private fun mirroredNow(hours: Int = 3): List<Pair<Map<String, String>, String>> {
        val query = URLEncoder.encode("{source=\"annotation\", cluster=\"$CLUSTER\"}", Charsets.UTF_8)
        val response =
            ObservabilityBackends.get(
                "${ObservabilityBackends.baseUrl(
                    loki,
                    Constants.K8s.LOKI_HTTP_PORT,
                )}/loki/api/v1/query_range?query=$query&since=${hours}h&limit=1000",
                TENANT,
            )
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
        return Json
            .parseToJsonElement(response.body())
            .jsonObject
            .getValue("data")
            .jsonObject
            .getValue("result")
            .jsonArray
            .map { it.jsonObject }
            .flatMap { stream -> stream.values(stream.labels()) }
    }

    private fun JsonObject.labels(): Map<String, String> = getValue("stream").jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun JsonObject.values(labels: Map<String, String>) =
        getValue("values").jsonArray.map { labels to it.jsonArray[1].jsonPrimitive.content }

    @Test
    fun `mirroring the same annotation twice leaves one entry`() {
        val annotation =
            MirroredAnnotation(id = 5, text = "concurrent_reads 64->128", tags = listOf("ab"), time = System.currentTimeMillis())

        mirror.push(annotation).getOrThrow()
        mirror.push(annotation).getOrThrow()

        assertThat(mirrored(expected = 1).map { it.second }).containsExactly("concurrent_reads 64->128")
    }

    @Test
    fun `an annotation backdated two hours is accepted after newer ones`() {
        val now = System.currentTimeMillis()
        mirror.push(MirroredAnnotation(id = 1, text = "newer", tags = emptyList(), time = now)).getOrThrow()

        mirror
            .push(
                MirroredAnnotation(id = 2, text = "backdated", tags = emptyList(), time = now - Duration.ofHours(2).toMillis()),
            ).getOrThrow()

        assertThat(mirrored(expected = 2).map { it.second }).containsExactlyInAnyOrder("newer", "backdated")
    }

    @Test
    fun `syncAll copies an annotation made directly in Grafana, and a region keeps its end time`() {
        val start = System.currentTimeMillis() - Duration.ofMinutes(10).toMillis()
        val end = start + Duration.ofMinutes(5).toMillis()
        val created =
            ObservabilityBackends.send(
                HttpRequest
                    .newBuilder(URI("http://${grafana.host}:${grafana.getMappedPort(GRAFANA_PORT)}/api/annotations"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"text":"made in the UI","tags":["ui"],"time":$start,"timeEnd":$end}"""))
                    .build(),
            )
        assertThat(created.statusCode()).describedAs(created.body()).isEqualTo(200)

        assertThat(mirror.syncAll(control).getOrThrow()).isEqualTo(1)

        val (labels, line) = mirrored(expected = 1).single()
        assertThat(line).isEqualTo("made in the UI")
        assertThat(labels).containsEntry("cluster", CLUSTER).containsEntry("source", "annotation").containsEntry("tags", "ui")
        assertThat(labels["time_end"]).isEqualTo(end.toString())
        assertThat(labels).doesNotContainKey("dashboard_uid")
    }
}
