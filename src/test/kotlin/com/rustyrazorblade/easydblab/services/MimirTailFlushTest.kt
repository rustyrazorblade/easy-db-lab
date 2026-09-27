package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration

/**
 * What the Mimir flush checks and refuses. Mimir's HTTP API, the control node's filesystem (over
 * SSH), Kubernetes and S3 are faked at their boundaries. The S3 fake is a sorted bucket that
 * honours `startAfter`, the way S3 lists keys.
 */
class MimirTailFlushTest {
    private val control = ClusterHost("54.0.0.1", "10.0.1.5", "control0", "us-west-2a")
    private val state =
        ClusterState(name = "lab", clusterId = "c1", versions = mutableMapOf(), s3Bucket = "acct", initConfig = InitConfig(tenant = "acme"))
    private val remoteOps = mock<RemoteOperationsService>()
    private val bucket = sortedSetOf<String>()
    private val listings = mutableListOf<Pair<String, String>>()
    private val scaledDown = mutableListOf<String>()

    /** Lists [bucket] like S3: every key under the directory, in order, after `startAfter`. */
    private val objectStore =
        mock<ObjectStore>().also { store ->
            whenever(store.listFiles(any(), any(), any())).thenAnswer { invocation ->
                val dir = invocation.getArgument<ClusterS3Path>(0).getKey() + "/"
                val startAfter = invocation.getArgument<String>(2)
                listings += dir to startAfter
                bucket
                    .filter { it.startsWith(dir) && it > startAfter }
                    .map { ObjectStore.FileInfo(ClusterS3Path.fromKey("acct", it), 1, "") }
            }
        }

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

    private fun metrics(failedCompactions: Int) =
        ObservabilityResponse(
            200,
            """
            # HELP cortex_ingester_tsdb_compactions_failed_total Total number of TSDB compactions that failed.
            cortex_ingester_tsdb_compactions_failed_total $failedCompactions
            cortex_ingester_tsdb_head_max_timestamp_seconds 1.7900000005e+09
            """.trimIndent(),
        )

    private fun http(failedCompactionsAfter: Int = 0) =
        RecordingObservabilityHttp(metrics(0), ObservabilityResponse(204, ""), metrics(failedCompactionsAfter))

    private fun meta(
        ulid: String,
        maxTime: Long = 1790000000501,
        samples: Int = 100,
    ) = "=== /mnt/db1/mimir/tsdb/acme/$ulid/meta.json\n" +
        """{"ulid":"$ulid","minTime":1789990000000,"maxTime":$maxTime,"stats":{"numSamples":$samples},"compaction":{"level":1}}"""

    private fun localBlocks(vararg blocks: String) {
        whenever(remoteOps.executeRemotely(any(), argThat { contains("/mnt/db1/mimir/tsdb") }, any(), any()))
            .thenReturn(Response(blocks.joinToString("\n")))
    }

    private fun flush(
        http: RecordingObservabilityHttp = http(),
        progress: FlushProgress = FlushProgress(FlushStep.MIMIR_SHUTDOWN, "mimir"),
    ) = MimirTailFlush(http, workloads, remoteOps, objectStore).flush(control, state, progress)

    @BeforeEach
    fun setUp() {
        localBlocks(meta("01HBLOCKB"), meta("01HBLOCKC"))
        bucket += listOf("mimir/acme/01HBLOCKB/meta.json", "mimir/acme/01HBLOCKB/index", "mimir/acme/01HBLOCKC/meta.json")
    }

    @Test
    fun `the head is compacted into blocks that are all in S3, and Mimir is scaled to 0`() {
        val http = http()

        assertThat(flush(http)).isEqualTo(SignalReport.Metrics(2))
        assertThat(http.calls.map { "${it.method} ${it.port} ${it.path}" })
            .containsExactly("GET 9009 /metrics", "POST 9009 /ingester/shutdown", "GET 9009 /metrics")
        assertThat(scaledDown).containsExactly("mimir")
    }

    /**
     * A tenant's directory holds every cluster's blocks. The check lists it once, from this cluster's
     * oldest block, instead of one request per block, and still finds a block of its own that is missing.
     */
    @Test
    fun `one listing from the oldest local block finds the blocks among other clusters' blocks`() {
        bucket += listOf("mimir/acme/01HAAAOTHER/meta.json", "mimir/acme/01HBLOCKBX/meta.json", "mimir/acme-dev/01HBLOCKD/meta.json")
        localBlocks(meta("01HBLOCKC"), meta("01HBLOCKB"), meta("01HBLOCKD"))

        val progress = FlushProgress(FlushStep.MIMIR_SHUTDOWN, "mimir")
        assertThatThrownBy { flush(progress = progress) }
            .hasMessageContaining("acme/01HBLOCKD")
            .hasMessageNotContaining("01HBLOCKB,")
            .hasMessageNotContaining("01HBLOCKC")
        assertThat(listings).containsExactly("mimir/acme/" to "mimir/acme/01HBLOCKB")
        assertThat(progress.step).isEqualTo(FlushStep.MIMIR_S3_CHECK)
        assertThat(progress.backends).containsEntry("mimir", BackendState.INGESTER_STOPPED)
        assertThat(scaledDown).isEmpty()
    }

    @Test
    fun `an empty block is not expected in S3`() {
        localBlocks(meta("01HBLOCKB"), meta("01HEMPTY", samples = 0))

        assertThat(flush()).isEqualTo(SignalReport.Metrics(1))
    }

    @Test
    fun `a failed head compaction fails the compaction check, with Mimir's ingester stopped`() {
        val progress = FlushProgress(FlushStep.MIMIR_SHUTDOWN, "mimir")

        assertThatThrownBy { flush(http(failedCompactionsAfter = 1), progress) }.hasMessageContaining("compaction")
        assertThat(progress.step).isEqualTo(FlushStep.MIMIR_COMPACTION_CHECK)
        assertThat(progress.backends).containsEntry("mimir", BackendState.INGESTER_STOPPED)
    }

    @Test
    fun `a head that no local block covers fails the compaction check`() {
        localBlocks(meta("01HBLOCKB", maxTime = 1789999000000))

        assertThatThrownBy { flush() }.hasMessageContaining("head")
    }
}
