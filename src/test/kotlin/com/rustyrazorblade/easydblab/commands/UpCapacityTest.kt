package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredInstance
import com.rustyrazorblade.easydblab.services.aws.LaunchDryRun
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * `up` checks that the account can hold the instances it is about to launch, and that AWS would
 * accept the launch, before its first RunInstances: a cluster that cannot fit launches nothing,
 * and a re-run checks only the instances it adds.
 */
class UpCapacityTest : UpTestFixture() {
    private fun discovered(alias: String) =
        DiscoveredInstance(
            instanceId = "i-$alias",
            publicIp = "54.1.1.2",
            privateIp = "10.0.0.2",
            alias = alias,
            availabilityZone = "us-west-2a",
            serverType = ServerType.Cassandra,
            state = "running",
        )

    @Test
    fun `a cluster the account cannot hold launches nothing`() {
        whenever(mockClusterStateManager.load()).thenReturn(happyState(cassandraInstances = 3))
        accountCapacity.standardQuota = 8

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("vCPU")

        verify(mockClusterProvisioningService, never()).provisionAll(any(), any(), any(), any())
    }

    @Test
    fun `a re-run checks only the instances it adds`() {
        // db0..db2 run already; two more db nodes and nothing else are to be launched.
        whenever(mockClusterStateManager.load()).thenReturn(happyState(cassandraInstances = 5, stressInstances = 0))
        whenever(mockEc2InstanceService.findInstancesByClusterId(any())).thenReturn(
            mapOf(ServerType.Cassandra to listOf("db0", "db1", "db2").map(::discovered)),
        )
        // Room for exactly 2 x 4 db vCPUs and the control node's 4.
        accountCapacity.standardQuota = 12

        newUp().execute()

        verify(mockClusterProvisioningService).provisionAll(any(), any(), any(), any())
        assertThat(accountCapacity.dryRuns.map { it.serverType to it.count })
            .containsExactlyInAnyOrder(ServerType.Cassandra to 2, ServerType.Control to 1)
    }

    @Test
    fun `the dry run asks with the real AMI, subnet and security group`() {
        newUp().execute()

        assertThat(accountCapacity.dryRuns).isNotEmpty.allSatisfy { launch ->
            assertThat(launch.amiId).isEqualTo("ami-123")
            assertThat(launch.subnetIds).containsExactly("subnet-1")
            assertThat(launch.securityGroupId).isEqualTo("sg-1")
            assertThat(launch.keyName).isEqualTo("test-key")
        }
    }

    @Test
    fun `a launch AWS would refuse launches nothing`() {
        accountCapacity.dryRun = { LaunchDryRun.Refused("UnauthorizedOperation", "not authorized") }

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not authorized")

        verify(mockClusterProvisioningService, never()).provisionAll(any(), any(), any(), any())
    }
}
