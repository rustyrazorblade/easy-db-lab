package com.rustyrazorblade.easydblab.configuration.otel

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.LokiIngester
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
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
 * A producer sends one numbered metric sample, log line or span a second to the collector, before,
 * during and after the outage, and the test asserts that every number arrives. Each outage is
 * chosen to fail the old configuration: [RETRY_LIMIT_OUTAGE] outlasts the 5s push deadline that
 * dropped every Mimir batch, and the exporters' 300s default retry limit, which the Loki and Tempo
 * exporters had and which `prometheus_remote_write` 0.161.0 also applies (it passes
 * `max_elapsed_time` to its retry loop), so each test fails without `max_elapsed_time: 0`.
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
        val RETRY_LIMIT_OUTAGE: Duration = Duration.ofMinutes(6)
        val AFTER: Duration = Duration.ofSeconds(15)
        val ARRIVAL: Duration = Duration.ofMinutes(4)
        val POLL: Duration = Duration.ofSeconds(5)

        /** One trace lookup, so a hung Tempo answers in seconds; the poll itself stops at [ARRIVAL]. */
        val TRACE_LOOKUP_TIMEOUT: Duration = Duration.ofSeconds(2)

        /** Older than Mimir's 1h out-of-order window, so Mimir answers 400. */
        val TOO_OLD: Duration = Duration.ofHours(2)

        /** Long enough for the rejected batch to be sent and later batches to queue behind it. */
        val AFTER_REJECT: Duration = Duration.ofSeconds(30)
        const val STALE_VALUE = -1
        const val TOO_OLD_ERROR = "err-mimir-sample-"
        val MIMIR_HOST = "${Constants.K8s.MIMIR_APP_LABEL}.default.svc.cluster.local"
        val LOKI_HOST = "${Constants.K8s.LOKI_APP_LABEL}.default.svc.cluster.local"
        val TEMPO_HOST = "${Constants.K8s.TEMPO_APP_LABEL}.default.svc.cluster.local"
    }

    private val s3 = SharedLocalStack.s3Client()
    private val docker = DockerClientFactory.instance().client()
    private val bucket = "collector-replay-${UUID.randomUUID().toString().take(8)}"
    private val volume = "replay-${UUID.randomUUID().toString().take(8)}"
    private val traceRun =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(SPAN_ID_LENGTH)
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
                LokiIngester(TENANT, CLUSTER),
            ).also { serve(it, LOKI_HOST) }

    private fun startTempo(): GenericContainer<*> =
        ObservabilityBackends
            .startTempo(
                ObservabilityBackends.tempoConfig(
                    TempoManifestBuilder(templates()).buildConfigMap().data.getValue(TempoManifestBuilder.CONFIG_FILE),
                ),
                volume,
                bucket,
                Constants.Observability.TRACES_ROOT,
                CLUSTER,
            ).also { serve(it, TEMPO_HOST) }

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
     * A send that throws is recorded as refused, because a throwing task would silently end the
     * schedule and the test would report the unsent numbers as lost.
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
                runCatching {
                    ObservabilityBackends.send(
                        HttpRequest
                            .newBuilder(URI(url))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body(n)))
                            .build(),
                    )
                }.onSuccess { response ->
                    if (response.statusCode() != HTTP_OK) refused.add("$n: ${response.statusCode()} ${response.body()}")
                }.onFailure { refused.add("$n: $it") }
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

    /** Stops [backend] gracefully, keeps it down for [outage] in all, and starts its replacement. */
    private fun restartAfterOutage(
        backend: GenericContainer<*>,
        outage: Duration,
        start: () -> GenericContainer<*>,
    ) {
        val down = System.nanoTime()
        ObservabilityBackends.stopGracefully(backend)
        containers.remove(backend)
        Thread.sleep(maxOf(0L, outage.toMillis() - Duration.ofNanos(System.nanoTime() - down).toMillis()))
        start()
    }

    /**
     * Polls [arrived] until every number 1..[last] is in it, and returns the ones still missing.
     * [arrived] gets the deadline, so a poll that makes many requests can stop at it.
     */
    private fun missingAfterArrival(
        last: Int,
        arrived: (deadline: Long) -> Set<Int>,
    ): List<Int> {
        val deadline = System.nanoTime() + ARRIVAL.toNanos()
        var missing = (1..last) - arrived(deadline)
        while (missing.isNotEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis())
            missing = (1..last) - arrived(deadline)
        }
        return missing
    }

    private fun metricBody(n: Int): String = metricBody(n, System.currentTimeMillis())

    private fun metricBody(
        n: Int,
        timestampMillis: Long,
    ): String {
        val nanos = timestampMillis * NANOS_PER_MILLI
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

    /** A trace id unique to this run that carries the sequence number [n] in its low 16 hex digits. */
    private fun traceId(n: Int): String = traceRun + "%016x".format(n)

    private fun spanBody(n: Int): String {
        val nanos = System.currentTimeMillis() * NANOS_PER_MILLI
        val id = traceId(n)
        return """
            {"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"replay"}}]},
             "scopeSpans":[{"spans":[{"traceId":"$id","spanId":"${id.takeLast(SPAN_ID_LENGTH)}","name":"$SPAN_NAME","kind":1,
             "startTimeUnixNano":"$nanos","endTimeUnixNano":"${nanos + NANOS_PER_MILLI}"}]}]}]}
            """.trimIndent()
    }

    /**
     * Adds to [found] every number in 1..[last] whose trace Tempo now finds, and returns [found].
     * Tempo answers 200 with an empty `trace` object for an id it does not have, so a trace counts
     * as found only when the response carries its span. The lookups run one after another, so the
     * poll stops at [deadline] (from [System.nanoTime]) rather than outlast the arrival budget.
     */
    private fun tracesIn(
        tempo: GenericContainer<*>,
        last: Int,
        found: MutableSet<Int>,
        deadline: Long,
    ): Set<Int> {
        val url = ObservabilityBackends.baseUrl(tempo, Constants.K8s.TEMPO_PORT)
        (1..last)
            .asSequence()
            .filter { it !in found }
            .takeWhile { System.nanoTime() < deadline }
            .filter { n ->
                runCatching {
                    val response =
                        ObservabilityBackends.send(
                            HttpRequest
                                .newBuilder(URI("$url/api/v2/traces/${traceId(n)}"))
                                .header(Constants.Observability.TENANT_HEADER, TENANT)
                                .timeout(TRACE_LOOKUP_TIMEOUT)
                                .GET()
                                .build(),
                        )
                    response.statusCode() == HTTP_OK && response.body().contains("\"$SPAN_NAME\"")
                }.getOrDefault(false)
            }.forEach { found.add(it) }
        return found
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

        val last =
            produceAcross(collector, "/v1/metrics", ::metricBody) {
                restartAfterOutage(mimir, RETRY_LIMIT_OUTAGE) { startMimir().also { mimir = it } }
            }

        val missing = missingAfterArrival(last) { samplesIn { mimir } }
        assertThat(missing)
            .describedAs(
                "of samples 1..$last sent, the ones missing from Mimir after a ${RETRY_LIMIT_OUTAGE.seconds}s outage; collector:\n" +
                    ClusterCollector.exportFailures(collector),
            ).isEmpty()
    }

    /**
     * The remote-write queue has one consumer and retries without a time limit, so a batch Mimir
     * rejects must be dropped, not retried: a batch retried forever would hold back every batch
     * behind it. A sample older than Mimir's out-of-order window is answered with 400; every
     * numbered sample sent after it must still arrive.
     */
    @Test
    fun `a batch Mimir rejects is dropped and later samples still arrive`() {
        prepare()
        val mimir = startMimir()
        val collector = startCollector()
        val url = "${ObservabilityBackends.baseUrl(collector, Constants.K8s.OTEL_HTTP_PORT)}/v1/metrics"

        val last =
            produceAcross(collector, "/v1/metrics", ::metricBody) {
                val stale = System.currentTimeMillis() - TOO_OLD.toMillis()
                val response =
                    ObservabilityBackends.send(
                        HttpRequest
                            .newBuilder(URI(url))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(metricBody(STALE_VALUE, stale)))
                            .build(),
                    )
                assertThat(response.statusCode()).describedAs("the collector accepts the stale sample").isEqualTo(HTTP_OK)
                Thread.sleep(AFTER_REJECT.toMillis())
            }

        val missing = missingAfterArrival(last) { samplesIn { mimir } }
        assertThat(missing)
            .describedAs(
                "of samples 1..$last sent around a rejected one, the ones missing from Mimir; collector:\n" +
                    ClusterCollector.exportFailures(collector),
            ).isEmpty()
        assertThat(collector.logs).describedAs("Mimir rejected the stale sample as too old").contains(TOO_OLD_ERROR)
    }

    @Test
    fun `a Loki restart loses no log line`() {
        prepare()
        var loki = startLoki()
        val collector = startCollector()

        val last =
            produceAcross(collector, "/v1/logs", ::logBody) {
                restartAfterOutage(loki, RETRY_LIMIT_OUTAGE) { startLoki().also { loki = it } }
            }

        val missing = missingAfterArrival(last) { linesIn { loki } }
        assertThat(missing)
            .describedAs(
                "of lines 1..$last sent, the ones missing from Loki after a ${RETRY_LIMIT_OUTAGE.seconds}s outage; collector:\n" +
                    ClusterCollector.exportFailures(collector),
            ).isEmpty()
    }

    @Test
    fun `a Tempo restart loses no span`() {
        prepare()
        var tempo = startTempo()
        val collector = startCollector()

        val last =
            produceAcross(collector, "/v1/traces", ::spanBody) {
                restartAfterOutage(tempo, RETRY_LIMIT_OUTAGE) { startTempo().also { tempo = it } }
            }

        val found = mutableSetOf<Int>()
        val missing = missingAfterArrival(last) { deadline -> tracesIn(tempo, last, found, deadline) }
        assertThat(missing)
            .describedAs(
                "of spans 1..$last sent, the ones missing from Tempo after a ${RETRY_LIMIT_OUTAGE.seconds}s outage; collector:\n" +
                    ClusterCollector.exportFailures(collector),
            ).isEmpty()
    }
}

private const val HTTP_OK = 200
private const val NANOS_PER_MILLI = 1_000_000L
private const val SPAN_ID_LENGTH = 16
private const val SPAN_NAME = "replay-span"
