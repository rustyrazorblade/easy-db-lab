package com.rustyrazorblade.easydblab.services

import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Volume
import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.ContainerObservabilityHttp
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.PrometheusRemoteWrite
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.services.aws.S3ObjectStore
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.DockerClientFactory
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Runs the pre-teardown flush against the Loki and Mimir images the cluster deploys, with their
 * rendered configuration, on S3 (LocalStack). Docker volumes stand in for the control node's
 * hostPaths; a helper container mounts them at the node's paths and runs the flush's node commands
 * (SSH on a cluster); stopping and killing containers stands in for scaling to 0, and a backend's
 * `/ready` for its Kubernetes readiness. Every failure stops the flush where it is: nothing is
 * started again (owner decision, 2026-09-26). It proves:
 *
 * - a flush puts Loki's chunks and index (a backdated table too) and Mimir's head, as blocks, in S3;
 * - a Loki that cannot reach S3 times out at the shutdown, and the same Loki keeps running, not
 *   replaced, with Mimir untouched;
 * - a Loki killed before it builds its index fails the write-ahead check and stays at 0, its
 *   write-ahead data still on the node;
 * - an index file missing from S3 fails the S3 check;
 * - a local index the check cannot find, after the shutdown flushed chunks, fails the flush;
 * - a node listing that fails (sudo refused) fails the flush with the node's error;
 * - a Mimir whose blocks do not reach S3 fails the S3 check with Loki at 0 and Mimir's ingester
 *   stopped, nothing restarted; a re-run finds them so and stops before touching either.
 */
class TeardownFlushIntegrationTest : BaseKoinTest() {
    private companion object {
        const val TENANT = "acme"
        const val CLUSTER = "lab-f1"
        const val HELPER_IMAGE = "ubuntu:24.04"
        const val LOGS_PREFIX = "observability/logs"
        val POLL: Duration = Duration.ofSeconds(2)
    }

    private val s3 = SharedLocalStack.s3Client()
    private val docker = DockerClientFactory.instance().client()
    private val bucket = "flush-it-${UUID.randomUUID().toString().take(8)}"
    private val lokiVolume = "flush-loki-${UUID.randomUUID().toString().take(8)}"
    private val mimirVolume = "flush-mimir-${UUID.randomUUID().toString().take(8)}"
    private val control = ClusterHost("127.0.0.1", "10.0.0.1", "control0", "us-west-2a")
    private val state =
        ClusterState(
            name = "lab",
            clusterId = "f1",
            versions = mutableMapOf(),
            s3Bucket = bucket,
            initConfig = InitConfig(tenant = TENANT, region = SharedLocalStack.region()),
        )
    private val containers = mutableListOf<GenericContainer<*>>()
    private val running = mutableMapOf<String, GenericContainer<*>>()
    private val remoteOps = mock<RemoteOperationsService>()
    private lateinit var helper: GenericContainer<*>

    /** The bucket Mimir ships to; a deleted one stands for an S3 that refuses every upload. */
    private var mimirBucket = bucket

    /** The bucket Loki writes to; a missing one stands for an S3 that refuses every upload. */
    private var lokiBucket = bucket

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<ClusterStateManager>().also { whenever(it.load()).thenReturn(state) } }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun prepare() {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
        listOf(lokiVolume, mimirVolume).forEach { docker.createVolumeCmd().withName(it).exec() }
        helper =
            GenericContainer(HELPER_IMAGE)
                .withCreateContainerCmdModifier { cmd ->
                    cmd.hostConfig?.withBinds(
                        Bind(lokiVolume, Volume(LokiManifestBuilder.DATA_HOST_PATH)),
                        Bind(mimirVolume, Volume(MimirManifestBuilder.DATA_HOST_PATH)),
                    )
                }.withCommand("sleep", "infinity")
                .apply { start() }
                .also { containers.add(it) }
        runNodeCommands()
        startLoki()
        startMimir()
    }

    /**
     * The node runs the flush's commands over SSH as the admin user; here they run as root in the
     * helper, after [prelude]. Like SSH, a non-zero exit fails the call with the command's output.
     */
    private fun runNodeCommands(prelude: String = "sudo() { \"\$@\"; }") {
        whenever(remoteOps.executeRemotely(any(), any(), any(), any())).thenAnswer { invocation ->
            val command = invocation.getArgument<String>(1)
            val result = helper.execInContainer("bash", "-c", "$prelude\n$command")
            if (result.exitCode != 0) {
                throw RemoteCommandFailedException(command, result.stdout, result.stderr, "Remote command failed (${result.exitCode})")
            }
            Response(result.stdout, result.stderr)
        }
    }

    @AfterEach
    fun cleanUp() {
        containers.forEach { runCatching { it.stop() } }
        listOf(lokiVolume, mimirVolume).forEach { runCatching { docker.removeVolumeCmd(it).exec() } }
    }

    private fun startLoki() {
        val rendered = LokiManifestBuilder(getKoin().get()).buildConfigMap().data.getValue(LokiManifestBuilder.CONFIG_FILE)
        val loki =
            ObservabilityBackends.startLoki(
                ObservabilityBackends.lokiConfig(rendered),
                lokiVolume,
                lokiBucket,
                LOGS_PREFIX,
                TENANT,
                CLUSTER,
            )
        containers.add(loki)
        running[Constants.K8s.LOKI_APP_LABEL] = loki
    }

    private fun startMimir() {
        val rendered = MimirManifestBuilder(getKoin().get()).buildConfigMap().data.getValue(MimirManifestBuilder.CONFIG_FILE)
        val mimir =
            ObservabilityBackends.startMimir(
                ObservabilityBackends.mimirConfig(rendered),
                mimirVolume,
                mimirBucket,
                Constants.Observability.METRICS_ROOT,
            )
        containers.add(mimir)
        running[Constants.K8s.MIMIR_APP_LABEL] = mimir
    }

    private fun httpPort(workload: String) =
        if (workload == Constants.K8s.LOKI_APP_LABEL) Constants.K8s.LOKI_HTTP_PORT else Constants.K8s.MIMIR_HTTP_PORT

    /**
     * Scaling, on containers. Scaling to 0 stops the container gracefully (or kills it, when [kill]
     * is set) and then runs [afterScaleDown]. A backend's state is what Kubernetes' readiness probe
     * would report: a stopped container is at 0, and a running one is ready only while its `/ready`
     * answers 200 — which it stops doing once its ingester is shut down.
     */
    private inner class ContainerWorkloads(
        private val kill: Set<String> = emptySet(),
        private val afterScaleDown: (String) -> Unit = {},
    ) : BackendWorkloads {
        override fun scaleDown(
            controlHost: ClusterHost,
            workload: String,
            timeout: Duration,
        ) {
            val container = running.remove(workload) ?: return
            if (workload in kill) {
                docker.killContainerCmd(container.containerId).withSignal("KILL").exec()
            } else {
                docker.stopContainerCmd(container.containerId).withTimeout(timeout.seconds.toInt()).exec()
            }
            afterScaleDown(workload)
        }

        override fun state(
            controlHost: ClusterHost,
            workload: String,
        ): BackendState {
            val container = running[workload] ?: return BackendState.SCALED_TO_ZERO
            val ready =
                runCatching {
                    ObservabilityBackends.get("${ObservabilityBackends.baseUrl(container, httpPort(workload))}/ready", TENANT).statusCode()
                }.getOrNull()
            return if (ready == 200) BackendState.RUNNING else BackendState.NOT_READY
        }
    }

    private fun flushService(
        workloads: BackendWorkloads = ContainerWorkloads(),
        timeouts: FlushTimeouts = FlushTimeouts(shutdown = Duration.ofMinutes(2), scaleDown = Duration.ofMinutes(2)),
    ): DefaultTeardownFlushService {
        val http = ContainerObservabilityHttp(TENANT) { port -> ObservabilityBackends.baseUrl(backendServing(port), port) }
        val objectStore = S3ObjectStore(s3, EventBus())
        val backups =
            object : GrafanaAnnotationBackupService {
                override fun backup(
                    controlHost: ClusterHost,
                    clusterState: ClusterState,
                ) = Result.success(GrafanaAnnotationBackupResult(ClusterS3Path.root(bucket).resolve("a.json"), 0))
            }
        return DefaultTeardownFlushService(
            LokiTailFlush(http, workloads, remoteOps, objectStore, timeouts),
            MimirTailFlush(http, workloads, remoteOps, objectStore, timeouts),
            workloads,
            RecordingAnnotationMirror(),
            backups,
            EventBus(),
        )
    }

    private fun containerIds(): Map<String, String> = running.mapValues { it.value.containerId }

    private fun failure(result: Result<FlushReport>): FlushStepFailed = result.exceptionOrNull() as FlushStepFailed

    private fun backendServing(port: Int): GenericContainer<*> =
        running.getValue(if (port == Constants.K8s.LOKI_HTTP_PORT) Constants.K8s.LOKI_APP_LABEL else Constants.K8s.MIMIR_APP_LABEL)

    private fun pushLine(
        line: String,
        at: Instant = Instant.now(),
        host: String = "db0",
    ) {
        val nanos = at.toEpochMilli() * 1_000_000
        val body =
            """
            {"resourceLogs":[{"resource":{"attributes":[{"key":"cluster","value":{"stringValue":"$CLUSTER"}},
              {"key":"host.name","value":{"stringValue":"$host"}}]},
             "scopeLogs":[{"logRecords":[{"timeUnixNano":"$nanos","body":{"stringValue":"$line"}}]}]}]}
            """.trimIndent()
        val loki = running.getValue(Constants.K8s.LOKI_APP_LABEL)
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

    private fun writeSample() {
        val mimir = running.getValue(Constants.K8s.MIMIR_APP_LABEL)
        val body =
            PrometheusRemoteWrite.body(
                listOf(
                    PrometheusRemoteWrite.Series(
                        mapOf("__name__" to "edl_flush_probe", "cluster" to CLUSTER),
                        1.0,
                        System.currentTimeMillis(),
                    ),
                ),
            )
        val response =
            ObservabilityBackends.send(
                HttpRequest
                    .newBuilder(URI("${ObservabilityBackends.baseUrl(mimir, Constants.K8s.MIMIR_HTTP_PORT)}/api/v1/push"))
                    .header("Content-Type", "application/x-protobuf")
                    .header("Content-Encoding", "snappy")
                    .header(Constants.Observability.TENANT_HEADER, TENANT)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build(),
            )
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
    }

    private fun keys(prefix: String): List<String> =
        s3
            .listObjectsV2Paginator(
                ListObjectsV2Request
                    .builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .build(),
            ).contents()
            .map { it.key() }

    private fun day(at: Instant): Long = at.epochSecond / Duration.ofDays(1).seconds

    @Test
    fun `a flush puts Loki's chunks and index, a backdated table too, and Mimir's head in S3`() {
        val backdated = Instant.now().minus(Duration.ofDays(2))
        pushLine("today")
        pushLine("two days ago", at = backdated, host = "db1")
        writeSample()

        val report = flushService().saveTail(control, state).getOrThrow()

        val index = keys("$LOGS_PREFIX/index/")
        assertThat(index).anyMatch { it.startsWith("$LOGS_PREFIX/index/index_${day(Instant.now())}/") && it.contains("$TENANT.$CLUSTER") }
        assertThat(index).anyMatch { it.startsWith("$LOGS_PREFIX/index/index_${day(backdated)}/") }
        assertThat(keys("$LOGS_PREFIX/$TENANT/")).isNotEmpty()
        assertThat(keys("${Constants.Observability.METRICS_ROOT}/$TENANT/")).anyMatch { it.endsWith("/meta.json") }
        assertThat(report.lokiIndexFiles).isGreaterThanOrEqualTo(2)
        // Read from the real Loki's /metrics: proves the flush reads a counter this Loki exports.
        assertThat(report.lokiChunksFlushed).isGreaterThanOrEqualTo(2)
        assertThat(report.mimirBlocks).isGreaterThanOrEqualTo(1)
        assertThat(running).isEmpty()
    }

    @Test
    fun `an index the check cannot find after chunks were flushed fails the flush instead of passing empty`() {
        pushLine("line")
        val loseLocalIndex = { workload: String ->
            if (workload == Constants.K8s.LOKI_APP_LABEL) {
                helper.execInContainer("rm", "-rf", "${LokiTailFlush.INDEX_DIR}/multitenant")
            }
        }

        val failure = failure(flushService(ContainerWorkloads(afterScaleDown = loseLocalIndex)).saveTail(control, state))

        assertThat(failure.step).isEqualTo(FlushStep.LOKI_S3_CHECK)
        assertThat(failure).hasMessageContaining("no index file was found")
        assertThat(running.keys).containsExactly(Constants.K8s.MIMIR_APP_LABEL)
    }

    @Test
    fun `a node listing that fails stops the flush with the node's error, and Loki stays at 0`() {
        pushLine("line")
        val mimir = containerIds().getValue(Constants.K8s.MIMIR_APP_LABEL)
        runNodeCommands(prelude = "sudo() { echo 'sudo: a password is required' >&2; return 1; }")

        val failure = failure(flushService().saveTail(control, state))

        assertThat(failure.step).isEqualTo(FlushStep.LOKI_WAL_CHECK)
        assertThat(failure).hasMessageContaining("sudo: a password is required")
        assertThat(failure.backends).containsEntry(Constants.K8s.LOKI_APP_LABEL, BackendState.SCALED_TO_ZERO)
        // Nothing is started again, and Mimir, which the flush had not reached, is untouched.
        assertThat(containerIds()).containsExactly(entry(Constants.K8s.MIMIR_APP_LABEL, mimir))
    }

    @Test
    fun `a Loki killed before it builds its index fails the write-ahead check, stays at 0, and keeps its write-ahead log`() {
        pushLine("kept")

        val failure = failure(flushService(ContainerWorkloads(kill = setOf(Constants.K8s.LOKI_APP_LABEL))).saveTail(control, state))

        assertThat(failure.step).isEqualTo(FlushStep.LOKI_WAL_CHECK)
        assertThat(failure).hasMessageContaining("write-ahead")
        assertThat(running.keys).doesNotContain(Constants.K8s.LOKI_APP_LABEL)
        // Not restarted, so nothing replays or removes it: the index write-ahead data stays on the node.
        val wal = helper.execInContainer("find", "${LokiTailFlush.INDEX_DIR}/wal", "-type", "f").stdout
        assertThat(wal.lines().filter { it.isNotBlank() }).isNotEmpty()
    }

    @Test
    fun `an index file missing from S3 fails the S3 check`() {
        pushLine("line")
        val loseIndex = { workload: String ->
            if (workload == Constants.K8s.LOKI_APP_LABEL) {
                val lost = keys("$LOGS_PREFIX/index/").map { ObjectIdentifier.builder().key(it).build() }
                if (lost.isNotEmpty()) {
                    s3.deleteObjects(
                        DeleteObjectsRequest
                            .builder()
                            .bucket(bucket)
                            .delete(Delete.builder().objects(lost).build())
                            .build(),
                    )
                }
            }
        }

        val failure = failure(flushService(ContainerWorkloads(afterScaleDown = loseIndex)).saveTail(control, state))

        assertThat(failure.step).isEqualTo(FlushStep.LOKI_S3_CHECK)
        assertThat(failure).hasMessageContaining("Loki index files are not in S3")
    }

    @Test
    fun `a Mimir whose blocks do not reach S3 stops the flush, nothing is restarted, and a re-run stops before touching either`() {
        // Mimir comes up on a bucket that is then deleted, so every upload it makes fails.
        val doomed = "flush-doomed-${UUID.randomUUID().toString().take(8)}"
        SharedLocalStack.createBucketIfMissing(s3, doomed)
        mimirBucket = doomed
        running.remove(Constants.K8s.MIMIR_APP_LABEL)?.let { docker.stopContainerCmd(it.containerId).withTimeout(60).exec() }
        startMimir()
        deleteBucket(doomed)
        pushLine("line")
        writeSample()
        val mimir = containerIds().getValue(Constants.K8s.MIMIR_APP_LABEL)

        val failure = failure(flushService().saveTail(control, state))

        assertThat(failure.step).isEqualTo(FlushStep.MIMIR_S3_CHECK)
        assertThat(failure).hasMessageContaining("Mimir blocks are not in S3")
        assertThat(failure.backends).containsExactly(
            entry(Constants.K8s.LOKI_APP_LABEL, BackendState.SCALED_TO_ZERO),
            entry(Constants.K8s.MIMIR_APP_LABEL, BackendState.INGESTER_STOPPED),
        )
        // Loki stays at 0 and Mimir's pod is the one whose ingester was stopped: nothing was started again.
        assertThat(containerIds()).containsExactly(entry(Constants.K8s.MIMIR_APP_LABEL, mimir))

        // A second down finds Loki at 0 and Mimir not ready (its /ready fails once the ingester is
        // shut down) and stops before the mirror or any shutdown call.
        val rerun = failure(flushService().saveTail(control, state))

        assertThat(rerun.step).isEqualTo(FlushStep.BACKENDS_RUNNING)
        assertThat(rerun.backends).containsExactly(
            entry(Constants.K8s.LOKI_APP_LABEL, BackendState.SCALED_TO_ZERO),
            entry(Constants.K8s.MIMIR_APP_LABEL, BackendState.NOT_READY),
        )
        assertThat(rerun).hasMessageContaining("cannot be flushed without starting it again")
        assertThat(containerIds()).containsExactly(entry(Constants.K8s.MIMIR_APP_LABEL, mimir))
    }

    private fun deleteBucket(name: String) {
        val objects =
            s3
                .listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(name).build())
                .contents()
                .map { ObjectIdentifier.builder().key(it.key()).build() }
        if (objects.isNotEmpty()) {
            s3.deleteObjects(
                DeleteObjectsRequest
                    .builder()
                    .bucket(name)
                    .delete(Delete.builder().objects(objects).build())
                    .build(),
            )
        }
        s3.deleteBucket { it.bucket(name) }
    }

    @Test
    fun `a Loki that cannot reach S3 times out at the shutdown, is not restarted, and Mimir is untouched`() {
        lokiBucket = "missing-${UUID.randomUUID().toString().take(8)}"
        running.remove(Constants.K8s.LOKI_APP_LABEL)?.let { docker.stopContainerCmd(it.containerId).withTimeout(60).exec() }
        startLoki()
        pushLine("not yet in S3")
        val before = containerIds()

        val timeouts = FlushTimeouts(shutdown = Duration.ofSeconds(20), scaleDown = Duration.ofMinutes(2))
        val failure = failure(flushService(timeouts = timeouts).saveTail(control, state))

        assertThat(failure.step).isEqualTo(FlushStep.LOKI_SHUTDOWN)
        assertThat(failure.backends).containsExactly(
            entry(Constants.K8s.LOKI_APP_LABEL, BackendState.INGESTER_STOPPED),
            entry(Constants.K8s.MIMIR_APP_LABEL, BackendState.RUNNING),
        )
        // The same two containers run: Loki was neither scaled down nor replaced, Mimir never reached.
        assertThat(containerIds()).isEqualTo(before)
        assertThat(ContainerWorkloads().state(control, Constants.K8s.MIMIR_APP_LABEL)).isEqualTo(BackendState.RUNNING)
    }
}
