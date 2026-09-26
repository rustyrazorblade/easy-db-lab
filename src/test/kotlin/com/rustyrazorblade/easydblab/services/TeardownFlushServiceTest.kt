package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.mockingDetails
import org.mockito.kotlin.whenever
import java.time.Duration

/**
 * The order of the pre-teardown steps, what each check refuses, and how a failure stops the flush:
 * it names the step and each backend's state, and leaves the backends as the step left them. The
 * control node's HTTP services, its filesystem (over SSH), Kubernetes and S3 are faked at their
 * boundaries.
 */
class TeardownFlushServiceTest {
    private val control = ClusterHost("54.0.0.1", "10.0.1.5", "control0", "us-west-2a")
    private val state =
        ClusterState(name = "lab", clusterId = "c1", versions = mutableMapOf(), s3Bucket = "acct", initConfig = InitConfig(tenant = "acme"))
    private val steps = mutableListOf<String>()
    private val remoteOps = mock<RemoteOperationsService>()
    private val s3Keys = mutableSetOf<String>()
    private val objectStore = mock<ObjectStore>()
    private val mirror = RecordingAnnotationMirror()
    private val events = mutableListOf<Event>()
    private val eventBus =
        EventBus().also {
            it.addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        events += envelope.event
                    }

                    override fun close() = Unit
                },
            )
        }
    private val timeouts = FlushTimeouts(shutdown = Duration.ofSeconds(3), scaleDown = Duration.ofSeconds(4))

    /**
     * Kubernetes as the flush sees it. Every scale-down is recorded in [steps]; the interface offers
     * no way to scale a backend up or replace its pod, so the flush cannot start one again.
     */
    private inner class FakeWorkloads(
        private val states: Map<String, BackendState> = emptyMap(),
    ) : BackendWorkloads {
        override fun scaleDown(
            controlHost: ClusterHost,
            workload: String,
            timeout: Duration,
        ) {
            steps.add("scaleDown $workload")
        }

        override fun state(
            controlHost: ClusterHost,
            workload: String,
        ): BackendState = states[workload] ?: BackendState.RUNNING
    }

    private val backups =
        object : GrafanaAnnotationBackupService {
            override fun backup(
                controlHost: ClusterHost,
                clusterState: ClusterState,
            ): Result<GrafanaAnnotationBackupResult> {
                steps.add("annotations backup")
                return Result.success(GrafanaAnnotationBackupResult(ClusterS3Path.root("acct").resolve("a.json"), 2))
            }
        }

    /** Answers in order: Loki metrics, Loki shutdown, Loki metrics, Mimir metrics, Mimir shutdown, Mimir metrics. */
    private fun http(
        lokiShutdown: ObservabilityResponse = ObservabilityResponse(204, ""),
        chunksFlushedAtShutdown: Int = 2,
        failedCompactionsAfter: Int = 0,
    ) = RecordingObservabilityHttp(
        lokiMetrics(chunksFlushed = 5),
        lokiShutdown,
        lokiMetrics(chunksFlushed = 5 + chunksFlushedAtShutdown),
        metrics(failedCompactions = 0),
        ObservabilityResponse(204, ""),
        metrics(failedCompactions = failedCompactionsAfter),
    )

    /** Loki's flushed-chunk counter, split over two reasons so the flush must sum them. */
    private fun lokiMetrics(chunksFlushed: Int) =
        ObservabilityResponse(
            200,
            """
            # TYPE loki_ingester_chunks_flushed_total counter
            loki_ingester_chunks_flushed_total{reason="idle"} 1
            loki_ingester_chunks_flushed_total{reason="forced"} ${chunksFlushed - 1}
            """.trimIndent(),
        )

    private fun metrics(failedCompactions: Int) =
        ObservabilityResponse(
            200,
            """
            # HELP cortex_ingester_tsdb_compactions_failed_total Total number of TSDB compactions that failed.
            cortex_ingester_tsdb_compactions_failed_total $failedCompactions
            cortex_ingester_tsdb_head_max_timestamp_seconds 1.7900000005e+09
            """.trimIndent(),
        )

    private fun service(
        http: RecordingObservabilityHttp,
        workloads: BackendWorkloads = FakeWorkloads(),
    ) = DefaultTeardownFlushService(
        LokiTailFlush(http, workloads, remoteOps, objectStore, timeouts),
        MimirTailFlush(http, workloads, remoteOps, objectStore, timeouts),
        workloads,
        mirror,
        backups,
        eventBus,
    )

    private fun remote(
        marker: String,
        output: String,
    ) {
        whenever(remoteOps.executeRemotely(any(), argThat { contains(marker) }, any(), any())).thenReturn(Response(output))
    }

    private fun failure(result: Result<FlushReport>): FlushStepFailed = result.exceptionOrNull() as FlushStepFailed

    private val block =
        """
        === /mnt/db1/mimir/tsdb/acme/01HBLOCKA/meta.json
        {"ulid":"01HBLOCKA","minTime":1789990000000,"maxTime":1790000000501,"stats":{"numSamples":100},"compaction":{"level":1}}
        """.trimIndent()

    @BeforeEach
    fun setUp() {
        whenever(objectStore.fileExists(any())).thenAnswer { invocation ->
            invocation.getArgument<ClusterS3Path>(0).getKey() in s3Keys
        }
        remote("tsdb-index/wal", "")
        remote("tsdb-index/multitenant", "index_20722/1790000000-acme.lab-c1-17.tsdb\n")
        remote("/mnt/db1/mimir/tsdb", block)
        s3Keys += "observability/logs/index/index_20722/1790000000-acme.lab-c1-17.tsdb.gz"
        s3Keys += "observabilitymetrics/acme/01HBLOCKA/meta.json"
    }

    @Test
    fun `annotations are mirrored, then Loki and Mimir flushed and verified, then the annotations backed up`() {
        val http = http()

        val report = service(http).saveTail(control, state).getOrThrow()

        assertThat(steps).containsExactly("scaleDown loki", "scaleDown mimir", "annotations backup")
        assertThat(mirror.synced).containsExactly(control)
        assertThat(http.calls.map { "${it.method} ${it.port} ${it.path}" }).containsExactly(
            "GET 3100 /metrics",
            "POST 3100 /ingester/shutdown?flush=true&terminate=false&delete_ring_tokens=false",
            "GET 3100 /metrics",
            "GET 9009 /metrics",
            "POST 9009 /ingester/shutdown",
            "GET 9009 /metrics",
        )
        assertThat(http.calls.filter { it.method == "POST" }.map { it.timeout }).allMatch { it == Duration.ofSeconds(3) }
        assertThat(report).isEqualTo(FlushReport(lokiIndexFiles = 1, lokiChunksFlushed = 2, mimirBlocks = 1))
        assertThat(events.filterIsInstance<Event.Teardown.LokiFlushed>().single())
            .isEqualTo(Event.Teardown.LokiFlushed(indexFiles = 1, chunksFlushed = 2))
    }

    @Test
    fun `a Loki shutdown that does not finish stops the flush there, with Loki's ingester stopped and Mimir untouched`() {
        val http = http(lokiShutdown = ObservabilityResponse(500, "flush failed"))

        val failure = failure(service(http).saveTail(control, state))

        assertThat(failure.step).isEqualTo(FlushStep.LOKI_SHUTDOWN)
        assertThat(failure).hasMessageContaining("flush failed").hasMessageContaining(FlushStep.LOKI_SHUTDOWN.description)
        assertThat(failure.backends).containsExactly(
            entry("loki", BackendState.INGESTER_STOPPED),
            entry("mimir", BackendState.RUNNING),
        )
        assertThat(steps).isEmpty()
        assertThat(http.calls.map { it.port }).doesNotContain(9009)
    }

    @Test
    fun `an index write-ahead segment left after the stop fails the write-ahead check, and Loki stays at 0`() {
        remote("tsdb-index/wal", "/mnt/db1/loki/tsdb-index/wal/20722/00000001\n")

        val failure = failure(service(http()).saveTail(control, state))

        assertThat(failure.step).isEqualTo(FlushStep.LOKI_WAL_CHECK)
        assertThat(failure).hasMessageContaining("write-ahead")
        assertThat(failure.backends).containsExactly(
            entry("loki", BackendState.SCALED_TO_ZERO),
            entry("mimir", BackendState.RUNNING),
        )
        assertThat(steps).containsExactly("scaleDown loki")
    }

    @Test
    fun `an index file that is not in S3 fails the S3 check, and Loki stays at 0`() {
        s3Keys.clear()

        val failure = failure(service(http()).saveTail(control, state))

        assertThat(failure.step).isEqualTo(FlushStep.LOKI_S3_CHECK)
        assertThat(failure).hasMessageContaining("index_20722/1790000000-acme.lab-c1-17.tsdb")
        assertThat(failure.backends).containsEntry("loki", BackendState.SCALED_TO_ZERO)
        assertThat(steps).containsExactly("scaleDown loki")
    }

    @Test
    fun `chunks flushed at shutdown with no index file on the node fail the S3 check`() {
        remote("tsdb-index/multitenant", "")

        val failure = failure(service(http(chunksFlushedAtShutdown = 3)).saveTail(control, state))

        // The flushed chunks' series are in the index head, so Loki must have built an index file;
        // finding none means the check looked in the wrong place, not that there is nothing to save.
        assertThat(failure.step).isEqualTo(FlushStep.LOKI_S3_CHECK)
        assertThat(failure).hasMessageContaining("3 chunks").hasMessageContaining("no index file")
        assertThat(steps).containsExactly("scaleDown loki")
    }

    @Test
    fun `a Loki that flushed nothing at shutdown needs no index file`() {
        remote("tsdb-index/multitenant", "")

        val report = service(http(chunksFlushedAtShutdown = 0)).saveTail(control, state).getOrThrow()

        assertThat(report.lokiIndexFiles).isZero()
        assertThat(report.lokiChunksFlushed).isZero()
    }

    @Test
    fun `an index listing that fails on the node fails the flush with the node's error`() {
        whenever(remoteOps.executeRemotely(any(), argThat { contains("tsdb-index/multitenant") }, any(), any()))
            .thenAnswer {
                throw RemoteCommandFailedException(
                    command = "sudo sh -c ...",
                    stdout = "",
                    stderr = "sudo: a password is required",
                    summary = "10.0.1.5: Remote command failed (1)",
                )
            }

        val failure = failure(service(http()).saveTail(control, state))

        assertThat(failure).hasMessageContaining("sudo: a password is required")
        assertThat(failure.step).isEqualTo(FlushStep.LOKI_S3_CHECK)
        assertThat(steps).containsExactly("scaleDown loki")
    }

    @Test
    fun `the node listings treat only a missing directory as empty, and let every other failure through`() {
        service(http()).saveTail(control, state).getOrThrow()

        // `|| true` or a discarded stderr would turn a failed listing into an empty one, which the
        // write-ahead check reads as "nothing left" and the index check as "nothing to prove".
        val commands = mockingDetails(remoteOps).invocations.map { it.getArgument<String>(1) }
        val listings = commands.filter { it.contains("tsdb-index") }
        assertThat(listings).hasSize(2).allSatisfy { command ->
            assertThat(command).contains("if [ -d ").doesNotContain("|| true").doesNotContain("2>/dev/null")
        }
    }

    @Test
    fun `a failed head compaction fails the compaction check, with Loki at 0 and Mimir's ingester stopped`() {
        val failure = failure(service(http(failedCompactionsAfter = 1)).saveTail(control, state))

        assertThat(failure.step).isEqualTo(FlushStep.MIMIR_COMPACTION_CHECK)
        assertThat(failure).hasMessageContaining("compaction")
        assertThat(failure.backends).containsExactly(
            entry("loki", BackendState.SCALED_TO_ZERO),
            entry("mimir", BackendState.INGESTER_STOPPED),
        )
        assertThat(steps).containsExactly("scaleDown loki")
    }

    @Test
    fun `a head that no local block covers fails the compaction check`() {
        remote("/mnt/db1/mimir/tsdb", block.replace("1790000000501", "1789999000000"))

        val failure = failure(service(http()).saveTail(control, state))

        assertThat(failure.step).isEqualTo(FlushStep.MIMIR_COMPACTION_CHECK)
        assertThat(failure).hasMessageContaining("head")
    }

    @Test
    fun `a block that is not in S3 fails the S3 check, and an empty block is not expected there`() {
        s3Keys.remove("observabilitymetrics/acme/01HBLOCKA/meta.json")
        remote(
            "/mnt/db1/mimir/tsdb",
            block + "\n=== /mnt/db1/mimir/tsdb/acme/01HEMPTY/meta.json\n" +
                """{"ulid":"01HEMPTY","minTime":1,"maxTime":2,"stats":{"numSamples":0},"compaction":{"level":1}}""",
        )

        val failure = failure(service(http()).saveTail(control, state))

        assertThat(failure.step).isEqualTo(FlushStep.MIMIR_S3_CHECK)
        assertThat(failure.message).contains("01HBLOCKA").doesNotContain("01HEMPTY")
        assertThat(failure.backends).containsEntry("mimir", BackendState.INGESTER_STOPPED)
        assertThat(steps).containsExactly("scaleDown loki")
    }

    @Test
    fun `a backend a previous down left at 0 stops the flush before anything is touched`() {
        val http = http()
        val workloads = FakeWorkloads(states = mapOf("loki" to BackendState.SCALED_TO_ZERO))

        val failure = failure(service(http, workloads).saveTail(control, state))

        assertThat(failure.step).isEqualTo(FlushStep.BACKENDS_RUNNING)
        assertThat(failure).hasMessageContaining("loki").hasMessageContaining("cannot be flushed without starting it again")
        assertThat(failure.backends).containsExactly(
            entry("loki", BackendState.SCALED_TO_ZERO),
            entry("mimir", BackendState.RUNNING),
        )
        assertThat(mirror.synced).isEmpty()
        assertThat(http.calls).isEmpty()
        assertThat(steps).isEmpty()
    }

    @Test
    fun `a backend whose ingester a previous down stopped is not ready, and stops the flush`() {
        val workloads = FakeWorkloads(states = mapOf("mimir" to BackendState.NOT_READY))

        val failure = failure(service(http(), workloads).saveTail(control, state))

        assertThat(failure.step).isEqualTo(FlushStep.BACKENDS_RUNNING)
        assertThat(failure).hasMessageContaining("mimir (not ready)").hasMessageNotContaining("loki (")
        assertThat(steps).isEmpty()
    }
}
