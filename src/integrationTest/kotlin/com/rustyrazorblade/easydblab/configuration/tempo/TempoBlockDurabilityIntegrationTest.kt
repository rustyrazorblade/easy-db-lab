package com.rustyrazorblade.easydblab.configuration.tempo

import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Volume
import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.services.TemplateService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
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
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Runs Tempo at the image the cluster deploys ([TempoManifestBuilder.IMAGE]), with the configuration
 * the cluster renders, against S3 (LocalStack). It covers hypothesis H2 of the issue 967 design
 * (Decision 8) — traces lost because the WAL was an `emptyDir` and every deploy restarted Tempo — and
 * the shared-tenant layout. It does not establish the cause of the missing blocks on a real cluster,
 * which only a diagnosis on a running cluster can (task group 1 of the change). It proves:
 *
 * - blocks reach S3 while Tempo runs, under `observability/traces/<tenant>/`;
 * - a trace pushed 3s before Tempo is killed outright (no shutdown, no flush) reaches S3 after a
 *   restart on the same WAL volume: the live store has appended it to the WAL by then;
 * - a trace received just before a graceful stop — how Kubernetes restarts a pod — reaches S3 after
 *   the restart, because Tempo writes its live traces to the WAL on shutdown;
 * - two clusters in the same tenant, each with its own Tempo and WAL, write into the same
 *   `observability/traces/<tenant>/` directory at the same time and every block from each is kept.
 *
 * The WAL is a Docker volume shared by the successive containers, standing in for the control node's
 * hostPath. Two edits are made to the rendered configuration, for the harness only: the S3 endpoint
 * and credentials point at LocalStack, and the block cut is shortened from 5m to [TEST_BLOCK_CUT] so
 * the test does not wait five minutes per block. The production cut is asserted in the unit test.
 */
class TempoBlockDurabilityIntegrationTest : BaseKoinTest() {
    private companion object {
        const val TENANT = "acme"
        const val TEST_BLOCK_CUT = "45s"
        const val OTLP_HTTP_PORT = 4321
        const val WAL_MOUNT = "/var/tempo"
        val BLOCK_WAIT: Duration = Duration.ofMinutes(3)
        val POLL: Duration = Duration.ofSeconds(2)

        /**
         * How long the test waits after a push before killing Tempo. The configured live store appends
         * an idle trace to the WAL within `max_trace_idle` (1s) plus `flush_check_period` (1s); 3s
         * leaves a second of margin. Tempo's defaults (5s + 5s) would still hold the trace only in
         * memory at 3s, and the kill would lose it.
         */
        val WAL_APPEND_WAIT: Duration = Duration.ofSeconds(3)
    }

    private val s3 = SharedLocalStack.s3Client()
    private val docker = DockerClientFactory.instance().client()
    private val bucket = "tempo-durability-${UUID.randomUUID().toString().take(8)}"
    private val walVolume = newVolumeName()
    private val volumes = mutableListOf(walVolume)
    private val state =
        ClusterState(
            name = "tempo-it",
            versions = mutableMapOf(),
            s3Bucket = bucket,
            initConfig = InitConfig(region = SharedLocalStack.region(), tenant = TENANT),
        )
    private val tracesPrefix = ObservabilityStore.from(state).tracesPrefix()
    private val http = HttpClient.newHttpClient()
    private val containers = mutableListOf<GenericContainer<*>>()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also { whenever(it.load()).thenReturn(state) }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @AfterEach
    fun cleanUp() {
        containers.forEach { it.stop() }
        volumes.forEach { volume -> runCatching { docker.removeVolumeCmd(volume).exec() } }
    }

    private fun newVolumeName() = "tempo-wal-${UUID.randomUUID().toString().take(8)}"

    /** The cluster's rendered tempo.yaml, pointed at LocalStack with a shorter block cut. */
    private fun testConfig(): String {
        val rendered = TempoManifestBuilder(getKoin().get()).buildConfigMap().data.getValue("tempo.yaml")
        check(rendered.contains("max_block_duration: 5m")) { "rendered config no longer cuts at 5m" }
        val endpointLine = "      endpoint: s3.\${AWS_REGION}.amazonaws.com"
        check(rendered.contains(endpointLine)) { "rendered config no longer names the regional S3 endpoint" }
        return rendered
            .replace("max_block_duration: 5m", "max_block_duration: $TEST_BLOCK_CUT")
            .replace(
                endpointLine,
                """
                |      endpoint: host.testcontainers.internal:${SharedLocalStack.hostPort()}
                |      insecure: true
                |      forcepathstyle: true
                |      access_key: ${SharedLocalStack.accessKey()}
                |      secret_key: ${SharedLocalStack.secretKey()}
                """.trimMargin(),
            )
    }

    /** Starts Tempo with [config] on the WAL [volume]; each cluster has its own. */
    private fun startTempo(
        config: String,
        volume: String = walVolume,
    ): GenericContainer<*> {
        val tempo =
            GenericContainer(TempoManifestBuilder.IMAGE)
                .withCreateContainerCmdModifier { cmd ->
                    cmd.withUser("root")
                    cmd.hostConfig?.withBinds(Bind(volume, Volume(WAL_MOUNT)))
                }.withCopyToContainer(Transferable.of(config), "/etc/tempo/tempo.yaml")
                .withEnv("S3_BUCKET", bucket)
                .withEnv("AWS_REGION", SharedLocalStack.region())
                .withEnv("TRACES_S3_PREFIX", tracesPrefix)
                .withEnv("CLUSTER_NAME", state.clusterLabelName())
                .withCommand("-config.file=/etc/tempo/tempo.yaml", "-config.expand-env=true")
                .withExposedPorts(Constants.K8s.TEMPO_PORT, OTLP_HTTP_PORT)
                .waitingFor(Wait.forHttp("/ready").forPort(Constants.K8s.TEMPO_PORT).withStartupTimeout(Duration.ofMinutes(2)))
        tempo.start()
        containers.add(tempo)
        return tempo
    }

    private fun pushTrace(tempo: GenericContainer<*>) {
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val nowNanos = System.currentTimeMillis() * 1_000_000
        val body =
            """
            {"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"tempo-it"}}]},
            "scopeSpans":[{"spans":[{"traceId":"$traceId","spanId":"${traceId.take(16)}","name":"probe","kind":1,
            "startTimeUnixNano":"$nowNanos","endTimeUnixNano":"${nowNanos + 1_000_000}"}]}]}]}
            """.trimIndent()
        val request =
            HttpRequest
                .newBuilder(URI("http://${tempo.host}:${tempo.getMappedPort(OTLP_HTTP_PORT)}/v1/traces"))
                .header("Content-Type", "application/json")
                .header(Constants.Observability.TENANT_HEADER, TENANT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
    }

    /** Every block's meta.json key under the tenant's traces directory. */
    private fun blockMetas(): List<String> =
        s3
            .listObjectsV2(
                ListObjectsV2Request
                    .builder()
                    .bucket(bucket)
                    .prefix("$tracesPrefix/$TENANT/")
                    .build(),
            ).contents()
            .map { it.key() }
            .filter { it.endsWith("/meta.json") }

    /** Traces held by every block Tempo has written for the tenant, read from the blocks' meta.json. */
    private fun tracesInS3(): Int =
        blockMetas()
            .sumOf { key ->
                val meta =
                    s3.getObject(
                        GetObjectRequest
                            .builder()
                            .bucket(bucket)
                            .key(key)
                            .build(),
                        ResponseTransformer.toBytes(),
                    )
                Json
                    .parseToJsonElement(meta.asUtf8String())
                    .jsonObject
                    .getValue("totalObjects")
                    .jsonPrimitive
                    .int
            }

    /** Waits up to [BLOCK_WAIT] for S3 to hold [expected] traces, and fails with what it holds. */
    private fun awaitTracesInS3(expected: Int) {
        val deadline = System.nanoTime() + BLOCK_WAIT.toNanos()
        var found = tracesInS3()
        while (found < expected && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis())
            found = tracesInS3()
        }
        assertThat(found).describedAs("traces in s3://$bucket/$tracesPrefix/$TENANT/").isGreaterThanOrEqualTo(expected)
    }

    @Test
    fun `blocks reach S3 while Tempo runs, and traces survive a kill and a restart`() {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
        docker.createVolumeCmd().withName(walVolume).exec()
        val config = testConfig()

        // Blocks reach S3 while Tempo runs.
        val first = startTempo(config)
        pushTrace(first)
        awaitTracesInS3(1)

        // A trace in the WAL but not in a block survives a kill with no shutdown at all.
        pushTrace(first)
        Thread.sleep(WAL_APPEND_WAIT.toMillis())
        assertThat(tracesInS3()).describedAs("the second trace must not be uploaded before the kill").isEqualTo(1)
        docker.killContainerCmd(first.containerId).exec()
        val second = startTempo(config)
        awaitTracesInS3(2)

        // A trace received just before a graceful stop survives the restart.
        pushTrace(second)
        second.dockerClient
            .stopContainerCmd(second.containerId)
            .withTimeout(30)
            .exec()
        startTempo(config)
        awaitTracesInS3(3)
    }

    @Test
    fun `two clusters in one tenant write traces into the same directory and every block is kept`() {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
        val otherWal = newVolumeName().also { volumes.add(it) }
        docker.createVolumeCmd().withName(walVolume).exec()
        docker.createVolumeCmd().withName(otherWal).exec()
        val config = testConfig()

        // Two clusters, same bucket, prefix and tenant, each with its own Tempo and WAL, writing at once.
        val first = startTempo(config, walVolume)
        val second = startTempo(config, otherWal)
        repeat(2) { pushTrace(first) }
        repeat(3) { pushTrace(second) }

        awaitTracesInS3(5)
        assertThat(blockMetas()).describedAs("a block from each cluster").hasSizeGreaterThanOrEqualTo(2)
        // Exactly five: neither Tempo rewrote, merged or deleted a block the other wrote.
        assertThat(tracesInS3()).describedAs("no trace written twice or lost").isEqualTo(5)
    }
}
