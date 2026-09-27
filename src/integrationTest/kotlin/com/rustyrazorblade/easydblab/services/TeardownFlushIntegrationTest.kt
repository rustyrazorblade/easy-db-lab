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
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
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
import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the pre-teardown save against the Loki, Mimir and Tempo images the cluster deploys, with
 * their rendered configuration, on S3 (LocalStack). Docker volumes stand in for the control node's
 * hostPaths; a helper container mounts them at the node's paths and runs the save's node commands
 * (SSH on a cluster); stopping and killing containers stands in for scaling to 0, and a backend's
 * `/ready` for its Kubernetes readiness. Nothing is started again (owner decision, 2026-09-26). It proves:
 *
 * - a save puts Loki's chunks and index (a backdated table too), Mimir's head as blocks, and the
 *   spans pushed just before it in S3 (a second Tempo with no local data finds each by trace ID); the Tempo drain passes only once those live
 *   traces are cut and uploaded, never stopping Tempo;
 * - logs and metrics are recorded in the state file while the Tempo drain still runs;
 * - a re-run skips the recorded signals and runs the Tempo drain and the annotations backup again;
 * - a Loki killed before it builds its index fails the write-ahead check and stays at 0, its
 *   write-ahead data still on the node;
 * - an index file missing from S3 fails the S3 check;
 * - a local index the check cannot find, after the shutdown flushed chunks, fails the flush;
 * - a node listing that fails (sudo refused) fails the flush with the node's error.
 */
class TeardownFlushIntegrationTest : BaseKoinTest() {
    private companion object {
        const val TENANT = "acme"
        const val CLUSTER = "lab-f1"
        const val HELPER_IMAGE = "ubuntu:24.04"
        val LOGS_PREFIX = Constants.Observability.LOGS_ROOT
        val TRACES_PREFIX = Constants.Observability.TRACES_ROOT
        val RECORD_WAIT: Duration = Duration.ofMinutes(5)
        val POLL: Duration = Duration.ofSeconds(1)
        val READER_WAIT: Duration = Duration.ofMinutes(2)
    }

    private val s3 = SharedLocalStack.s3Client()
    private val docker = DockerClientFactory.instance().client()
    private val bucket = "flush-it-${UUID.randomUUID().toString().take(8)}"
    private val lokiVolume = "flush-loki-${UUID.randomUUID().toString().take(8)}"
    private val mimirVolume = "flush-mimir-${UUID.randomUUID().toString().take(8)}"
    private val tempoVolume = "flush-tempo-${UUID.randomUUID().toString().take(8)}"
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
    private val volumes = mutableListOf<String>()
    private val running = mutableMapOf<String, GenericContainer<*>>()
    private val remoteOps = mock<RemoteOperationsService>()
    private val sendersStopped = AtomicInteger()
    private val annotationBackups = AtomicInteger()
    private lateinit var helper: GenericContainer<*>

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
        listOf(lokiVolume, mimirVolume, tempoVolume).forEach { docker.createVolumeCmd().withName(it).exec() }
        helper =
            GenericContainer(HELPER_IMAGE)
                .withCreateContainerCmdModifier { cmd ->
                    cmd.hostConfig?.withBinds(
                        Bind(lokiVolume, Volume(LokiManifestBuilder.DATA_HOST_PATH)),
                        Bind(mimirVolume, Volume(MimirManifestBuilder.DATA_HOST_PATH)),
                        Bind(tempoVolume, Volume(TempoManifestBuilder.DATA_HOST_PATH)),
                    )
                }.withCommand("sleep", "infinity")
                .apply { start() }
                .also { containers.add(it) }
        runNodeCommands()
        startLoki()
        startMimir()
    }

    /**
     * The node runs the save's commands over SSH as the admin user; here they run as root in the
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
        (listOf(lokiVolume, mimirVolume, tempoVolume) + volumes).forEach { runCatching { docker.removeVolumeCmd(it).exec() } }
    }

    private fun startLoki() {
        val rendered = LokiManifestBuilder(getKoin().get()).buildConfigMap().data.getValue(LokiManifestBuilder.CONFIG_FILE)
        val loki =
            ObservabilityBackends.startLoki(
                ObservabilityBackends.lokiConfig(rendered),
                lokiVolume,
                bucket,
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
                bucket,
                Constants.Observability.METRICS_ROOT,
            )
        containers.add(mimir)
        running[Constants.K8s.MIMIR_APP_LABEL] = mimir
    }

    private fun startTempo() {
        val rendered = TempoManifestBuilder(getKoin().get()).buildConfigMap().data.getValue("tempo.yaml")
        val tempo =
            ObservabilityBackends.startTempo(
                ObservabilityBackends.tempoConfig(rendered),
                tempoVolume,
                bucket,
                TRACES_PREFIX,
                CLUSTER,
            )
        containers.add(tempo)
        running[Constants.K8s.TEMPO_APP_LABEL] = tempo
    }

    private fun workloadServing(port: Int): String =
        when (port) {
            Constants.K8s.LOKI_HTTP_PORT -> Constants.K8s.LOKI_APP_LABEL
            Constants.K8s.MIMIR_HTTP_PORT -> Constants.K8s.MIMIR_APP_LABEL
            else -> Constants.K8s.TEMPO_APP_LABEL
        }

    private fun httpPort(workload: String) =
        when (workload) {
            Constants.K8s.LOKI_APP_LABEL -> Constants.K8s.LOKI_HTTP_PORT
            Constants.K8s.MIMIR_APP_LABEL -> Constants.K8s.MIMIR_HTTP_PORT
            else -> Constants.K8s.TEMPO_PORT
        }

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

    /** The collector does not run here: its stop only counts, and Tempo's senders are the test's pushes. */
    private val senders =
        object : TelemetrySenders {
            override fun stop(
                controlHost: ClusterHost,
                timeout: Duration,
            ) {
                sendersStopped.incrementAndGet()
            }
        }

    private val backups =
        object : GrafanaAnnotationBackupService {
            override fun backup(
                controlHost: ClusterHost,
                clusterState: ClusterState,
            ): Result<GrafanaAnnotationBackupResult> {
                annotationBackups.incrementAndGet()
                return Result.success(GrafanaAnnotationBackupResult(ClusterS3Path.root(bucket).resolve("grafana/annotations/a.json"), 0))
            }
        }

    private val http =
        ContainerObservabilityHttp(TENANT) { port -> ObservabilityBackends.baseUrl(running.getValue(workloadServing(port)), port) }

    private fun tempoDrain() = TempoTailFlush(http, remoteOps)

    private fun flushService(
        workloads: BackendWorkloads = ContainerWorkloads(),
        tempoDrain: SignalFlush = tempoDrain(),
    ): DefaultTeardownFlushService {
        val objectStore = S3ObjectStore(s3, EventBus())
        val timeouts = FlushTimeouts(shutdown = Duration.ofMinutes(2), scaleDown = Duration.ofMinutes(2))
        return DefaultTeardownFlushService(
            lokiFlush = LokiTailFlush(http, workloads, remoteOps, objectStore, timeouts),
            mimirFlush = MimirTailFlush(http, workloads, remoteOps, objectStore, timeouts),
            tempoDrain = tempoDrain,
            workloads = workloads,
            telemetrySenders = senders,
            annotationMirror = RecordingAnnotationMirror(),
            annotationBackupService = backups,
            eventBus = EventBus(),
        )
    }

    /** Saves only [signal], and returns its failure. */
    private fun failureOf(
        signal: TailSignal,
        service: DefaultTeardownFlushService = flushService(),
    ): FlushStepFailed = service.saveTail(control, state, setOf(signal)).failed.getValue(signal)

    private fun containerIds(): Map<String, String> = running.mapValues { it.value.containerId }

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
        post(Constants.K8s.LOKI_APP_LABEL, Constants.K8s.LOKI_HTTP_PORT, "/otlp/v1/logs", body, expect = 204)
    }

    /** Pushes one span of a new trace to Tempo and returns its trace ID, in hex. */
    private fun pushSpan(): String {
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val nowNanos = System.currentTimeMillis() * 1_000_000
        val body =
            """
            {"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"flush-it"}}]},
            "scopeSpans":[{"spans":[{"traceId":"$traceId","spanId":"${traceId.take(16)}","name":"probe","kind":1,
            "startTimeUnixNano":"$nowNanos","endTimeUnixNano":"${nowNanos + 1_000_000}"}]}]}]}
            """.trimIndent()
        post(Constants.K8s.TEMPO_APP_LABEL, ObservabilityBackends.TEMPO_OTLP_HTTP_PORT, "/v1/traces", body, expect = 200)
        return traceId
    }

    private fun post(
        workload: String,
        port: Int,
        path: String,
        body: String,
        expect: Int,
    ) {
        val response =
            ObservabilityBackends.send(
                HttpRequest
                    .newBuilder(URI("${ObservabilityBackends.baseUrl(running.getValue(workload), port)}$path"))
                    .header("Content-Type", "application/json")
                    .header(Constants.Observability.TENANT_HEADER, TENANT)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
            )
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(expect)
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

    /**
     * The trace IDs among [traceIds] that a second Tempo, on the same bucket but an empty WAL, finds.
     * It has no local data, so it can only answer from the blocks in S3.
     */
    private fun traceIdsInS3(traceIds: List<String>): Set<String> {
        val volume = "flush-tempo-reader-${UUID.randomUUID().toString().take(8)}".also { docker.createVolumeCmd().withName(it).exec() }
        volumes.add(volume)
        val rendered = TempoManifestBuilder(getKoin().get()).buildConfigMap().data.getValue("tempo.yaml")
        val reader =
            ObservabilityBackends
                .startTempo(ObservabilityBackends.tempoConfig(rendered), volume, bucket, TRACES_PREFIX, "reader")
                .also { containers.add(it) }
        val deadline = Instant.now().plus(READER_WAIT)
        var found = emptySet<String>()
        while (found.size < traceIds.size && Instant.now().isBefore(deadline)) {
            found =
                traceIds
                    .filter { id ->
                        ObservabilityBackends
                            .get("${ObservabilityBackends.baseUrl(reader, Constants.K8s.TEMPO_PORT)}/api/v2/traces/$id", TENANT)
                            .statusCode() == 200
                    }.toSet()
            if (found.size < traceIds.size) Thread.sleep(POLL.toMillis())
        }
        return found
    }

    private fun day(at: Instant): Long = at.epochSecond / Duration.ofDays(1).seconds

    /**
     * The Tempo drain, but it first waits for logs and metrics to be recorded in [stateFile]: the
     * drain is still running when they are, as a `down` interrupted during the drain would find them.
     */
    private inner class DrainAfterRecords(
        private val stateFile: ClusterStateManager,
    ) : SignalFlush {
        var recordedBeforeDrainEnded = false

        override fun flush(
            controlHost: ClusterHost,
            clusterState: ClusterState,
            progress: FlushProgress,
        ): SignalReport {
            val deadline = Instant.now().plus(RECORD_WAIT)
            while (Instant.now().isBefore(deadline) && !recorded(stateFile).containsAll(TailSignal.RECORDED)) {
                Thread.sleep(POLL.toMillis())
            }
            recordedBeforeDrainEnded = recorded(stateFile).containsAll(TailSignal.RECORDED)
            return tempoDrain().flush(controlHost, clusterState, progress)
        }
    }

    private fun recorded(stateFile: ClusterStateManager): Set<TailSignal> =
        runCatching {
            stateFile
                .load()
                .tailFlush
                ?.signals
                ?.keys
                .orEmpty()
        }.getOrDefault(emptySet())

    @Test
    fun `a save puts every signal in S3, records logs and metrics as they finish, and a re-run saves only the rest`() {
        startTempo()
        val backdated = Instant.now().minus(Duration.ofDays(2))
        pushLine("today")
        pushLine("two days ago", at = backdated, host = "db1")
        writeSample()
        // Pushed just before the save: still live traces in Tempo's memory when the drain starts.
        val traces = listOf(pushSpan(), pushSpan())
        val tempo = containerIds().getValue(Constants.K8s.TEMPO_APP_LABEL)
        val stateFile = ClusterStateManager(File(tempDir, "state.json"))
        val drain = DrainAfterRecords(stateFile)

        val outcome =
            DefaultTeardownBackupService(
                flushService(tempoDrain = drain),
                stateFile,
            ).backupBeforeTeardown(control, state).getOrThrow()

        assertThat(outcome.saved.keys).containsExactlyInAnyOrderElementsOf(TailSignal.entries)
        val index = keys("$LOGS_PREFIX/index/")
        assertThat(index).anyMatch { it.startsWith("$LOGS_PREFIX/index/index_${day(Instant.now())}/") && it.contains("$TENANT.$CLUSTER") }
        assertThat(index).anyMatch { it.startsWith("$LOGS_PREFIX/index/index_${day(backdated)}/") }
        assertThat(keys("$LOGS_PREFIX/$TENANT/")).isNotEmpty()
        assertThat(keys("${Constants.Observability.METRICS_ROOT}/$TENANT/")).anyMatch { it.endsWith("/meta.json") }
        assertThat(
            traceIdsInS3(traces),
        ).describedAs("trace IDs a Tempo with no local data finds in S3").containsExactlyInAnyOrderElementsOf(traces)
        assertThat(drain.recordedBeforeDrainEnded).describedAs("logs and metrics recorded while the Tempo drain ran").isTrue()
        assertThat(stateFile.load().tailFlush?.signals).containsOnlyKeys(TailSignal.LOGS, TailSignal.METRICS)
        // Tempo is never stopped or restarted; Loki and Mimir are left at 0.
        assertThat(containerIds()).containsExactly(entry(Constants.K8s.TEMPO_APP_LABEL, tempo))

        // A re-run finds logs and metrics recorded: Loki and Mimir (gone) are never called, and the
        // collector stop, the Tempo drain and the annotations backup run again.
        val rerun = DefaultTeardownBackupService(flushService(), stateFile).backupBeforeTeardown(control, stateFile.load()).getOrThrow()

        assertThat(rerun.saved.keys).containsExactlyInAnyOrder(TailSignal.TRACES, TailSignal.PROFILES, TailSignal.ANNOTATIONS)
        assertThat(sendersStopped.get()).isEqualTo(2)
        assertThat(annotationBackups.get()).isEqualTo(2)
        assertThat(containerIds()).containsExactly(entry(Constants.K8s.TEMPO_APP_LABEL, tempo))
    }

    @Test
    fun `an index the check cannot find after chunks were flushed fails the flush instead of passing empty`() {
        pushLine("line")
        val loseLocalIndex = { workload: String ->
            if (workload == Constants.K8s.LOKI_APP_LABEL) {
                helper.execInContainer("rm", "-rf", "${LokiTailFlush.INDEX_DIR}/multitenant")
            }
        }

        val failure = failureOf(TailSignal.LOGS, flushService(ContainerWorkloads(afterScaleDown = loseLocalIndex)))

        assertThat(failure.step).isEqualTo(FlushStep.LOKI_S3_CHECK)
        assertThat(failure).hasMessageContaining("no index file was found")
        assertThat(running.keys).containsExactly(Constants.K8s.MIMIR_APP_LABEL)
    }

    @Test
    fun `a node listing that fails stops the flush with the node's error, and Loki stays at 0`() {
        pushLine("line")
        val mimir = containerIds().getValue(Constants.K8s.MIMIR_APP_LABEL)
        runNodeCommands(prelude = "sudo() { echo 'sudo: a password is required' >&2; return 1; }")

        val failure = failureOf(TailSignal.LOGS)

        assertThat(failure.step).isEqualTo(FlushStep.LOKI_WAL_CHECK)
        assertThat(failure).hasMessageContaining("sudo: a password is required")
        assertThat(failure.backends).containsEntry(Constants.K8s.LOKI_APP_LABEL, BackendState.SCALED_TO_ZERO)
        // Nothing is started again, and Mimir, which this save did not include, is untouched.
        assertThat(containerIds()).containsExactly(entry(Constants.K8s.MIMIR_APP_LABEL, mimir))
    }

    @Test
    fun `a Loki killed before it builds its index fails the write-ahead check, stays at 0, and keeps its write-ahead log`() {
        pushLine("kept")

        val failure = failureOf(TailSignal.LOGS, flushService(ContainerWorkloads(kill = setOf(Constants.K8s.LOKI_APP_LABEL))))

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

        val failure = failureOf(TailSignal.LOGS, flushService(ContainerWorkloads(afterScaleDown = loseIndex)))

        assertThat(failure.step).isEqualTo(FlushStep.LOKI_S3_CHECK)
        assertThat(failure).hasMessageContaining("Loki index files are not in S3")
    }
}
