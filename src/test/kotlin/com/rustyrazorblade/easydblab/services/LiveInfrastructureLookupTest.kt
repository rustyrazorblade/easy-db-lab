package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredResources
import com.rustyrazorblade.easydblab.providers.aws.TeardownResult
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * `init --clean` asks this whether the workspace's cluster still has infrastructure in AWS, so
 * it must not delete the only local record of the VPC while resources run.
 */
class LiveInfrastructureLookupTest {
    @TempDir
    lateinit var dir: File

    private lateinit var stateManager: ClusterStateManager
    private val infrastructure: AwsInfrastructureService = mock()
    private lateinit var lookup: LiveInfrastructureLookup

    @BeforeEach
    fun setUp() {
        stateManager = ClusterStateManager(File(dir, "state.json"))
        lookup = LiveInfrastructureLookup(stateManager, infrastructure)
    }

    private fun saveState(configure: ClusterState.() -> Unit = {}) {
        stateManager.save(ClusterState(name = "lab", versions = mutableMapOf()).apply(configure))
    }

    @Test
    fun `a workspace with no cluster state has nothing live and asks AWS nothing`() {
        assertThat(lookup.find()).isNull()

        verify(infrastructure, never()).teardownVpc(any(), any())
    }

    @Test
    fun `a cluster state that records no VPC has nothing live and asks AWS nothing`() {
        saveState()

        assertThat(lookup.find()).isNull()

        verify(infrastructure, never()).teardownVpc(any(), any())
    }

    @Test
    fun `a VPC with instances is live, found by a dry-run discovery of the whole VPC`() {
        saveState { vpcId = "vpc-1" }
        val found = DiscoveredResources(vpcId = "vpc-1", instanceIds = listOf("i-1", "i-2"), subnetIds = listOf("subnet-1"))
        whenever(infrastructure.teardownVpc("vpc-1", true)).thenReturn(TeardownResult.success(found))

        assertThat(lookup.find()).isEqualTo(found)
    }

    @Test
    fun `a VPC that holds only subnets is still live, because the VPC would be orphaned`() {
        saveState { vpcId = "vpc-1" }
        val found = DiscoveredResources(vpcId = "vpc-1", subnetIds = listOf("subnet-1"))
        whenever(infrastructure.teardownVpc("vpc-1", true)).thenReturn(TeardownResult.success(found))

        assertThat(lookup.find()?.nonInstanceResourceCount()).isEqualTo(1)
    }

    @Test
    fun `a VPC AWS shows nothing in is not live`() {
        saveState { vpcId = "vpc-1" }
        whenever(infrastructure.teardownVpc("vpc-1", true))
            .thenReturn(TeardownResult.success(DiscoveredResources(vpcId = "vpc-1")))

        assertThat(lookup.find()).isNull()
    }

    @Test
    fun `a VPC supplied with init --vpc is searched by the cluster's own tag, never by membership`() {
        saveState {
            vpcId = "vpc-user"
            vpcSupplied = true
        }
        val clusterId = stateManager.load().clusterId
        val found = DiscoveredResources(vpcId = "vpc-user", instanceIds = listOf("i-9"), ownsVpc = false)
        whenever(infrastructure.teardownClusterInSuppliedVpc("vpc-user", clusterId, true)).thenReturn(TeardownResult.success(found))

        assertThat(lookup.find()).isEqualTo(found)
        verify(infrastructure, never()).teardownVpc(any(), any())
    }

    @Test
    fun `an AWS failure propagates, so a cluster that cannot be checked is not treated as gone`() {
        saveState { vpcId = "vpc-1" }
        whenever(infrastructure.teardownVpc("vpc-1", true)).thenThrow(IllegalStateException("AWS unreachable"))

        assertThatThrownBy { lookup.find() }.hasMessageContaining("AWS unreachable")
    }
}
