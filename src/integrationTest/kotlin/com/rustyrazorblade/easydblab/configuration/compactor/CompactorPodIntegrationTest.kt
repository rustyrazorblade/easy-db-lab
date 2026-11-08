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
 * - **Compaction keeps every sample.** Mimir A ships one-minute blocks for tenant `acme`; the pod's
 *   gated Mimir compactor merges them; Mimir B, on an empty volume, answers every sample from the
 *   store alone. The samples are four hours old, so retention would show.
 *
 * The harness's edits: the S3 endpoint and credentials point at LocalStack (in the configuration
 * files, and `S3_ENDPOINT` for the agent), the agent's timing is shortened for the failover so it
 * fits a test, and Mimir's compactor gets `-compactor.first-level-compaction-wait-period=0`, as the
 * real one waits 2 minutes after upload before it merges a one-minute block. The timing still holds
 * `LeaseTiming`'s margin.
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

    private fun awaitUntil(
        within: Duration,
        what: String,
        detail: () -> String,
        done: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + within.toNanos()
        while (!done() && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis())
        }
        assertThat(done()).describedAs { "$what within $within: ${detail()}" }.isTrue()
    }
}
