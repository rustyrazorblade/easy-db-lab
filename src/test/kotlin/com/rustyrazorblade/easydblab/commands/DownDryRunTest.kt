package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InfrastructureStatus
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredResources
import com.rustyrazorblade.easydblab.providers.aws.TeardownResult
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.proxy.TunnelStopResult
import com.rustyrazorblade.easydblab.services.TailscaleService
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import com.rustyrazorblade.easydblab.services.aws.AwsS3BucketService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * `down --dry-run` previews the teardown and changes nothing: the cluster state keeps its VPC and
 * stays up, the SOCKS tunnel keeps running, and the tailnet device stays recorded.
 *
 * Cluster state is real, persisted to a temp file. AWS, Tailscale and the tunnel are mocked: each
 * has side effects, and a tunnel that reports a stop is how a dry run that stops it shows.
 */
class DownDryRunTest : BaseKoinTest() {
    private lateinit var stateManager: ClusterStateManager
    private lateinit var teardownService: AwsInfrastructureService
    private lateinit var outputHandler: BufferedOutputHandler

    private val resources = DiscoveredResources(vpcId = "vpc-123", vpcName = "test-cluster", instanceIds = listOf("i-1"))

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { ClusterStateManager(File(tempDir, "state.json")) }
                single { mock<AwsInfrastructureService>() }
                single { mock<TailscaleService>() }
                single { mock<AwsS3BucketService>() }
                single<SocksProxyService> {
                    mock<SocksProxyService> { on { stop() } doReturn TunnelStopResult.Stopped(STOPPED_PID) }
                }
            },
        )

    @BeforeEach
    fun setup() {
        stateManager = getKoin().get()
        teardownService = getKoin().get()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(teardownService.teardownVpc(eq("vpc-123"), any())).thenReturn(TeardownResult.success(resources))
        whenever(teardownService.teardownAllTagged(any(), any())).thenReturn(TeardownResult.success(listOf(resources)))
        whenever(teardownService.teardownPackerInfrastructure(any())).thenReturn(TeardownResult.success(resources))

        stateManager.save(
            ClusterState(name = "test-cluster", versions = mutableMapOf(), vpcId = "vpc-123").apply {
                markInfrastructureUp()
                tailscaleDeviceId = "nControl0CNTRL"
            },
        )
        getKoin().declare(
            getKoin().get<User>().copy(tailscaleClientId = "client-id", tailscaleClientSecret = "client-secret"),
        )
    }

    private fun output(): String = (outputHandler.messages + outputHandler.errors.map { it.first }).joinToString("\n")

    @ParameterizedTest
    @ValueSource(strings = ["current", "vpc", "all", "packer"])
    fun `a dry run previews the teardown and changes nothing`(mode: String) {
        val exitCode =
            Down()
                .apply {
                    dryRun = true
                    when (mode) {
                        "vpc" -> vpcId = "vpc-123"
                        "all" -> teardownAll = true
                        "packer" -> teardownPacker = true
                    }
                }.call()

        val state = stateManager.load()
        assertThat(exitCode).isEqualTo(0)
        assertThat(output()).contains("DRY RUN").doesNotContain("Stopped SOCKS5 proxy").doesNotContain("marked as DOWN")
        assertThat(state.vpcId).isEqualTo("vpc-123")
        assertThat(state.infrastructureStatus).isEqualTo(InfrastructureStatus.UP)
        assertThat(state.tailscaleDeviceId).isEqualTo("nControl0CNTRL")
    }

    @ParameterizedTest
    @ValueSource(strings = ["current", "vpc", "all", "packer"])
    fun `a dry run that finds nothing to remove changes nothing`(mode: String) {
        val empty = TeardownResult(success = true, resourcesDeleted = emptyList())
        whenever(teardownService.teardownVpc(eq("vpc-123"), any())).thenReturn(empty)
        whenever(teardownService.teardownAllTagged(any(), any())).thenReturn(empty)
        whenever(teardownService.teardownPackerInfrastructure(any())).thenReturn(empty)

        val exitCode =
            Down()
                .apply {
                    dryRun = true
                    if (mode == "vpc") vpcId = "vpc-123"
                    teardownAll = mode == "all"
                    teardownPacker = mode == "packer"
                }.call()

        val state = stateManager.load()
        assertThat(exitCode).isEqualTo(0)
        assertThat(output()).doesNotContain("Stopped SOCKS5 proxy").doesNotContain("marked as DOWN")
        assertThat(state.vpcId).isEqualTo("vpc-123")
        assertThat(state.infrastructureStatus).isEqualTo(InfrastructureStatus.UP)
        assertThat(state.tailscaleDeviceId).isEqualTo("nControl0CNTRL")
    }

    @Test
    fun `a dry run whose VPC is gone but whose tailnet device is recorded only previews the device removal`() {
        stateManager.save(
            ClusterState(name = "test-cluster", versions = mutableMapOf(), vpcId = null).apply {
                markInfrastructureUp()
                tailscaleDeviceId = "nControl0CNTRL"
            },
        )

        val exitCode = Down().apply { dryRun = true }.call()

        val state = stateManager.load()
        assertThat(exitCode).isEqualTo(0)
        assertThat(output())
            .contains("DRY RUN")
            .contains("nControl0CNTRL")
            .doesNotContain("Stopped SOCKS5 proxy")
            .doesNotContain("marked as DOWN")
        assertThat(state.tailscaleDeviceId).isEqualTo("nControl0CNTRL")
        assertThat(state.infrastructureStatus).isEqualTo(InfrastructureStatus.UP)
        verify(getKoin().get<SocksProxyService>(), never()).stop()
        verify(getKoin().get<TailscaleService>(), never()).deleteDevice(any(), any(), any())
    }

    private companion object {
        const val STOPPED_PID = 4242
    }
}
