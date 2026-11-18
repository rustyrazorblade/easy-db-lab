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
import com.rustyrazorblade.easydblab.services.TemplateService
import kotlinx.serialization.json.jsonArray
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
import org.testcontainers.containers.Network
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proves that a backend restart loses no telemetry: the cluster's collector, with its rendered
 * configuration, holds what it cannot deliver and sends it once the backend is back (issue #991).
 *
 * The collector and the backend share a Docker network on which the backend answers to its
 * in-cluster Service name, the name the collector's local-mode endpoints use. As a Service does,
 * the name resolves only once the backend is ready, and goes when it is removed, so a restarted
 * backend on a new address is found again. The backend restarts on the same data volume, as the
 * control node's hostPath keeps it.
 *
 * A producer sends one numbered metric sample or log line a second to the collector, before,
 * during and after a [OUTAGE] outage, and the test asserts that every number arrives.
 */
class CollectorReplayIntegrationTest : BaseKoinTest() {
    private companion object {
        const val TENANT = "acme"
        const val CLUSTER = "replay-lab-0123"
        const val CONTROL_HOST = "control0"
        const val METRIC = "edl_replay_seq"
        const val LINE_PREFIX = "replay-line-"
        const val LOKI_LIMIT = 5000
        val SEND_INTERVAL: Duration = Duration.ofSeconds(1)
        val BEFORE: Duration = Duration.ofSeconds(15)
        val OUTAGE: Duration = Duration.ofSeconds(60)
        val AFTER: Duration = Duration.ofSeconds(15)
        val ARRIVAL: Duration = Duration.ofMinutes(4)
        val POLL: Duration = Duration.ofSeconds(5)
        val MIMIR_HOST = "${Constants.K8s.MIMIR_APP_LABEL}.default.svc.cluster.local"
        val LOKI_HOST = "${Constants.K8s.LOKI_APP_LABEL}.default.svc.cluster.local"
    }

    private val s3 = SharedLocalStack.s3Client()
    private val docker = DockerClientFactory.instance().client()
    private val bucket = "collector-replay-${UUID.randomUUID().toString().take(8)}"
    private val volume = "replay-${UUID.randomUUID().toString().take(8)}"
    private val network: Network = Network.newNetwork()
    private val containers = mutableListOf<GenericContainer<*>>()
    private val state =
        ClusterState(name = "replay-lab", s3Bucket = bucket, initConfig = InitConfig(tenant = TENANT))

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
        runCatching { docker.removeVolumeCmd(volume).exec() }
        network.close()
    }

    private fun prepare() {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
        docker.createVolumeCmd().withName(volume).exec()
    }

    private fun templates(): TemplateService = getKoin().get()

    private fun startMimir(): GenericContainer<*> =
        ObservabilityBackends
            .startMimir(
                ObservabilityBackends.mimirConfig(
                    MimirManifestBuilder(templates()).buildConfigMap().data.getValue(MimirManifestBuilder.CONFIG_FILE),
                ),
                volume,
                bucket,
                Constants.Observability.METRICS_ROOT,
            ).also { serve(it, MIMIR_HOST) }

    private fun startLoki(): GenericContainer<*> =
        ObservabilityBackends
            .startLoki(
                ObservabilityBackends.lokiConfig(
                    LokiManifestBuilder(templates()).buildConfigMap().data.getValue(LokiManifestBuilder.CONFIG_FILE),
                ),
                volume,
                bucket,
                Constants.Observability.LOGS_ROOT,
                TENANT,
                CLUSTER,
            ).also { serve(it, LOKI_HOST) }

    private fun serve(
        backend: GenericContainer<*>,
        serviceName: String,
    ) {
        containers.add(backend)
        ObservabilityBackends.attachAsService(backend, network.id, serviceName)
    }

    private fun startCollector(): GenericContainer<*> {
        val config = OtelManifestBuilder(templates()).buildConfigMap(emptyList()).data.getValue("otel-collector-config.yaml")
        return ClusterCollector
            .container(config, CONTROL_HOST, CLUSTER, TENANT)
            .withNetwork(network)
            .also {
                it.start()
                containers.add(it)
            }
    }

    /**
     * Sends `body(n)` to [path] on [collector] once per [SEND_INTERVAL] for n = 1, 2, ... while
     * [restart] stops and starts the backend, then for [AFTER] more. Returns the last number sent.
     * The collector accepts every request: what it cannot deliver is its to hold, not the producer's.
     */
    private fun produceAcross(
        collector: GenericContainer<*>,
        path: String,
        body: (Int) -> String,
        restart: () -> Unit,
    ): Int {
        val url = "${ObservabilityBackends.baseUrl(collector, Constants.K8s.OTEL_HTTP_PORT)}$path"
        val sent = AtomicInteger()
        val refused = ConcurrentLinkedQueue<String>()
        val producer = Executors.newSingleThreadScheduledExecutor()
        producer.scheduleAtFixedRate(
            {
                val n = sent.incrementAndGet()
                val response =
                    ObservabilityBackends.send(
                        HttpRequest
                            .newBuilder(URI(url))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body(n)))
                            .build(),
                    )
                if (response.statusCode() != HTTP_OK) refused.add("$n: ${response.statusCode()} ${response.body()}")
            },
            0,
            SEND_INTERVAL.toMillis(),
            TimeUnit.MILLISECONDS,
        )
        try {
            Thread.sleep(BEFORE.toMillis())
            restart()
            Thread.sleep(AFTER.toMillis())
        } finally {
            producer.shutdown()
            producer.awaitTermination(1, TimeUnit.MINUTES)
        }
        assertThat(refused).describedAs("the collector accepted every request").isEmpty()
        return sent.get()
    }

    /** Stops [backend] gracefully, keeps it down for [OUTAGE] in all, and starts its replacement. */
    private fun restartAfterOutage(
        backend: GenericContainer<*>,
        start: () -> GenericContainer<*>,
    ) {
        val down = System.nanoTime()
        ObservabilityBackends.stopGracefully(backend)
        containers.remove(backend)
        Thread.sleep(maxOf(0L, OUTAGE.toMillis() - Duration.ofNanos(System.nanoTime() - down).toMillis()))
        start()
    }

    /** Polls [arrived] until every number 1..[last] is in it, and returns the ones still missing. */
    private fun missingAfterArrival(
        last: Int,
        arrived: () -> Set<Int>,
    ): List<Int> {
        val deadline = System.nanoTime() + ARRIVAL.toNanos()
        var missing = (1..last) - arrived()
        while (missing.isNotEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis())
            missing = (1..last) - arrived()
        }
        return missing
    }

    private fun metricBody(n: Int): String {
        val nanos = System.currentTimeMillis() * NANOS_PER_MILLI
        return """
            {"resourceMetrics":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"replay"}}]},
             "scopeMetrics":[{"metrics":[{"name":"$METRIC","gauge":{"dataPoints":[{"asDouble":$n,"timeUnixNano":"$nanos"}]}}]}]}]}
            """.trimIndent()
    }

    private fun logBody(n: Int): String {
        val nanos = System.currentTimeMillis() * NANOS_PER_MILLI
        return """
            {"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"replay"}}]},
             "scopeLogs":[{"logRecords":[{"timeUnixNano":"$nanos","body":{"stringValue":"$LINE_PREFIX$n"}}]}]}]}
            """.trimIndent()
    }

    /** Every value of [METRIC] in Mimir over the last 30 minutes, as the sequence numbers sent. */
    private fun samplesIn(mimir: () -> GenericContainer<*>): Set<Int> {
        val url = ObservabilityBackends.baseUrl(mimir(), Constants.K8s.MIMIR_HTTP_PORT)
        val query = URLEncoder.encode("$METRIC[30m]", Charsets.UTF_8)
        val response = ObservabilityBackends.get("$url/prometheus/api/v1/query?query=$query", TENANT)
        if (response.statusCode() != HTTP_OK) return emptySet()
        return ClusterCollector
            .results(response.body())
            .flatMap { it.getValue("values").jsonArray }
            .map {
                it.jsonArray[1]
                    .jsonPrimitive.content
                    .toDouble()
                    .toInt()
            }.toSet()
    }

    /** Every replay line in Loki over the last 30 minutes, as the sequence numbers sent. */
    private fun linesIn(loki: () -> GenericContainer<*>): Set<Int> {
        val url = ObservabilityBackends.baseUrl(loki(), Constants.K8s.LOKI_HTTP_PORT)
        val query = URLEncoder.encode("{cluster=\"$CLUSTER\"} |= \"$LINE_PREFIX\"", Charsets.UTF_8)
        val response =
            ObservabilityBackends.get("$url/loki/api/v1/query_range?query=$query&since=30m&limit=$LOKI_LIMIT", TENANT)
        if (response.statusCode() != HTTP_OK) return emptySet()
        return ClusterCollector
            .results(response.body())
            .flatMap { it.getValue("values").jsonArray }
            .map { it.jsonArray[1].jsonPrimitive.content }
            .filter { it.startsWith(LINE_PREFIX) }
            .map { it.removePrefix(LINE_PREFIX).toInt() }
            .toSet()
    }

    @Test
    fun `a Mimir restart loses no metric sample`() {
        prepare()
        var mimir = startMimir()
        val collector = startCollector()

        val last = produceAcross(collector, "/v1/metrics", ::metricBody) { restartAfterOutage(mimir) { startMimir().also { mimir = it } } }

        val missing = missingAfterArrival(last) { samplesIn { mimir } }
        assertThat(missing)
            .describedAs(
                "of samples 1..$last sent, the ones missing from Mimir after a ${OUTAGE.seconds}s restart; collector:\n" +
                    ClusterCollector.exportFailures(collector),
            ).isEmpty()
    }

    @Test
    fun `a Loki restart loses no log line`() {
        prepare()
        var loki = startLoki()
        val collector = startCollector()

        val last = produceAcross(collector, "/v1/logs", ::logBody) { restartAfterOutage(loki) { startLoki().also { loki = it } } }

        val missing = missingAfterArrival(last) { linesIn { loki } }
        assertThat(missing)
            .describedAs(
                "of lines 1..$last sent, the ones missing from Loki after a ${OUTAGE.seconds}s restart; collector:\n" +
                    ClusterCollector.exportFailures(collector),
            ).isEmpty()
    }
}

private const val HTTP_OK = 200
private const val NANOS_PER_MILLI = 1_000_000L
