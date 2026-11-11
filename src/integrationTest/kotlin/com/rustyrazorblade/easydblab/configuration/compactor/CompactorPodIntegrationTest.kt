package com.rustyrazorblade.easydblab.configuration.compactor

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.PrometheusRemoteWrite
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.compactorlease.LeaseRecord
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.PodSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.DockerClientFactory
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Runs the `account-compactor` pod from the real [CompactorManifestBuilder] against S3 (LocalStack),
 * with the real lease agent jar, the real gate, and the real Grafana images, and proves what the
 * compactor must keep true for the shared store.
 *
 * Scenarios:
 * - **A standby runs no compactor.** Two pods share one store: exactly one holds the lease and runs
 *   its compactors; the other's lease agent is ready and its compactors never start.
 * - **A standby takes over after expiry.** When the holder's pod dies without a word, the standby
 *   takes the lease only after it has seen one version for the TTL, and then starts its compactors.
 *   Each holder's four compactors answer `/ready`, and no gate reports an exit for 30 s after.
 * - **Compaction keeps every sample, log line and trace.** Mimir A ships one-minute blocks for
 *   tenant `acme`; the pod's gated Mimir compactor merges them; Mimir B, on an empty volume, answers
 *   every sample from the store alone. The samples are four hours old, so retention would show. Two
 *   Lokis and two Tempos, as two clusters, each ship their own index file and blocks; the gated Loki
 *   compactor merges the index files into one and the Tempo worker writes a compacted block; a new
 *   Loki and a new Tempo, on empty volumes, answer every line and every trace.
 *
 * The harness's edits: the S3 endpoint and credentials point at LocalStack (in the configuration
 * files, and `S3_ENDPOINT` for the agent), the agent's timing is shortened for the failover so it
 * fits a test, Mimir's compactor gets `-compactor.first-level-compaction-wait-period=0`, as the
 * real one waits 2 minutes after upload before it merges a one-minute block, and Loki's compactor
 * gets a 30 s interval, as the real one first runs 10 minutes after it starts. The timing still
 * holds `LeaseTiming`'s margin.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompactorPodIntegrationTest {
    private companion object {
        const val TENANT = "acme"
        const val METRIC = "edl_compactor_probe"
        const val SAMPLE_STEP_SECONDS = 60L
        const val TWO_HOURS_SECONDS = 7200L
        const val SAMPLE_COUNT = 130
        const val FIRST_LEVEL_WAIT_OFF = "-compactor.first-level-compaction-wait-period=0"
        val STARTUP: Duration = Duration.ofMinutes(3)
        val COMPACTION: Duration = Duration.ofMinutes(8)
        val SHIPPING: Duration = Duration.ofMinutes(8)
        val POLL: Duration = Duration.ofSeconds(2)

        /** How long every compactor must stay up, with no exit reported by its gate, once it is ready. */
        val STABLE: Duration = Duration.ofSeconds(30)
        const val READY_PATH = "/ready"
        const val LOG_DETAIL = 3000
        val HTTP_PORT_FLAG = Regex("-server\\.http-listen-port=(\\d+)")

        /** Lines each of two Lokis writes, so the store holds two index files for one table. */
        const val LINES_PER_LOKI = 20

        /** Loki's compactor first runs one interval after it starts; the shipped 10 minutes would not fit a test. */
        const val LOKI_COMPACTION_INTERVAL = "-compactor.compaction-interval=30s"

        /** How long a graceful stop of Loki or Tempo may take to flush and upload. */
        const val GRACEFUL_STOP_SECONDS = 120
        val UPLOAD: Duration = Duration.ofMinutes(4)

        /** A short timing for the failover; the margin is 4 + 1 + 3 = 8 s, under the 15 s TTL. */
        val FAST_TIMING =
            mapOf(
                "LEASE_TTL_SECONDS" to "15",
                "LEASE_RENEW_INTERVAL_SECONDS" to "2",
                "LEASE_RENEW_DEADLINE_SECONDS" to "4",
                "LEASE_STANDBY_POLL_SECONDS" to "2",
                "LEASE_GATE_POLL_SECONDS" to "1",
                "LEASE_GATE_GRACE_SECONDS" to "3",
            )
    }

    private val s3 = SharedLocalStack.s3Client()
    private val docker = DockerClientFactory.instance().client()
    private val bucket = "compactor-pod-it-${UUID.randomUUID().toString().take(8)}"
    private val pods = mutableListOf<DockerPod>()
    private val containers = mutableListOf<GenericContainer<*>>()
    private val volumes = mutableListOf<String>()
    private val blockRanges = mutableMapOf<String, LongRange>()
    private lateinit var templateService: TemplateService

    private val agentJar =
        Path.of(
            checkNotNull(System.getProperty("easydblab.test.compactorLeaseJar")) {
                "the lease agent jar path is not set"
            },
        )
    private val remoteJar by lazy { CompactorAgentJarFile(agentJar).remote() }

    @BeforeAll
    fun prepare() {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
        val clusterStateManager =
            mock<ClusterStateManager>().also {
                whenever(it.load()).thenReturn(ClusterState(name = "compactor-it", versions = mutableMapOf()))
            }
        val user =
            User(
                region = SharedLocalStack.region(),
                email = "test@example.com",
                keyName = "",
                awsProfile = "",
                awsAccessKey = "",
                awsSecret = "",
            )
        templateService = TemplateService(clusterStateManager, user)
    }

    @AfterEach
    fun stopPods() {
        pods.forEach { it.close() }
        pods.clear()
    }

    @AfterAll
    fun cleanUp() {
        containers.forEach { it.stop() }
        volumes.forEach { runCatching { docker.removeVolumeCmd(it).exec() } }
    }

    private fun builder() = CompactorManifestBuilder(templateService)

    private fun podSpec(): PodSpec =
        builder()
            .buildDeployment(remoteJar, SharedLocalStack.region())
            .spec.template.spec

    /** The pod's configuration files, pointed at LocalStack on its Docker network. */
    private fun configFiles(): Map<String, String> {
        val local = "host.testcontainers.internal:${SharedLocalStack.hostPort()}"
        val onNetwork = SharedLocalStack.networkAddress()
        return builder().buildConfigMap().data.mapValues { (file, content) ->
            when (file) {
                MimirManifestBuilder.CONFIG_FILE -> ObservabilityBackends.mimirConfig(content)
                LokiManifestBuilder.CONFIG_FILE -> ObservabilityBackends.lokiConfig(content)
                CompactorManifestBuilder.TEMPO_BACKEND_FILE -> ObservabilityBackends.tempoConfig(content)
                else -> content
            }.replace(local, onNetwork)
        }
    }

    private fun pod(
        cluster: String,
        timing: Map<String, String> = emptyMap(),
        extraArgs: Map<String, List<String>> = emptyMap(),
    ): DockerPod =
        DockerPod(
            name = "$cluster-${UUID.randomUUID().toString().take(4)}",
            spec = podSpec(),
            configFiles = configFiles(),
            clusterConfig = mapOf("s3_bucket" to bucket, "cluster_name" to cluster),
            hostFiles = mapOf(CompactorManifestBuilder.AGENT_VOLUME to agentJar),
            hostDirectoryOwners = Scratch.entries.associate { it.volume to it.owner },
            extraEnv =
                timing +
                    mapOf(
                        "S3_ENDPOINT" to "http://${SharedLocalStack.networkAddress()}",
                        "AWS_ACCESS_KEY_ID" to SharedLocalStack.accessKey(),
                        "AWS_SECRET_ACCESS_KEY" to SharedLocalStack.secretKey(),
                    ),
            extraArgs = extraArgs,
        ).also { pods += it }.start()

    private fun lease(): LeaseRecord? =
        runCatching {
            s3
                .getObject(
                    GetObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(LeaseRecord.KEY)
                        .build(),
                    ResponseTransformer.toBytes(),
                ).asUtf8String()
        }.getOrNull()?.let { LeaseRecord.fromJson(it) }

    private val compactorNames =
        listOf(
            CompactorManifestBuilder.MIMIR_CONTAINER,
            CompactorManifestBuilder.LOKI_CONTAINER,
            CompactorManifestBuilder.TEMPO_SCHEDULER_CONTAINER,
            CompactorManifestBuilder.TEMPO_WORKER_CONTAINER,
        )

    /** How many of [pod]'s gates have started their binary. */
    private fun startedCompactors(pod: DockerPod): Int = compactorNames.count { pod.logs(it).contains("started pid") }

    /** The HTTP port [container] serves `/ready` on: its `-server.http-listen-port`, or its config file's. */
    private fun httpPort(container: String): Int =
        podSpec()
            .containers
            .single { it.name == container }
            .args
            .firstNotNullOfOrNull {
                HTTP_PORT_FLAG
                    .matchEntire(it)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
            }
            ?: when (container) {
                CompactorManifestBuilder.MIMIR_CONTAINER -> Constants.K8s.MIMIR_HTTP_PORT
                CompactorManifestBuilder.LOKI_CONTAINER -> Constants.K8s.LOKI_HTTP_PORT
                else -> Constants.K8s.TEMPO_PORT
            }

    /**
     * Waits until every compactor in [pod] answers `/ready` with 200, then watches them for
     * [STABLE]: a gate that reports an exit there is a compactor that crash-loops, which a count of
     * started gates alone would let pass. After the window each must answer `/ready` again, and
     * still no gate may have reported an exit.
     */
    private fun awaitCompactorsReady(pod: DockerPod) {
        compactorNames.forEach { name ->
            val port = httpPort(name)
            awaitUntil(STARTUP, "$name answers /ready on port $port", { pod.logs(name).takeLast(LOG_DETAIL) }) {
                pod.answers200(port, READY_PATH)
            }
        }
        Thread.sleep(STABLE.toMillis())
        assertThat(compactorNames).allSatisfy { name ->
            assertThat(pod.logs(name)).describedAs("$name's gate log").doesNotContain("the compactor exited")
            assertThat(pod.isRunning(name)).describedAs("$name's gate still runs\n${pod.logs(name)}").isTrue()
        }
        // One probe can miss under a loaded Docker host; a compactor that is up answers again.
        compactorNames.forEach { name ->
            val port = httpPort(name)
            awaitUntil(STARTUP, "$name still answers /ready on port $port", { pod.logs(name).takeLast(LOG_DETAIL) }) {
                pod.answers200(port, READY_PATH)
            }
        }
        assertThat(compactorNames).allSatisfy { name ->
            assertThat(pod.logs(name)).describedAs("$name's gate log").doesNotContain("the compactor exited")
        }
    }

    private fun ready(pod: DockerPod): Boolean = pod.exists(CompactorManifestBuilder.LEASE_CONTAINER, "/lease/ready")

    private fun diagnostics(vararg pods: DockerPod): String =
        pods.joinToString("\n") { pod ->
            (
                listOf(
                    CompactorManifestBuilder.LEASE_CONTAINER,
                ) + compactorNames
            ).joinToString("\n") { "--- $it\n${pod.logs(it).takeLast(1500)}" }
        }

    @Test
    fun `of two pods on one store one compacts, the other is ready and idle, and it takes over only after the TTL`() {
        val a = pod("lab-a", FAST_TIMING)
        awaitUntil(STARTUP, "pod A holds the lease and starts all four compactors", { diagnostics(a) }) {
            lease()?.holder?.startsWith("lab-a/") == true && startedCompactors(a) == compactorNames.size
        }
        awaitCompactorsReady(a)
        val b = pod("lab-b", FAST_TIMING)
        awaitUntil(STARTUP, "pod B's lease agent is ready", { diagnostics(b) }) { ready(b) }

        // B stands by through more than a whole TTL while A renews.
        Thread.sleep(Duration.ofSeconds(20).toMillis())
        assertThat(lease()?.holder).describedAs(diagnostics(a, b)).startsWith("lab-a/")
        assertThat(startedCompactors(b)).describedAs("compactors started on the standby\n${diagnostics(b)}").isZero()
        assertThat(compactorNames).allSatisfy { assertThat(b.isRunning(it)).describedAs("$it waits under its gate").isTrue() }
        assertThat(b.exists(CompactorManifestBuilder.MIMIR_CONTAINER, "/lease/valid-until")).isFalse()

        // A's node goes away without a word: no SIGTERM, no release.
        val sequenceAtStop = checkNotNull(lease()).sequence
        val stoppedAt = Instant.now()
        a.kill()
        awaitUntil(Duration.ofMinutes(2), "pod B takes the lease and starts all four compactors", { diagnostics(b) }) {
            lease()?.holder?.startsWith("lab-b/") == true && startedCompactors(b) == compactorNames.size
        }
        val tookOver = Duration.between(stoppedAt, Instant.now())

        assertThat(tookOver).describedAs("B took over before a whole TTL had passed").isGreaterThanOrEqualTo(Duration.ofSeconds(15))
        assertThat(checkNotNull(lease()).sequence).isGreaterThan(sequenceAtStop)
        assertThat(b.logs(CompactorManifestBuilder.LEASE_CONTAINER)).contains("taken over from lab-a/")
        awaitCompactorsReady(b)
    }

    @Test
    fun `Mimir blocks compacted by the account compactor keep every sample, and a new Mimir reads them from the store`() {
        val rendered = MimirManifestBuilder(templateService).buildConfigMap().data.getValue(MimirManifestBuilder.CONFIG_FILE)
        val clusterConfig = ObservabilityBackends.mimirConfig(rendered)
        val mimirA =
            ObservabilityBackends.startMimir(clusterConfig, volume(), bucket, Constants.Observability.METRICS_ROOT).also {
                containers +=
                    it
            }

        // Four-hour-old samples across a complete two-hour range, then shipped as one-minute blocks.
        val start = (Instant.now().epochSecond - 2 * TWO_HOURS_SECONDS) / TWO_HOURS_SECONDS * TWO_HOURS_SECONDS
        val written = (0 until SAMPLE_COUNT).map { (start + it * SAMPLE_STEP_SECONDS) * 1000 }
        write(mimirA, written)
        val countedAt = start + SAMPLE_COUNT * SAMPLE_STEP_SECONDS
        flush(mimirA)
        awaitUntil(SHIPPING, "every written sample in a block in the store", {
            "${written.count { ts -> shippedRanges().none { ts in it } }} of ${written.size} samples not yet in a shipped block"
        }) { written.all { ts -> shippedRanges().any { ts in it } } }
        val shipped = blockMetas().size
        val before = samples(mimirA, countedAt)
        assertThat(before).describedAs("samples Mimir A answers before compaction").isEqualTo(SAMPLE_COUNT)

        val compactor = pod("lab-c", extraArgs = mapOf(CompactorManifestBuilder.MIMIR_CONTAINER to listOf(FIRST_LEVEL_WAIT_OFF)))
        awaitUntil(COMPACTION, "the bucket index and a merged block for $TENANT", {
            "${objectKeys().size} objects\n${compactor.logs(CompactorManifestBuilder.MIMIR_CONTAINER)}"
        }) { objectKeys().contains("${tenantRoot()}bucket-index.json.gz") && blockMetas().any { compactionLevel(it) > 1 } }
        mimirA.stop()

        val mimirB =
            ObservabilityBackends.startMimir(clusterConfig, volume(), bucket, Constants.Observability.METRICS_ROOT).also {
                containers +=
                    it
            }
        awaitUntil(STARTUP, "Mimir B answers $before samples from the store", { "answered ${samples(mimirB, countedAt)}" }) {
            samples(mimirB, countedAt) == before
        }
        assertThat(samples(mimirB, countedAt)).isEqualTo(before)
        assertThat(shipped).describedAs("one-minute blocks Mimir A shipped").isGreaterThan(1)
        assertThat(compactorNames).allSatisfy { assertThat(compactor.isRunning(it)).describedAs("$it\n${compactor.logs(it)}").isTrue() }
    }

    @Test
    fun `log lines and traces compacted by the account compactor are all answered by a new Loki and Tempo from the store`() {
        val lokiConfig =
            ObservabilityBackends.lokiConfig(
                LokiManifestBuilder(templateService).buildConfigMap().data.getValue(LokiManifestBuilder.CONFIG_FILE),
            )
        val tempoConfig =
            ObservabilityBackends.tempoConfig(
                TempoManifestBuilder(templateService).buildConfigMap().data.getValue("tempo.yaml"),
            )

        // Two Lokis, as two clusters, each ship an index file for today's table, and two Tempos each
        // ship blocks: the store holds more than one of each for the compactor to merge.
        val lines =
            listOf("lab-x", "lab-y").flatMap { cluster ->
                val loki = startLoki(lokiConfig, cluster)
                val written = (1..LINES_PER_LOKI).map { "line $it from $cluster ${UUID.randomUUID()}" }
                written.forEach { pushLine(loki, cluster, it) }
                stopGracefully(loki)
                written
            }
        // Tempo compacts only blocks in one compaction window (an hour, by each block's end time), so
        // every span ends at one instant: the two clusters' blocks can never fall either side of an
        // hour and leave the worker nothing to merge. The instant is taken once both run, as Tempo
        // clamps a span's time to within 2 minutes of when it arrives.
        val tempos = listOf("lab-x", "lab-y").associateWith { startTempo(tempoConfig, it) }
        val spanTime = Instant.now()
        val traceIds = tempos.values.flatMap { tempo -> List(2) { pushTrace(tempo, spanTime) } }
        awaitUntil(UPLOAD, "both clusters' traces in Tempo blocks in the store", { "${traceBlocks().size} blocks" }) {
            traceBlocks().sumOf { totalObjects(it) } >= traceIds.size
        }
        tempos.values.forEach { stopGracefully(it) }
        val indexFilesBefore = lokiIndexFiles()
        val traceBlocksBefore = traceBlocks()
        assertThat(indexFilesBefore)
            .describedAs("Loki index files before compaction; the store holds ${keys("${Constants.Observability.LOGS_ROOT}/")}")
            .hasSizeGreaterThan(1)
        assertThat(traceBlocksBefore)
            .describedAs("Tempo blocks before compaction; the store holds ${keys("${Constants.Observability.TRACES_ROOT}/")}")
            .hasSizeGreaterThan(1)

        val compactor = pod("lab-c", extraArgs = mapOf(CompactorManifestBuilder.LOKI_CONTAINER to listOf(LOKI_COMPACTION_INTERVAL)))
        awaitUntil(COMPACTION, "Loki's index files merged into one and a Tempo block of level 1 or more", {
            "index ${lokiIndexFiles()}\nblocks ${traceBlocks().map { it to compactionLevelOf(it) }}\n" +
                "${compactor.logs(CompactorManifestBuilder.LOKI_CONTAINER).takeLast(LOG_DETAIL)}\n" +
                compactor.logs(CompactorManifestBuilder.TEMPO_WORKER_CONTAINER).takeLast(LOG_DETAIL)
        }) {
            val index = lokiIndexFiles()
            index.size == 1 && index.none { it in indexFilesBefore } && traceBlocks().any { compactionLevelOf(it) > 0 }
        }
        assertThat(compactorNames).allSatisfy { assertThat(compactor.isRunning(it)).describedAs("$it\n${compactor.logs(it)}").isTrue() }
        compactor.close()

        val reader = startLoki(lokiConfig, "lab-z")
        awaitUntil(STARTUP, "the new Loki answers all ${lines.size} lines from the store", {
            "answered ${logLines(reader).size}"
        }) { logLines(reader).toSet() == lines.toSet() }
        assertThat(logLines(reader)).containsExactlyInAnyOrderElementsOf(lines)

        val tempoReader = startTempo(tempoConfig, "lab-z")
        assertThat(traceIds).allSatisfy { id ->
            awaitUntil(STARTUP, "the new Tempo answers trace $id from the store", { "status ${traceStatus(tempoReader, id)}" }) {
                traceStatus(tempoReader, id) == 200
            }
        }
    }

    private fun startLoki(
        config: String,
        cluster: String,
    ): GenericContainer<*> =
        ObservabilityBackends
            .startLoki(config, volume(), bucket, Constants.Observability.LOGS_ROOT, TENANT, cluster)
            .also { containers += it }

    private fun startTempo(
        config: String,
        cluster: String,
    ): GenericContainer<*> =
        ObservabilityBackends
            .startTempo(config, volume(), bucket, Constants.Observability.TRACES_ROOT, cluster)
            .also { containers += it }

    /** Stops [container] with SIGTERM and time to flush, as a pod stop does; Loki then uploads its index. */
    private fun stopGracefully(container: GenericContainer<*>) {
        docker.stopContainerCmd(container.containerId).withTimeout(GRACEFUL_STOP_SECONDS).exec()
        containers -= container
        container.stop()
    }

    private fun pushLine(
        loki: GenericContainer<*>,
        cluster: String,
        line: String,
    ) {
        val nanos = System.currentTimeMillis() * 1_000_000
        val body =
            """
            {"resourceLogs":[{"resource":{"attributes":[{"key":"cluster","value":{"stringValue":"$cluster"}},
              {"key":"host.name","value":{"stringValue":"db0"}}]},
             "scopeLogs":[{"logRecords":[{"timeUnixNano":"$nanos","body":{"stringValue":"$line"}}]}]}]}
            """.trimIndent()
        val response =
            ObservabilityBackends.send(
                HttpRequest
                    .newBuilder(URI("${ObservabilityBackends.baseUrl(loki, Constants.K8s.LOKI_HTTP_PORT)}/otlp/v1/logs"))
                    .header("Content-Type", "application/json")
                    .header(Constants.Observability.TENANT_HEADER, TENANT)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
            )
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(204)
    }

    /** Every line [loki] answers for the tenant over the last day; empty while it cannot answer. */
    private fun logLines(loki: GenericContainer<*>): List<String> {
        val query = URLEncoder.encode("""{cluster=~"lab-.+"}""", Charsets.UTF_8)
        val response =
            runCatching {
                ObservabilityBackends.get(
                    "${ObservabilityBackends.baseUrl(loki, Constants.K8s.LOKI_HTTP_PORT)}/loki/api/v1/query_range" +
                        "?query=$query&since=24h&limit=5000",
                    TENANT,
                )
            }.getOrNull()
        if (response?.statusCode() != 200) return emptyList()
        return Json
            .parseToJsonElement(response.body())
            .jsonObject
            .getValue("data")
            .jsonObject
            .getValue("result")
            .jsonArray
            .flatMap { stream ->
                stream.jsonObject
                    .getValue("values")
                    .jsonArray
                    .map { it.jsonArray[1].jsonPrimitive.content }
            }
    }

    /** Pushes one span of a new trace to [tempo], starting at [at], and returns its trace ID, in hex. */
    private fun pushTrace(
        tempo: GenericContainer<*>,
        at: Instant,
    ): String {
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val nowNanos = at.toEpochMilli() * 1_000_000
        val body =
            """
            {"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"compactor-it"}}]},
            "scopeSpans":[{"spans":[{"traceId":"$traceId","spanId":"${traceId.take(16)}","name":"probe","kind":1,
            "startTimeUnixNano":"$nowNanos","endTimeUnixNano":"${nowNanos + 1_000_000}"}]}]}]}
            """.trimIndent()
        val response =
            ObservabilityBackends.send(
                HttpRequest
                    .newBuilder(URI("${ObservabilityBackends.baseUrl(tempo, ObservabilityBackends.TEMPO_OTLP_HTTP_PORT)}/v1/traces"))
                    .header("Content-Type", "application/json")
                    .header(Constants.Observability.TENANT_HEADER, TENANT)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
            )
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
        return traceId
    }

    /** The HTTP status of a lookup of trace [id] in [tempo]; -1 while it cannot answer. */
    private fun traceStatus(
        tempo: GenericContainer<*>,
        id: String,
    ): Int =
        runCatching {
            ObservabilityBackends
                .get("${ObservabilityBackends.baseUrl(tempo, Constants.K8s.TEMPO_PORT)}/api/v2/traces/$id", TENANT)
                .statusCode()
        }.getOrDefault(-1)

    private fun keys(prefix: String): Set<String> =
        s3
            .listObjectsV2Paginator(
                ListObjectsV2Request
                    .builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .build(),
            ).contents()
            .map { it.key() }
            .toSet()

    /**
     * The tenant's index files in every daily table under `loki/index/`. An ingester uploads
     * `index_<day>/<ts>-<tenant>.<cluster>-<n>.tsdb.gz`; the compactor writes the merged file under
     * `index_<day>/<tenant>/`.
     */
    private fun lokiIndexFiles(): Set<String> =
        keys("${Constants.Observability.LOGS_ROOT}/index/")
            .filter { it.endsWith(".gz") && ("/$TENANT/" in it || "-$TENANT." in it.substringAfterLast('/')) }
            .toSet()

    /** Every Tempo block's meta.json key for the tenant. */
    private fun traceBlocks(): List<String> = keys("${Constants.Observability.TRACES_ROOT}/$TENANT/").filter { it.endsWith("/meta.json") }

    private fun totalObjects(metaKey: String): Int = metaJson(metaKey).getValue("totalObjects").jsonPrimitive.int

    private fun compactionLevelOf(metaKey: String): Int = metaJson(metaKey)["compactionLevel"]?.jsonPrimitive?.int ?: 0

    private fun volume(): String =
        "compactor-pod-it-${UUID.randomUUID().toString().take(8)}".also {
            docker.createVolumeCmd().withName(it).exec()
            volumes += it
        }

    private fun tenantRoot() = "${Constants.Observability.METRICS_ROOT}/$TENANT/"

    private fun objectKeys(): Set<String> =
        s3
            .listObjectsV2Paginator(
                ListObjectsV2Request
                    .builder()
                    .bucket(bucket)
                    .prefix(tenantRoot())
                    .build(),
            ).contents()
            .map { it.key() }
            .toSet()

    private fun blockMetas(): List<String> = objectKeys().filter { it.endsWith("/meta.json") }

    private fun metaJson(metaKey: String) =
        Json
            .parseToJsonElement(
                s3
                    .getObject(
                        GetObjectRequest
                            .builder()
                            .bucket(bucket)
                            .key(metaKey)
                            .build(),
                        ResponseTransformer.toBytes(),
                    ).asUtf8String(),
            ).jsonObject

    private fun compactionLevel(metaKey: String): Int =
        metaJson(metaKey)
            .getValue("compaction")
            .jsonObject
            .getValue("level")
            .jsonPrimitive.int

    private fun shippedRanges(): List<LongRange> =
        blockMetas().map { key ->
            blockRanges.getOrPut(key) {
                val meta = metaJson(key)
                // A block's maxTime is exclusive.
                meta.getValue("minTime").jsonPrimitive.long until meta.getValue("maxTime").jsonPrimitive.long
            }
        }

    private fun url(mimir: GenericContainer<*>) = ObservabilityBackends.baseUrl(mimir, Constants.K8s.MIMIR_HTTP_PORT)

    private fun write(
        mimir: GenericContainer<*>,
        timestampsMillis: List<Long>,
    ) {
        timestampsMillis.forEachIndexed { index, at ->
            val body =
                PrometheusRemoteWrite.body(
                    listOf(PrometheusRemoteWrite.Series(mapOf("__name__" to METRIC, "cluster" to "lab-a"), index.toDouble(), at)),
                )
            val response =
                ObservabilityBackends.send(
                    HttpRequest
                        .newBuilder(URI("${url(mimir)}/api/v1/push"))
                        .header("Content-Type", "application/x-protobuf")
                        .header("Content-Encoding", "snappy")
                        .header("X-Prometheus-Remote-Write-Version", "0.1.0")
                        .header(Constants.Observability.TENANT_HEADER, TENANT)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                )
            assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
        }
    }

    private fun flush(mimir: GenericContainer<*>) {
        val response =
            ObservabilityBackends.send(
                HttpRequest.newBuilder(URI("${url(mimir)}/ingester/flush?wait=true")).POST(HttpRequest.BodyPublishers.noBody()).build(),
            )
        assertThat(response.statusCode()).describedAs(response.body()).isIn(200, 204)
    }

    /** The number of the probe's samples in the six hours up to [atSeconds]; 0 while Mimir cannot answer. */
    private fun samples(
        mimir: GenericContainer<*>,
        atSeconds: Long,
    ): Int {
        val query = URLEncoder.encode("count_over_time($METRIC[6h])", Charsets.UTF_8)
        val response = ObservabilityBackends.get("${url(mimir)}/prometheus/api/v1/query?query=$query&time=$atSeconds", TENANT)
        if (response.statusCode() != 200) return 0
        return Json
            .parseToJsonElement(response.body())
            .jsonObject
            .getValue("data")
            .jsonObject
            .getValue("result")
            .jsonArray
            .sumOf {
                it.jsonObject
                    .getValue("value")
                    .jsonArray[1]
                    .jsonPrimitive.content
                    .toDouble()
                    .toInt()
            }
    }

    /**
     * Polls [done] until it holds or [within] has passed, and asserts the last result it saw. It
     * never asks again after the loop: a second check can see something else, and fail a wait that
     * succeeded.
     */
    private fun awaitUntil(
        within: Duration,
        what: String,
        detail: () -> String,
        done: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + within.toNanos()
        var met = done()
        while (!met && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis())
            met = done()
        }
        assertThat(met).describedAs { "$what within $within: ${detail()}" }.isTrue()
    }
}
