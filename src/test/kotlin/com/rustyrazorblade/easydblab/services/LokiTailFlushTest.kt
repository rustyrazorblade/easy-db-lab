package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.mockingDetails
import org.mockito.kotlin.whenever
import java.time.Duration

/**
 * What the Loki flush checks and refuses, and the state it leaves Loki in when a step fails. Loki's
 * HTTP API, the control node's filesystem (over SSH), Kubernetes and S3 are faked at their boundaries.
 */
class LokiTailFlushTest {
    private val control = ClusterHost("54.0.0.1", "10.0.1.5", "control0", "us-west-2a")
    private val state =
        ClusterState(name = "lab", clusterId = "c1", versions = mutableMapOf(), s3Bucket = "acct", initConfig = InitConfig(tenant = "acme"))
    private val remoteOps = mock<RemoteOperationsService>()
    private val s3Keys = mutableSetOf<String>()
    private val objectStore = mock<ObjectStore>()
    private val scaledDown = mutableListOf<String>()
    private val timeouts = FlushTimeouts(shutdown = Duration.ofSeconds(3), scaleDown = Duration.ofSeconds(4))

    private val workloads =
        object : BackendWorkloads {
            override fun scaleDown(
                controlHost: ClusterHost,
                workload: String,
                timeout: Duration,
            ) {
                scaledDown.add(workload)
            }

            override fun state(
                controlHost: ClusterHost,
                workload: String,
            ) = BackendState.RUNNING
        }

    /** Answers in order: metrics, the shutdown, metrics. */
    private fun http(
        shutdown: ObservabilityResponse = ObservabilityResponse(204, ""),
        chunksFlushedAtShutdown: Int = 2,
    ) = RecordingObservabilityHttp(metrics(chunksFlushed = 5), shutdown, metrics(chunksFlushed = 5 + chunksFlushedAtShutdown))

    /** Loki's flushed-chunk counter, split over two reasons so the flush must sum them. */
    private fun metrics(chunksFlushed: Int) =
        ObservabilityResponse(
            200,
            """
            # TYPE loki_ingester_chunks_flushed_total counter
            loki_ingester_chunks_flushed_total{reason="idle"} 1
            loki_ingester_chunks_flushed_total{reason="forced"} ${chunksFlushed - 1}
            """.trimIndent(),
        )

    private fun remote(
        marker: String,
        output: String,
    ) {
        whenever(remoteOps.executeRemotely(any(), argThat { contains(marker) }, any(), any())).thenReturn(Response(output))
    }

    private fun flush(
        http: RecordingObservabilityHttp = http(),
        progress: FlushProgress = FlushProgress(FlushStep.LOKI_SHUTDOWN, "loki"),
    ) = LokiTailFlush(http, workloads, remoteOps, objectStore, timeouts).flush(control, state, progress)

    @BeforeEach
    fun setUp() {
        whenever(objectStore.fileExists(any())).thenAnswer { it.getArgument<ClusterS3Path>(0).getKey() in s3Keys }
        remote("tsdb-index/wal", "")
        remote("tsdb-index/multitenant", "index_20722/1790000000-acme.lab-c1-17.tsdb\n")
        s3Keys += "loki/index/index_20722/1790000000-acme.lab-c1-17.tsdb.gz"
    }

    @Test
    fun `the shutdown flushes every chunk, the stop builds the index, and every index file is found in S3`() {
        val http = http()

        val report = flush(http)

        assertThat(report).isEqualTo(SignalReport.Logs(indexFiles = 1, chunksFlushed = 2))
        assertThat(http.calls.map { "${it.method} ${it.port} ${it.path}" }).containsExactly(
            "GET 3100 /metrics",
            "POST 3100 /ingester/shutdown?flush=true&terminate=false&delete_ring_tokens=false",
            "GET 3100 /metrics",
        )
        assertThat(http.calls.single { it.method == "POST" }.timeout).isEqualTo(Duration.ofSeconds(3))
        assertThat(scaledDown).containsExactly("loki")
    }

    @Test
    fun `a shutdown that does not finish stops the flush there, with Loki's ingester stopped`() {
        val progress = FlushProgress(FlushStep.LOKI_SHUTDOWN, "loki")

        assertThatThrownBy { flush(http(shutdown = ObservabilityResponse(500, "flush failed")), progress) }
            .hasMessageContaining("flush failed")
        assertThat(progress.step).isEqualTo(FlushStep.LOKI_SHUTDOWN)
        assertThat(progress.backends).containsEntry("loki", BackendState.INGESTER_STOPPED)
        assertThat(scaledDown).isEmpty()
    }

    @Test
    fun `an index write-ahead segment left after the stop fails the write-ahead check, and Loki stays at 0`() {
        remote("tsdb-index/wal", "/mnt/db1/loki/tsdb-index/wal/20722/00000001\n")
        val progress = FlushProgress(FlushStep.LOKI_SHUTDOWN, "loki")

        assertThatThrownBy { flush(progress = progress) }.hasMessageContaining("write-ahead")
        assertThat(progress.step).isEqualTo(FlushStep.LOKI_WAL_CHECK)
        assertThat(progress.backends).containsEntry("loki", BackendState.SCALED_TO_ZERO)
    }

    @Test
    fun `an index file that is not in S3 fails the S3 check`() {
        s3Keys.clear()
        val progress = FlushProgress(FlushStep.LOKI_SHUTDOWN, "loki")

        assertThatThrownBy { flush(progress = progress) }.hasMessageContaining("index_20722/1790000000-acme.lab-c1-17.tsdb")
        assertThat(progress.step).isEqualTo(FlushStep.LOKI_S3_CHECK)
    }

    @Test
    fun `chunks flushed at shutdown with no index file on the node fail the S3 check`() {
        remote("tsdb-index/multitenant", "")

        // The flushed chunks' series are in the index head, so Loki must have built an index file;
        // finding none means the check looked in the wrong place, not that there is nothing to save.
        assertThatThrownBy { flush(http(chunksFlushedAtShutdown = 3)) }
            .hasMessageContaining("3 chunks")
            .hasMessageContaining("no index file")
    }

    @Test
    fun `a Loki that flushed nothing at shutdown needs no index file`() {
        remote("tsdb-index/multitenant", "")

        assertThat(flush(http(chunksFlushedAtShutdown = 0))).isEqualTo(SignalReport.Logs(indexFiles = 0, chunksFlushed = 0))
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

        assertThatThrownBy { flush() }.hasMessageContaining("sudo: a password is required")
    }

    @Test
    fun `the node listings treat only a missing directory as empty, and let every other failure through`() {
        flush()

        // `|| true` or a discarded stderr would turn a failed listing into an empty one, which the
        // write-ahead check reads as "nothing left" and the index check as "nothing to prove".
        val listings = mockingDetails(remoteOps).invocations.map { it.getArgument<String>(1) }.filter { it.contains("tsdb-index") }
        assertThat(listings).hasSize(2).allSatisfy { command ->
            assertThat(command).contains("if [ -d ").doesNotContain("|| true").doesNotContain("2>/dev/null")
        }
    }
}
