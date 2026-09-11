package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.TeardownResult
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.proxy.SocksProxyState
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
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * Tests the pre-teardown backup wiring in [Down].
 *
 * The backup runs FIRST, before any infrastructure is touched. A backup failure must abort the
 * teardown with no infrastructure removed, and `--force` must skip the backup and proceed. These
 * tests drive the whole `execute()` path with the teardown and backup services mocked, and assert
 * on whether `teardownVpc` is ever reached — that is the observable "no infra removed" signal.
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
    }

    private fun errorOutput(): String = outputHandler.errors.joinToString("\n") { it.first }

    @Test
    fun `a failed backup aborts teardown with no infrastructure removed`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        whenever(clusterStateManager.load()).thenReturn(upClusterState())
        whenever(teardownBackupService.backupBeforeTeardown(any(), any()))
            .thenReturn(Result.failure(IllegalStateException("grafana unreachable")))

        Down().apply { autoApprove = true }.execute()

        // The teardown must never be attempted: no infrastructure was removed.
        verify(teardownService, never()).teardownVpc(any(), any())
        assertThat(errorOutput()).contains("no infrastructure was removed")
    }

    @Test
    fun `a successful backup runs before the teardown`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        whenever(clusterStateManager.load()).thenReturn(upClusterState())
        whenever(teardownBackupService.backupBeforeTeardown(any(), any())).thenReturn(Result.success(Unit))
        // Empty preview so the teardown returns immediately after the preview call.
        whenever(teardownService.teardownVpc(any(), eq(true))).thenReturn(TeardownResult.success(emptyList()))

        Down().apply { autoApprove = true }.execute()

        // The backup is attempted, and only then is the teardown reached.
        val order = inOrder(teardownBackupService, teardownService)
        order.verify(teardownBackupService).backupBeforeTeardown(eq(controlHost), any())
        order.verify(teardownService).teardownVpc(eq("vpc-123"), eq(true))
    }

    @Test
    fun `--force skips the backup and tears down anyway`() {
        whenever(clusterStateManager.exists()).thenReturn(true)
        whenever(clusterStateManager.load()).thenReturn(upClusterState())
        whenever(teardownService.teardownVpc(any(), eq(true))).thenReturn(TeardownResult.success(emptyList()))

        Down()
            .apply {
                autoApprove = true
                force = true
            }.execute()

        verify(teardownBackupService, never()).backupBeforeTeardown(any(), any())
        verify(socksProxyService, never()).ensureRunning(any())
        verify(teardownService, times(1)).teardownVpc(eq("vpc-123"), eq(true))
    }
}
