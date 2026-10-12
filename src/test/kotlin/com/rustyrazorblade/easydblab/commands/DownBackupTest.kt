package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.SavedSignal
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.TailFlushRecord
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredResources
import com.rustyrazorblade.easydblab.providers.aws.TeardownResult
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.proxy.SocksProxyState
import com.rustyrazorblade.easydblab.proxy.TunnelStopResult
import com.rustyrazorblade.easydblab.services.BackendState
import com.rustyrazorblade.easydblab.services.FlushOutcome
import com.rustyrazorblade.easydblab.services.FlushStep
import com.rustyrazorblade.easydblab.services.FlushStepFailed
import com.rustyrazorblade.easydblab.services.TailFlushFailed
import com.rustyrazorblade.easydblab.services.TailSignal
import com.rustyrazorblade.easydblab.services.TailscaleService
import com.rustyrazorblade.easydblab.services.TeardownBackupService
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.nio.file.AccessDeniedException
import java.time.Instant

/**
 * Tests the pre-teardown backup wiring in [Down].
 *
 * The backup stops Loki and Mimir, so it runs only once the teardown is certain to go ahead: after
 * the preview found resources and the operator confirmed, and before any infrastructure is touched.
 * A backup failure must abort the teardown with no infrastructure removed, and `--force` must skip
 * the backup and proceed. A teardown that fails after the backup ran restores nothing. These
 * tests drive the whole `execute()` path with the teardown and backup services mocked, and assert
 * on whether `teardownVpc(dryRun = false)` is ever reached — the observable "no infra removed" signal.
 */
class DownBackupTest : BaseKoinTest() {
    private lateinit var clusterStateManager: ClusterStateManager
    private lateinit var teardownService: AwsInfrastructureService
    private lateinit var teardownBackupService: TeardownBackupService
    private lateinit var socksProxyService: SocksProxyService
    private lateinit var outputHandler: BufferedOutputHandler

    private val controlHost =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = "10.0.1.5",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-control",
        )

    private fun upClusterState() =
        ClusterState(
            name = "perf-test",
            versions = mutableMapOf(),
            s3Bucket = "acct-bucket",
            vpcId = "vpc-123",
            hosts = mapOf(ServerType.Control to listOf(controlHost)),
        ).apply { markInfrastructureUp() }

    /** A cluster whose infrastructure is DOWN — the backup must be skipped, not attempted. */
    private fun downClusterState() =
        ClusterState(
            name = "perf-test",
            versions = mutableMapOf(),
            s3Bucket = "acct-bucket",
            vpcId = "vpc-123",
            hosts = mapOf(ServerType.Control to listOf(controlHost)),
        )

    /** An UP cluster with no control node — the backup has no host to reach, so it must be skipped. */
    private fun upClusterStateNoControlHost() =
        ClusterState(
            name = "perf-test",
            versions = mutableMapOf(),
            s3Bucket = "acct-bucket",
            vpcId = "vpc-123",
            hosts = emptyMap(),
        ).apply { markInfrastructureUp() }

    /** An UP redirect cluster: it runs no local backends, so there is nothing to back up. */
    private fun upRedirectClusterState() =
        upClusterState().apply {
            initConfig = InitConfig(telemetryRedirect = TelemetryRedirect.fromBaseHost("10.9.9.9"))
        }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<ClusterStateManager>() }
                single { mock<AwsInfrastructureService>() }
                single { mock<TailscaleService>() }
                single { mock<TeardownBackupService>() }
                single { mock<SocksProxyService>() }
            },
        )

    @BeforeEach
    fun setupMocks() {
        clusterStateManager = getKoin().get()
        teardownService = getKoin().get()
        teardownBackupService = getKoin().get()
        socksProxyService = getKoin().get()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(socksProxyService.ensureRunning(any()))
            .thenReturn(SocksProxyState(1080, controlHost, Instant.now()))
        // A mock's default for the sealed result is null; these teardowns record no tunnel.
        whenever(socksProxyService.stop()).thenReturn(TunnelStopResult.NotRunning)
        whenever(teardownBackupService.unsavedSignals(any())).thenReturn(unsavedSignals)
    }

    private val unsavedSignals = listOf(TailSignal.LOGS, TailSignal.METRICS, TailSignal.TRACES, TailSignal.ANNOTATIONS)

    private val saved = Result.success(FlushOutcome(saved = emptyMap(), failed = emptyMap(), backends = emptyMap()))

    /** A save that failed [signal] at [step] with [reason], leaving [backends] as they are. */
    private fun failedSave(
        step: FlushStep,
        reason: String,
        backends: Map<String, BackendState> = emptyMap(),
    ): Result<FlushOutcome> =
        Result.failure(TailFlushFailed(mapOf(step.signal to FlushStepFailed(step, backends, IllegalStateException(reason))), backends))

    private val resources = DiscoveredResources(vpcId = "vpc-123", vpcName = "perf-test", instanceIds = listOf("i-control"))

    /** The preview finds this cluster's resources, so the teardown goes ahead once confirmed. */
    private fun previewFindsResources() {
        whenever(teardownService.teardownVpc(eq("vpc-123"), eq(true))).thenReturn(TeardownResult.success(resources))
    }

    private fun answerPrompt(
        answer: String,
        block: () -> Int,
    ): Int {
        val originalIn = System.`in`
        return try {
            System.setIn(ByteArrayInputStream("$answer\n".toByteArray()))
            block()
        } finally {
            System.setIn(originalIn)
        }
    }

    private fun errorOutput(): String = outputHandler.errors.joinToString("\n") { it.first }

    private fun messageOutput(): String = outputHandler.messages.joinToString("\n")

    @Test
    fun `a failed backup aborts teardown with no infrastructure removed`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        whenever(clusterStateManager.load()).thenReturn(upClusterState())
        previewFindsResources()
        whenever(teardownBackupService.backupBeforeTeardown(any(), any()))
            .thenReturn(failedSave(FlushStep.ANNOTATIONS_BACKUP, "grafana unreachable"))

        Down().apply { autoApprove = true }.execute()

        // The teardown must never be attempted: no infrastructure was removed.
        verify(teardownService, never()).teardownVpc(any(), eq(false))
        assertThat(errorOutput()).contains("no infrastructure was removed")
    }

    @Test
    fun `every failed signal is reported with its step, its cause, each workload's state and --force`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        whenever(clusterStateManager.load()).thenReturn(upClusterState())
        previewFindsResources()
        val backends =
            mapOf(
                "loki" to BackendState.SCALED_TO_ZERO,
                "mimir" to BackendState.INGESTER_STOPPED,
                "otel-collector" to BackendState.DELETED,
                "tempo" to BackendState.RUNNING,
            )
        val failures =
            mapOf(
                TailSignal.METRICS to
                    FlushStepFailed(
                        FlushStep.MIMIR_SHUTDOWN,
                        backends,
                        IllegalStateException("Mimir's ingester shutdown failed (status 503): ingester unavailable"),
                    ),
                TailSignal.TRACES to
                    FlushStepFailed(
                        FlushStep.TEMPO_BLOCKS_FLUSHED,
                        backends,
                        IllegalStateException("local blocks not yet in S3 [blocks/acme/01HT]"),
                    ),
            )
        whenever(teardownBackupService.backupBeforeTeardown(any(), any())).thenReturn(Result.failure(TailFlushFailed(failures, backends)))

        val exitCode = Down().apply { autoApprove = true }.call()

        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        verify(teardownService, never()).teardownVpc(any(), eq(false))
        assertThat(errorOutput())
            .contains(TailSignal.METRICS.description)
            .contains(FlushStep.MIMIR_SHUTDOWN.description)
            .contains("Mimir's ingester shutdown failed (status 503)")
            .contains(TailSignal.TRACES.description)
            .contains(FlushStep.TEMPO_BLOCKS_FLUSHED.description)
            .contains("blocks/acme/01HT")
            .contains("no infrastructure was removed")
            .contains("loki: ${BackendState.SCALED_TO_ZERO.description}")
            .contains("mimir: ${BackendState.INGESTER_STOPPED.description}")
            .contains("left stopped: loki, mimir, otel-collector")
            .contains("down --force")
    }

    @Test
    fun `a re-run reports the recorded signals as already saved and saves the rest`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        val flushed =
            upClusterState().apply {
                tailFlush =
                    TailFlushRecord(
                        mapOf(
                            TailSignal.LOGS to SavedSignal(Instant.parse("2026-09-26T12:00:00Z")),
                            TailSignal.METRICS to SavedSignal(Instant.parse("2026-09-26T12:00:05Z")),
                        ),
                    )
            }
        whenever(clusterStateManager.load()).thenReturn(flushed)
        whenever(teardownBackupService.backupBeforeTeardown(any(), any())).thenReturn(saved)
        previewFindsResources()
        whenever(teardownService.teardownVpc(eq("vpc-123"), eq(false))).thenReturn(TeardownResult.success(resources))

        val exitCode = Down().apply { autoApprove = true }.call()

        // The collector stop, the Tempo drain and the annotations backup run on every down.
        assertThat(exitCode).isEqualTo(0)
        verify(teardownBackupService).backupBeforeTeardown(eq(controlHost), eq(flushed))
        verify(teardownService).teardownVpc(eq("vpc-123"), eq(false))
        assertThat(messageOutput())
            .contains("Already saved by an earlier down")
            .contains("${TailSignal.LOGS.description} at 2026-09-26T12:00:00Z")
            .contains("${TailSignal.METRICS.description} at 2026-09-26T12:00:05Z")
    }

    @Test
    fun `a failed backup makes down exit non-zero`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        whenever(clusterStateManager.load()).thenReturn(upClusterState())
        previewFindsResources()
        whenever(teardownBackupService.backupBeforeTeardown(any(), any()))
            .thenReturn(failedSave(FlushStep.ANNOTATIONS_BACKUP, "grafana unreachable"))

        val exitCode = Down().apply { autoApprove = true }.call()

        // Nothing was torn down, so a script driving `down` must see a failure, not success.
        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        verify(teardownService, never()).teardownVpc(any(), eq(false))
    }

    @Test
    fun `a tunnel-setup failure makes down exit non-zero`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        whenever(clusterStateManager.load()).thenReturn(upClusterState())
        previewFindsResources()
        whenever(socksProxyService.ensureRunning(any()))
            .thenThrow(IllegalStateException("SOCKS5 proxy failed to establish a working tunnel"))

        val exitCode = Down().apply { autoApprove = true }.call()

        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        assertThat(errorOutput())
            .contains("no infrastructure was removed")
            .contains("'SOCKS tunnel to the control node' failed: SOCKS5 proxy failed to establish a working tunnel")
        unsavedSignals.forEach { assertThat(errorOutput()).contains("${it.description}: 'SOCKS tunnel to the control node'") }
    }

    @Test
    fun `a successful backup and teardown exits zero`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        whenever(clusterStateManager.load()).thenReturn(upClusterState())
        whenever(teardownBackupService.backupBeforeTeardown(any(), any())).thenReturn(saved)
        previewFindsResources()
        whenever(teardownService.teardownVpc(eq("vpc-123"), eq(false))).thenReturn(TeardownResult.success(resources))

        val exitCode = Down().apply { autoApprove = true }.call()

        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `the backup runs after the preview and before the teardown`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        whenever(clusterStateManager.load()).thenReturn(upClusterState())
        whenever(teardownBackupService.backupBeforeTeardown(any(), any())).thenReturn(saved)
        previewFindsResources()
        whenever(teardownService.teardownVpc(eq("vpc-123"), eq(false))).thenReturn(TeardownResult.success(resources))

        Down().apply { autoApprove = true }.execute()

        // The preview decides the teardown goes ahead; only then are the backends stopped, and only
        // once they are flushed is any infrastructure removed.
        val order = inOrder(teardownBackupService, teardownService)
        order.verify(teardownService).teardownVpc(eq("vpc-123"), eq(true))
        order.verify(teardownBackupService).backupBeforeTeardown(eq(controlHost), any())
        order.verify(teardownService).teardownVpc(eq("vpc-123"), eq(false))
    }

    @Test
    fun `declining the prompt stops no backend`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        whenever(clusterStateManager.load()).thenReturn(upClusterState())
        previewFindsResources()

        val exitCode = answerPrompt("n") { Down().call() }

        // The flush scales Loki and Mimir to zero; declining must leave them running.
        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        verify(teardownBackupService, never()).backupBeforeTeardown(any(), any())
        verify(teardownService, never()).teardownVpc(any(), eq(false))
    }

    @Test
    fun `confirming the prompt runs the backup and then the teardown`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        whenever(clusterStateManager.load()).thenReturn(upClusterState())
        whenever(teardownBackupService.backupBeforeTeardown(any(), any())).thenReturn(saved)
        previewFindsResources()
        whenever(teardownService.teardownVpc(eq("vpc-123"), eq(false))).thenReturn(TeardownResult.success(resources))

        val exitCode = answerPrompt("y") { Down().call() }

        assertThat(exitCode).isEqualTo(0)
        verify(teardownBackupService).backupBeforeTeardown(eq(controlHost), any())
        verify(teardownService).teardownVpc(eq("vpc-123"), eq(false))
    }

    @Test
    fun `a preview that finds nothing stops no backend`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        whenever(clusterStateManager.load()).thenReturn(upClusterState())
        whenever(teardownService.teardownVpc(any(), eq(true))).thenReturn(TeardownResult.success(emptyList()))

        Down().apply { autoApprove = true }.execute()

        verify(teardownBackupService, never()).backupBeforeTeardown(any(), any())
        verify(socksProxyService, never()).ensureRunning(any())
    }

    @Test
    fun `a teardown that fails after the flush restores nothing and reports the teardown failure`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        whenever(clusterStateManager.load()).thenReturn(upClusterState())
        whenever(teardownBackupService.backupBeforeTeardown(any(), any())).thenReturn(saved)
        previewFindsResources()
        whenever(teardownService.teardownVpc(eq("vpc-123"), eq(false)))
            .thenReturn(TeardownResult.failure(listOf("DependencyViolation on sg-1")))

        val exitCode = Down().apply { autoApprove = true }.call()

        // The owner wants the cluster down: Loki and Mimir stay at 0, and the tunnel's port is not
        // published again for a cluster client to use.
        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        verify(teardownBackupService).backupBeforeTeardown(eq(controlHost), any())
        verifyNoMoreInteractions(teardownBackupService)
        assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isNull()
        assertThat(errorOutput()).contains("DependencyViolation on sg-1")
    }

    @Test
    fun `--force skips the backup and tears down anyway`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        whenever(clusterStateManager.load()).thenReturn(upClusterState())
        previewFindsResources()
        whenever(teardownService.teardownVpc(eq("vpc-123"), eq(false))).thenReturn(TeardownResult.success(resources))

        Down()
            .apply {
                autoApprove = true
                force = true
            }.execute()

        verify(teardownBackupService, never()).backupBeforeTeardown(any(), any())
        verify(socksProxyService, never()).ensureRunning(any())
        verify(teardownService, times(1)).teardownVpc(eq("vpc-123"), eq(false))
    }

    @Test
    fun `--force lists the unsaved signals before the confirmation prompt and before any teardown`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        whenever(clusterStateManager.load()).thenReturn(upClusterState())
        whenever(
            teardownBackupService.unsavedSignals(any()),
        ).thenReturn(listOf(TailSignal.METRICS, TailSignal.TRACES, TailSignal.ANNOTATIONS))
        previewFindsResources()
        whenever(teardownService.teardownVpc(eq("vpc-123"), eq(false))).thenAnswer {
            // By the time anything is torn down, the list is already out.
            assertThat(messageOutput()).contains("not saved to S3")
            TeardownResult.success(resources)
        }

        val exitCode = answerPrompt("y") { Down().apply { force = true }.call() }

        assertThat(exitCode).isEqualTo(0)
        val output = messageOutput()
        val listed = output.indexOf("not saved to S3: metrics (Mimir), traces (Tempo), annotations (Grafana)")
        assertThat(listed).isNotNegative()
        assertThat(listed).isLessThan(output.indexOf("Are you sure you want to delete these resources?"))
        assertThat(output).doesNotContain("profiles")
        verify(teardownBackupService, never()).backupBeforeTeardown(any(), any())
        verify(teardownService).teardownVpc(eq("vpc-123"), eq(false))
    }

    @Test
    fun `infrastructure DOWN skips the backup, says why, and still tears down`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        whenever(clusterStateManager.load()).thenReturn(downClusterState())
        previewFindsResources()
        whenever(teardownService.teardownVpc(eq("vpc-123"), eq(false))).thenReturn(TeardownResult.success(resources))

        Down().apply { autoApprove = true }.execute()

        // No backup is attempted, but the teardown still proceeds — and the skip is observable.
        verify(teardownBackupService, never()).backupBeforeTeardown(any(), any())
        verify(socksProxyService, never()).ensureRunning(any())
        verify(teardownService).teardownVpc(eq("vpc-123"), eq(false))
        assertThat(messageOutput()).contains("infrastructure is not up")
    }

    @Test
    fun `redirect cluster skips the backup, says why, and still tears down`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        whenever(clusterStateManager.load()).thenReturn(upRedirectClusterState())
        previewFindsResources()
        whenever(teardownService.teardownVpc(eq("vpc-123"), eq(false))).thenReturn(TeardownResult.success(resources))

        Down().apply { autoApprove = true }.execute()

        // The control host exists and the cluster is UP, so only the redirect gate can skip here.
        verify(teardownBackupService, never()).backupBeforeTeardown(any(), any())
        verify(socksProxyService, never()).ensureRunning(any())
        verify(teardownService).teardownVpc(eq("vpc-123"), eq(false))
        assertThat(messageOutput()).contains("redirected to http://10.9.9.9:9009")
    }

    @Test
    fun `current-cluster teardown with no control host skips the backup rather than throwing`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        whenever(clusterStateManager.load()).thenReturn(upClusterStateNoControlHost())
        previewFindsResources()
        whenever(teardownService.teardownVpc(eq("vpc-123"), eq(false))).thenReturn(TeardownResult.success(resources))

        Down().apply { autoApprove = true }.execute()

        verify(teardownBackupService, never()).backupBeforeTeardown(any(), any())
        verify(socksProxyService, never()).ensureRunning(any())
        verify(teardownService).teardownVpc(eq("vpc-123"), eq(false))
        assertThat(messageOutput()).contains("no control node")
    }

    @Test
    fun `down --all never runs the pre-teardown backup`() {
        whenever(teardownService.teardownAllTagged(eq(true), any()))
            .thenReturn(TeardownResult.success(emptyList()))

        Down()
            .apply {
                autoApprove = true
                teardownAll = true
            }.execute()

        // --all does not map to a single reachable control node, so the backup never runs.
        verify(teardownBackupService, never()).backupBeforeTeardown(any(), any())
        verify(socksProxyService, never()).ensureRunning(any())
        verify(teardownService).teardownAllTagged(eq(true), any())
    }

    @Test
    fun `a tunnel-setup failure aborts teardown with no infrastructure removed`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        whenever(clusterStateManager.load()).thenReturn(upClusterState())
        previewFindsResources()
        // The SOCKS tunnel cannot be established — this must abort the same way a backup failure does,
        // not escape as a raw stack trace.
        whenever(socksProxyService.ensureRunning(any()))
            .thenThrow(RuntimeException("ssh tunnel refused"))

        Down().apply { autoApprove = true }.execute()

        verify(teardownService, never()).teardownVpc(any(), eq(false))
        verify(teardownBackupService, never()).backupBeforeTeardown(any(), any())
        assertThat(errorOutput())
            .contains("no infrastructure was removed")
            // No backend was stopped, so running down again is the way forward.
            .contains("run 'easy-db-lab down' again")
            .contains("'SOCKS tunnel to the control node' failed: ssh tunnel refused")
        unsavedSignals.forEach { assertThat(errorOutput()).contains("${it.description}: 'SOCKS tunnel to the control node'") }
    }

    @Test
    fun `a save that fails for a reason other than a signal is reported as the save's failure, not the tunnel's`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        whenever(clusterStateManager.load()).thenReturn(upClusterState())
        previewFindsResources()
        whenever(teardownBackupService.backupBeforeTeardown(any(), any()))
            .thenReturn(Result.failure(AccessDeniedException("state.json")))

        val exitCode = Down().apply { autoApprove = true }.call()

        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        verify(teardownService, never()).teardownVpc(any(), eq(false))
        assertThat(errorOutput())
            .contains("no infrastructure was removed")
            .contains("'pre-teardown save' failed: state.json")
            .doesNotContain("SOCKS tunnel")
        unsavedSignals.forEach { assertThat(errorOutput()).contains("${it.description}: 'pre-teardown save'") }
    }

    @Test
    fun `a re-run after a complete save skips the whole save and its tunnel, and tears down`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        val completed =
            upClusterState().apply {
                tailFlush =
                    TailFlushRecord(
                        mapOf(
                            TailSignal.LOGS to SavedSignal(Instant.parse("2026-09-26T12:00:00Z")),
                            TailSignal.METRICS to SavedSignal(Instant.parse("2026-09-26T12:00:05Z")),
                        ),
                        saveCompletedAt = Instant.parse("2026-09-26T12:01:00Z"),
                    )
            }
        whenever(clusterStateManager.load()).thenReturn(completed)
        previewFindsResources()
        // The control node was terminated by the teardown that failed part-way: no tunnel reaches it.
        whenever(socksProxyService.ensureRunning(any())).thenThrow(IllegalStateException("control node unreachable"))
        whenever(teardownService.teardownVpc(eq("vpc-123"), eq(false))).thenReturn(TeardownResult.success(resources))

        val exitCode = Down().apply { autoApprove = true }.call()

        assertThat(exitCode).isEqualTo(0)
        verify(socksProxyService, never()).ensureRunning(any())
        verify(teardownBackupService, never()).backupBeforeTeardown(any(), any())
        verify(teardownService).teardownVpc(eq("vpc-123"), eq(false))
        assertThat(messageOutput())
            .contains("Already saved by an earlier down")
            .contains("${TailSignal.LOGS.description} at 2026-09-26T12:00:00Z")
            .contains("${TailSignal.TRACES.description} at 2026-09-26T12:01:00Z")
            .contains("${TailSignal.ANNOTATIONS.description} at 2026-09-26T12:01:00Z")
            .doesNotContain("Saving annotations, logs, metrics and traces")
        assertThat(errorOutput()).isEmpty()
    }
}
