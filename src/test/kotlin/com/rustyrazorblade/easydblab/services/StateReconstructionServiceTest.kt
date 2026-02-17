package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredInstance
import com.rustyrazorblade.easydblab.providers.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for StateReconstructionService.
 *
 * This service reconstructs ClusterState from AWS resources when the local state.json
 * is missing but the VPC and resources still exist in AWS.
 */
internal class StateReconstructionServiceTest {
    private val mockVpcService: VpcService = mock()
    private val mockEc2InstanceService: EC2InstanceService = mock()

    private val service = DefaultStateReconstructionService(mockVpcService, mockEc2InstanceService)

    @Test
    fun `reconstructFromVpc should return ClusterState with discovered instances`() {
        val vpcId = "vpc-12345"
        val clusterId = "abc123-full-uuid"
        val clusterName = "my-cluster"
        val bucketName = "easy-db-lab-my-cluster-abc12345"

        // Setup VPC tags including bucket
        val vpcTags =
            mapOf(
                "Name" to clusterName,
                "ClusterId" to clusterId,
                "bucket" to bucketName,
            )
        whenever(mockVpcService.getVpcTags(vpcId)).thenReturn(vpcTags)

        // Setup discovered instances
        val cassandraInstance =
            DiscoveredInstance(
                instanceId = "i-cassandra1",
                publicIp = "1.2.3.4",
                privateIp = "10.0.1.10",
                alias = "db0",
                availabilityZone = "us-west-2a",
                serverType = ServerType.Cassandra,
                state = "running",
            )
        val controlInstance =
            DiscoveredInstance(
                instanceId = "i-control1",
                publicIp = "1.2.3.5",
                privateIp = "10.0.1.11",
                alias = "control0",
                availabilityZone = "us-west-2a",
                serverType = ServerType.Control,
                state = "running",
            )

        val instancesByType =
            mapOf(
                ServerType.Cassandra to listOf(cassandraInstance),
                ServerType.Control to listOf(controlInstance),
            )
        whenever(mockEc2InstanceService.findInstancesByClusterId(clusterId)).thenReturn(instancesByType)

        // Setup infrastructure discovery
        whenever(mockVpcService.findSubnetsInVpc(vpcId)).thenReturn(listOf("subnet-1", "subnet-2"))
        whenever(mockVpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(listOf("sg-12345"))
        whenever(mockVpcService.findInternetGatewayByVpc(vpcId)).thenReturn("igw-12345")

        // Execute
        val result = service.reconstructFromVpc(vpcId)

        // Verify
        assertThat(result.name).isEqualTo(clusterName)
        assertThat(result.clusterId).isEqualTo(clusterId)
        assertThat(result.vpcId).isEqualTo(vpcId)
        assertThat(result.s3Bucket).isEqualTo(bucketName)

        // Verify hosts
        assertThat(result.hosts).containsKey(ServerType.Cassandra)
        assertThat(result.hosts).containsKey(ServerType.Control)
        assertThat(result.hosts[ServerType.Cassandra]).hasSize(1)
        assertThat(result.hosts[ServerType.Cassandra]?.first()?.alias).isEqualTo("db0")
        assertThat(result.hosts[ServerType.Control]?.first()?.alias).isEqualTo("control0")

        // Verify infrastructure
        assertThat(result.infrastructure).isNotNull
        assertThat(result.infrastructure?.vpcId).isEqualTo(vpcId)
        assertThat(result.infrastructure?.subnetIds).containsExactly("subnet-1", "subnet-2")
        assertThat(result.infrastructure?.securityGroupId).isEqualTo("sg-12345")
        assertThat(result.infrastructure?.internetGatewayId).isEqualTo("igw-12345")
    }

    @Test
    fun `reconstructFromVpc should throw when VPC has no ClusterId tag`() {
        val vpcId = "vpc-no-clusterid"

        // Setup VPC tags without ClusterId
        val vpcTags =
            mapOf(
                "Name" to "my-cluster",
            )
        whenever(mockVpcService.getVpcTags(vpcId)).thenReturn(vpcTags)

        // Execute & Verify
        val exception =
            assertThrows<IllegalStateException> {
                service.reconstructFromVpc(vpcId)
            }
        assertThat(exception.message).contains("ClusterId")
    }

    @Test
    fun `reconstructFromVpc should use default name when Name tag is missing`() {
        val vpcId = "vpc-12345"
        val clusterId = "abc123-full-uuid"

        // Setup VPC tags without Name
        val vpcTags =
            mapOf(
                "ClusterId" to clusterId,
            )
        whenever(mockVpcService.getVpcTags(vpcId)).thenReturn(vpcTags)

        // Setup empty instances
        whenever(mockEc2InstanceService.findInstancesByClusterId(clusterId))
            .thenReturn(emptyMap())

        // Setup infrastructure discovery
        whenever(mockVpcService.findSubnetsInVpc(vpcId)).thenReturn(emptyList())
        whenever(mockVpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(emptyList())
        whenever(mockVpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)

        // Execute
        val result = service.reconstructFromVpc(vpcId)

        // Verify default name is used
        assertThat(result.name).isEqualTo("recovered-cluster")
    }

    @Test
    fun `reconstructFromVpc should handle missing bucket tag gracefully`() {
        val vpcId = "vpc-12345"
        val clusterId = "abc123-full-uuid"

        // Setup VPC tags without bucket
        val vpcTags =
            mapOf(
                "Name" to "my-cluster",
                "ClusterId" to clusterId,
            )
        whenever(mockVpcService.getVpcTags(vpcId)).thenReturn(vpcTags)

        // Setup empty instances
        whenever(mockEc2InstanceService.findInstancesByClusterId(clusterId))
            .thenReturn(emptyMap())

        // Setup infrastructure discovery
        whenever(mockVpcService.findSubnetsInVpc(vpcId)).thenReturn(emptyList())
        whenever(mockVpcService.findSecurityGroupsInVpc(vpcId)).thenReturn(emptyList())
        whenever(mockVpcService.findInternetGatewayByVpc(vpcId)).thenReturn(null)

        // Execute
        val result = service.reconstructFromVpc(vpcId)

        // Verify S3 bucket is null
        assertThat(result.s3Bucket).isNull()
    }
}
