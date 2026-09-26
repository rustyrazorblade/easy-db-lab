package com.rustyrazorblade.easydblab.configuration.otel

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.services.LogQl
import com.rustyrazorblade.easydblab.services.TemplateService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.DockerClientFactory
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.time.Duration
import java.util.UUID

/**
 * Runs the pinned OTel collector with the cluster's rendered configuration, unedited, in front of
 * the pinned Mimir and Loki, and proves that a metric and a log line pushed to the collector arrive
 * in the cluster's tenant carrying the cluster label, and that a journald line's `source` arrives
 * as a Loki label. A Spark job's line sent to an EMR node's collector, with its rendered
 * configuration, reaches Loki through the cluster's collector and is found by the queries `spark
 * logs` and `logs query --source emr` send.
 *
 * The collector maps Mimir's and Loki's in-cluster service names to the containers' addresses on
 * Docker's default bridge in `/etc/hosts`, so its local-mode endpoints resolve as they do on a
 * cluster. The collector gets a placeholder service
 * account token so its Kubernetes components start; they find no API server, which only means no
 * pod metadata, and nothing here depends on it.
 */
class CollectorToBackendsIntegrationTest : BaseKoinTest() {
    private companion object {
        const val TENANT = "acme"
        const val CLUSTER = "e2e-lab-0123"
        const val SA_DIR = "/var/run/secrets/kubernetes.io/serviceaccount"
        val ARRIVAL: Duration = Duration.ofMinutes(2)
        val POLL: Duration = Duration.ofSeconds(2)
        const val LOG_LINES = 20
        const val COMPONENT_ID_LENGTH = 30
    }

    private val s3 = SharedLocalStack.s3Client()
    private val docker = DockerClientFactory.instance().client()
    private val bucket = "collector-e2e-${UUID.randomUUID().toString().take(8)}"
    private val volumes = listOf("mimir-e2e", "loki-e2e").map { "$it-${UUID.randomUUID().toString().take(8)}" }
    private val containers = mutableListOf<GenericContainer<*>>()
    private val state =
        ClusterState(name = "e2e-lab", versions = mutableMapOf(), s3Bucket = bucket, initConfig = InitConfig(tenant = TENANT))

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<ClusterStateManager>().also { whenever(it.load()).thenReturn(state) } }
                single { TemplateService(get(), get()) }
            },
        )

    @AfterEach
    fun cleanUp() {
        containers.forEach { it.stop() }
        volumes.forEach { runCatching { docker.removeVolumeCmd(it).exec() } }
    }

    private fun startBackends(): Pair<GenericContainer<*>, GenericContainer<*>> {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
        volumes.forEach { docker.createVolumeCmd().withName(it).exec() }
        val templates: TemplateService = getKoin().get()
        val mimir =
            ObservabilityBackends.startMimir(
                ObservabilityBackends.mimirConfig(
                    MimirManifestBuilder(templates).buildConfigMap().data.getValue(MimirManifestBuilder.CONFIG_FILE),
                ),
                volumes[0],
                bucket,
                Constants.Observability.METRICS_ROOT,
            )
        containers.add(mimir)
        val loki =
            ObservabilityBackends.startLoki(
                ObservabilityBackends.lokiConfig(
                    LokiManifestBuilder(templates).buildConfigMap().data.getValue(LokiManifestBuilder.CONFIG_FILE),
                ),
                volumes[1],
                bucket,
                "observability/logs",
                TENANT,
                CLUSTER,
            )
        containers.add(loki)
        return mimir to loki
    }

    /** [container]'s address on Docker's default bridge, which every container here shares. */
    private fun bridgeAddress(container: GenericContainer<*>): String {
        val networks =
            docker
                .inspectContainerCmd(container.containerId)
                .exec()
                .networkSettings.networks
        return checkNotNull(networks["bridge"]?.ipAddress) { "no address on the default bridge: $networks" }
    }

    private fun startCollector(
        mimir: GenericContainer<*>,
        loki: GenericContainer<*>,
    ): GenericContainer<*> {
        val config = OtelManifestBuilder(getKoin().get()).buildConfigMap(emptyList()).data.getValue("otel-collector-config.yaml")
        return GenericContainer("otel/opentelemetry-collector-contrib:${Constants.OtelCollector.VERSION}")
            .withExtraHost("mimir.default.svc.cluster.local", bridgeAddress(mimir))
            .withExtraHost("loki.default.svc.cluster.local", bridgeAddress(loki))
            .withCopyToContainer(Transferable.of(config), "/etc/otel-collector-config.yaml")
            .withCopyToContainer(Transferable.of(""), "${OtelManifestBuilder.HOST_ROOT_MOUNT_PATH}/.keep")
            .withCopyToContainer(Transferable.of("placeholder"), "$SA_DIR/token")
            .withCopyToContainer(Transferable.of("default"), "$SA_DIR/namespace")
            .withEnv("HOSTNAME", "node0")
            .withEnv("CLUSTER_NAME", CLUSTER)
            .withEnv("TENANT", TENANT)
            .withEnv("KUBERNETES_SERVICE_HOST", "127.0.0.1")
            .withEnv("KUBERNETES_SERVICE_PORT", "1")
            .withCommand("--config=/etc/otel-collector-config.yaml")
            .withExposedPorts(Constants.K8s.OTEL_HTTP_PORT, Constants.K8s.OTEL_HEALTH_PORT)
            .waitingFor(Wait.forHttp("/").forPort(Constants.K8s.OTEL_HEALTH_PORT).withStartupTimeout(Duration.ofMinutes(2)))
            .also {
                it.start()
                containers.add(it)
            }
    }

    /** An EMR node's collector, configured as the bootstrap action writes it, sending to [control]. */
    private fun startEmrCollector(control: GenericContainer<*>): GenericContainer<*> {
        val config =
            checkNotNull(javaClass.getResource("/com/rustyrazorblade/easydblab/configuration/emr/otel-collector-config.yaml"))
                .readText()
                .replace("__CONTROL_NODE_IP__", bridgeAddress(control))
                .replace("__NODE_ROLE__", "spark-master")
        return GenericContainer("otel/opentelemetry-collector-contrib:${Constants.OtelCollector.VERSION}")
            .withCopyToContainer(Transferable.of(config), "/etc/otel-collector-config.yaml")
            .withCommand("--config=/etc/otel-collector-config.yaml")
            .withExposedPorts(Constants.K8s.OTEL_HTTP_PORT, Constants.K8s.OTEL_HEALTH_PORT)
            .waitingFor(Wait.forHttp("/").forPort(Constants.K8s.OTEL_HEALTH_PORT).withStartupTimeout(Duration.ofMinutes(2)))
            .also {
                it.start()
                containers.add(it)
            }
    }

    private fun lokiStreams(
        loki: GenericContainer<*>,
        logql: String,
    ): List<JsonObject> {
        val lokiUrl = ObservabilityBackends.baseUrl(loki, Constants.K8s.LOKI_HTTP_PORT)
        val query = URLEncoder.encode(logql, Charsets.UTF_8)
        return results(ObservabilityBackends.get("$lokiUrl/loki/api/v1/query_range?query=$query&since=10m", TENANT).body())
    }

    private fun post(
        collector: GenericContainer<*>,
        path: String,
        body: String,
    ) {
        val response =
            ObservabilityBackends.send(
                HttpRequest
                    .newBuilder(URI("${ObservabilityBackends.baseUrl(collector, Constants.K8s.OTEL_HTTP_PORT)}$path"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
            )
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
    }

    private fun <T> await(
        collector: GenericContainer<*>,
        probe: () -> T?,
    ): T {
        val deadline = System.nanoTime() + ARRIVAL.toNanos()
        var value = probe()
        while (value == null && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis())
            value = probe()
        }
        return checkNotNull(value) {
            "nothing arrived within $ARRIVAL; collector export failures:\n" +
                collector.logs
                    .lines()
                    .filter { it.contains("Exporting failed") }
                    .map {
                        it.substringAfter("\"otelcol.component.id\": ").take(COMPONENT_ID_LENGTH) + " " +
                            it.substringAfter("\"error\": ")
                    }.takeLast(LOG_LINES)
                    .joinToString("\n")
        }
    }

    private fun results(json: String): List<JsonObject> =
        Json
            .parseToJsonElement(json)
            .jsonObject
            .getValue("data")
            .jsonObject
            .getValue("result")
            .jsonArray
            .map { it.jsonObject }

    private fun labelsOf(
        result: JsonObject,
        key: String,
    ): Map<String, String> = result.getValue(key).jsonObject.mapValues { it.value.jsonPrimitive.content }

    @Test
    fun `metrics and logs reach Mimir and Loki in the cluster's tenant with the cluster label`() {
        val (mimir, loki) = startBackends()
        val collector = startCollector(mimir, loki)
        val nanos = System.currentTimeMillis() * 1_000_000

        post(
            collector,
            "/v1/metrics",
            """
            {"resourceMetrics":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"e2e"}}]},
             "scopeMetrics":[{"metrics":[{"name":"edl_e2e_probe","gauge":{"dataPoints":[{"asDouble":7,"timeUnixNano":"$nanos"}]}}]}]}]}
            """.trimIndent(),
        )
        post(
            collector,
            "/v1/logs",
            """
            {"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"e2e"}}]},
             "scopeLogs":[{"logRecords":[{"timeUnixNano":"$nanos","body":{"stringValue":"e2e line"},
               "attributes":[{"key":"source","value":{"stringValue":"journald"}}]}]}]}]}
            """.trimIndent(),
        )

        val mimirUrl = ObservabilityBackends.baseUrl(mimir, Constants.K8s.MIMIR_HTTP_PORT)
        val series =
            await(collector) {
                results(ObservabilityBackends.get("$mimirUrl/prometheus/api/v1/query?query=edl_e2e_probe", TENANT).body())
                    .firstOrNull()
            }
        assertThat(labelsOf(series, "metric")).containsEntry("cluster", CLUSTER)

        val lokiUrl = ObservabilityBackends.baseUrl(loki, Constants.K8s.LOKI_HTTP_PORT)
        val query = URLEncoder.encode("{cluster=\"$CLUSTER\"}", Charsets.UTF_8)
        val stream =
            await(collector) {
                results(ObservabilityBackends.get("$lokiUrl/loki/api/v1/query_range?query=$query&since=10m", TENANT).body())
                    .firstOrNull()
            }
        assertThat(labelsOf(stream, "stream")).containsEntry("cluster", CLUSTER).containsEntry("source", "journald")

        // Nothing went to another tenant.
        assertThat(results(ObservabilityBackends.get("$mimirUrl/prometheus/api/v1/query?query=edl_e2e_probe", "other").body())).isEmpty()
    }

    @Test
    fun `a Spark job's line from an EMR node is found by spark logs and by logs query for the emr source`() {
        val (mimir, loki) = startBackends()
        val control = startCollector(mimir, loki)
        val emr = startEmrCollector(control)
        val nanos = System.currentTimeMillis() * 1_000_000

        post(
            emr,
            "/v1/logs",
            """
            {"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"spark-BulkWriter"}}]},
             "scopeLogs":[{"logRecords":[{"timeUnixNano":"$nanos","body":{"stringValue":"Job 0 finished"}}]}]}]}
            """.trimIndent(),
        )

        val step = await(control) { lokiStreams(loki, LogQl.sparkStep(CLUSTER, "BulkWriter")).firstOrNull() }
        assertThat(labelsOf(step, "stream")).containsEntry("cluster", CLUSTER).containsEntry("source", "emr")
        assertThat(lokiStreams(loki, LogQl.logsQuery(CLUSTER, source = "emr"))).isNotEmpty()
        assertThat(lokiStreams(loki, LogQl.sparkStep(CLUSTER, "OtherJob"))).isEmpty()
    }
}
