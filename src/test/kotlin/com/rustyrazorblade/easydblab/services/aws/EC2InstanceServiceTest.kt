package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredInstance
import com.rustyrazorblade.easydblab.providers.aws.InstanceCreationConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse
import software.amazon.awssdk.services.ec2.model.Instance
import software.amazon.awssdk.services.ec2.model.InstanceState
import software.amazon.awssdk.services.ec2.model.InstanceStateName
import software.amazon.awssdk.services.ec2.model.Placement
import software.amazon.awssdk.services.ec2.model.Reservation
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse
import software.amazon.awssdk.services.ec2.model.Tag

internal class EC2InstanceServiceTest {
    private val mockEc2Client: Ec2Client = mock()
    private val mockOutputHandler: OutputHandler = mock()
    private val ec2InstanceService = EC2InstanceService(mockEc2Client, mockOutputHandler)

    @Test
    fun `findInstancesByClusterId should return empty map when no instances found`() {
        val response =
            DescribeInstancesResponse
                .builder()
                .reservations(emptyList())
                .build()

        whenever(mockEc2Client.describeInstances(any<DescribeInstancesRequest>())).thenReturn(response)

        val result = ec2InstanceService.findInstancesByClusterId("test-cluster-id")

        assertThat(result).isEmpty()
    }

    @Test
    fun `findInstancesByClusterId should return instances grouped by ServerType`() {
        val cassandraInstance =
            createInstance(
                instanceId = "i-db0",
                serverType = "db",
                alias = "db0",
                az = "us-east-1a",
            )
        val stressInstance =
            createInstance(
                instanceId = "i-app0",
                serverType = "app",
                alias = "app0",
                az = "us-east-1b",
            )
        val controlInstance =
            createInstance(
                instanceId = "i-control0",
                serverType = "control",
                alias = "control0",
                az = "us-east-1c",
            )

        val response =
            DescribeInstancesResponse
                .builder()
                .reservations(
                    Reservation.builder().instances(cassandraInstance, stressInstance, controlInstance).build(),
                ).build()

        whenever(mockEc2Client.describeInstances(any<DescribeInstancesRequest>())).thenReturn(response)

        val result = ec2InstanceService.findInstancesByClusterId("test-cluster-id")

        assertThat(result).hasSize(3)
        assertThat(result[ServerType.Cassandra]).hasSize(1)
        assertThat(result[ServerType.Stress]).hasSize(1)
        assertThat(result[ServerType.Control]).hasSize(1)

        val cassandra = result[ServerType.Cassandra]!!.first()
        assertThat(cassandra.instanceId).isEqualTo("i-db0")
        assertThat(cassandra.alias).isEqualTo("db0")
        assertThat(cassandra.availabilityZone).isEqualTo("us-east-1a")
        assertThat(cassandra.serverType).isEqualTo(ServerType.Cassandra)
    }

    @Test
    fun `findInstancesByClusterId should handle multiple instances of same type`() {
        val db0 =
            createInstance(
                instanceId = "i-db0",
                serverType = "db",
                alias = "db0",
                az = "us-east-1a",
            )
        val db1 =
            createInstance(
                instanceId = "i-db1",
                serverType = "db",
                alias = "db1",
                az = "us-east-1b",
            )
        val db2 =
            createInstance(
                instanceId = "i-db2",
                serverType = "db",
                alias = "db2",
                az = "us-east-1c",
            )

        val response =
            DescribeInstancesResponse
                .builder()
                .reservations(
                    Reservation.builder().instances(db0, db1, db2).build(),
                ).build()

        whenever(mockEc2Client.describeInstances(any<DescribeInstancesRequest>())).thenReturn(response)

        val result = ec2InstanceService.findInstancesByClusterId("test-cluster-id")

        assertThat(result).hasSize(1)
        assertThat(result[ServerType.Cassandra]).hasSize(3)

        val aliases = result[ServerType.Cassandra]!!.map { it.alias }
        assertThat(aliases).containsExactlyInAnyOrder("db0", "db1", "db2")
    }

    @Test
    fun `findInstancesByClusterId should skip instances with missing ServerType tag`() {
        val validInstance =
            createInstance(
                instanceId = "i-valid",
                serverType = "db",
                alias = "db0",
                az = "us-east-1a",
            )

        // Instance without ServerType tag
        val invalidInstance =
            Instance
                .builder()
                .instanceId("i-invalid")
                .publicIpAddress("1.2.3.4")
                .privateIpAddress("10.0.0.4")
                .state(InstanceState.builder().name(InstanceStateName.RUNNING).build())
                .placement(Placement.builder().availabilityZone("us-east-1a").build())
                .tags(
                    Tag
                        .builder()
                        .key("Name")
                        .value("orphan")
                        .build(),
                ).build()

        val response =
            DescribeInstancesResponse
                .builder()
                .reservations(
                    Reservation.builder().instances(validInstance, invalidInstance).build(),
                ).build()

        whenever(mockEc2Client.describeInstances(any<DescribeInstancesRequest>())).thenReturn(response)

        val result = ec2InstanceService.findInstancesByClusterId("test-cluster-id")

        assertThat(result[ServerType.Cassandra]).hasSize(1)
        assertThat(result[ServerType.Cassandra]!!.first().instanceId).isEqualTo("i-valid")
    }

    @Test
    fun `findInstancesByClusterId should skip instances with unknown ServerType`() {
        val validInstance =
            createInstance(
                instanceId = "i-valid",
                serverType = "db",
                alias = "db0",
                az = "us-east-1a",
            )

        // Instance with unknown ServerType
        val unknownTypeInstance =
            Instance
                .builder()
                .instanceId("i-unknown")
                .publicIpAddress("1.2.3.4")
                .privateIpAddress("10.0.0.4")
                .state(InstanceState.builder().name(InstanceStateName.RUNNING).build())
                .placement(Placement.builder().availabilityZone("us-east-1a").build())
                .tags(
                    Tag
                        .builder()
                        .key("Name")
                        .value("unknown0")
                        .build(),
                    Tag
                        .builder()
                        .key("ServerType")
                        .value("unknown_type")
                        .build(),
                ).build()

        val response =
            DescribeInstancesResponse
                .builder()
                .reservations(
                    Reservation.builder().instances(validInstance, unknownTypeInstance).build(),
                ).build()

        whenever(mockEc2Client.describeInstances(any<DescribeInstancesRequest>())).thenReturn(response)

        val result = ec2InstanceService.findInstancesByClusterId("test-cluster-id")

        assertThat(result).hasSize(1)
        assertThat(result[ServerType.Cassandra]).hasSize(1)
    }

    @Test
    fun `toClusterHost should convert DiscoveredInstance correctly`() {
        val discovered =
            DiscoveredInstance(
                instanceId = "i-123",
                publicIp = "1.2.3.4",
                privateIp = "10.0.0.1",
                alias = "db0",
                availabilityZone = "us-east-1a",
                serverType = ServerType.Cassandra,
                state = "running",
            )

        val clusterHost = discovered.toClusterHost()

        assertThat(clusterHost.publicIp).isEqualTo("1.2.3.4")
        assertThat(clusterHost.privateIp).isEqualTo("10.0.0.1")
        assertThat(clusterHost.alias).isEqualTo("db0")
        assertThat(clusterHost.availabilityZone).isEqualTo("us-east-1a")
        assertThat(clusterHost.instanceId).isEqualTo("i-123")
    }

    @Test
    fun `toClusterHost should handle null IPs`() {
        val discovered =
            DiscoveredInstance(
                instanceId = "i-123",
                publicIp = null,
                privateIp = null,
                alias = "db0",
                availabilityZone = "us-east-1a",
                serverType = ServerType.Cassandra,
                state = "running",
            )

        val clusterHost = discovered.toClusterHost()

        assertThat(clusterHost.publicIp).isEqualTo("")
        assertThat(clusterHost.privateIp).isEqualTo("")
    }

    @Test
    fun `createInstances should distribute across AZs using instanceIndex not loop index`() {
        // 3 subnets representing 3 AZs
        val subnets = listOf("subnet-a", "subnet-b", "subnet-c")

        // Config with startIndex=1 (simulating adding to existing cluster with 1 instance)
        val config =
            InstanceCreationConfig(
                serverType = ServerType.Cassandra,
                count = 2,
                instanceType = "m5.large",
                amiId = "ami-123",
                keyName = "test-key",
                securityGroupId = "sg-123",
                subnetIds = subnets,
                iamInstanceProfile = "test-profile",
                ebsConfig = null,
                tags = mapOf("easy_cass_lab" to "1"),
                clusterName = "test-cluster",
                startIndex = 1, // Adding instances starting from index 1
            )

        // Mock runInstances to return a valid response
        val mockInstance =
            Instance
                .builder()
                .instanceId("i-test")
                .publicIpAddress("1.2.3.4")
                .privateIpAddress("10.0.0.1")
                .placement(Placement.builder().availabilityZone("us-east-1a").build())
                .build()

        val runResponse =
            RunInstancesResponse
                .builder()
                .instances(mockInstance)
                .build()

        whenever(mockEc2Client.runInstances(any<RunInstancesRequest>())).thenReturn(runResponse)

        ec2InstanceService.createInstances(config)

        // Capture the requests to verify subnet selection
        val requestCaptor = argumentCaptor<RunInstancesRequest>()
        verify(mockEc2Client, times(2)).runInstances(requestCaptor.capture())

        val capturedRequests = requestCaptor.allValues

        // With startIndex=1:
        // - First instance (instanceIndex=1): subnet[1 % 3] = subnet-b
        // - Second instance (instanceIndex=2): subnet[2 % 3] = subnet-c
        assertThat(capturedRequests[0].subnetId()).isEqualTo("subnet-b")
        assertThat(capturedRequests[1].subnetId()).isEqualTo("subnet-c")
    }

    @Test
    fun `createInstances should wrap around AZs correctly with startIndex`() {
        // 3 subnets representing 3 AZs
        val subnets = listOf("subnet-a", "subnet-b", "subnet-c")

        // Config with startIndex=3 (simulating adding to cluster with 3 existing instances)
        val config =
            InstanceCreationConfig(
                serverType = ServerType.Cassandra,
                count = 2,
                instanceType = "m5.large",
                amiId = "ami-123",
                keyName = "test-key",
                securityGroupId = "sg-123",
                subnetIds = subnets,
                iamInstanceProfile = "test-profile",
                ebsConfig = null,
                tags = mapOf("easy_cass_lab" to "1"),
                clusterName = "test-cluster",
                startIndex = 3, // Adding instances starting from index 3
            )

        val mockInstance =
            Instance
                .builder()
                .instanceId("i-test")
                .publicIpAddress("1.2.3.4")
                .privateIpAddress("10.0.0.1")
                .placement(Placement.builder().availabilityZone("us-east-1a").build())
                .build()

        val runResponse =
            RunInstancesResponse
                .builder()
                .instances(mockInstance)
                .build()

        whenever(mockEc2Client.runInstances(any<RunInstancesRequest>())).thenReturn(runResponse)

        ec2InstanceService.createInstances(config)

        val requestCaptor = argumentCaptor<RunInstancesRequest>()
        verify(mockEc2Client, times(2)).runInstances(requestCaptor.capture())

        val capturedRequests = requestCaptor.allValues

        // With startIndex=3:
        // - First instance (instanceIndex=3): subnet[3 % 3] = subnet-a (wraps around)
        // - Second instance (instanceIndex=4): subnet[4 % 3] = subnet-b
        assertThat(capturedRequests[0].subnetId()).isEqualTo("subnet-a")
        assertThat(capturedRequests[1].subnetId()).isEqualTo("subnet-b")
    }

    @Test
    fun `waitForInstanceStatusOk should return immediately for empty list`() {
        // Should not throw and should not call the EC2 client
        ec2InstanceService.waitForInstanceStatusOk(emptyList())

        // Verify no calls were made to the EC2 client (waiter is never created for empty list)
        // Note: The waiter uses its own client internally, so we verify no status calls were made
        verify(
            mockEc2Client,
            times(0),
        ).describeInstanceStatus(
            any<software.amazon.awssdk.services.ec2.model.DescribeInstanceStatusRequest>(),
        )
    }

    private fun createInstance(
        instanceId: String,
        serverType: String,
        alias: String,
        az: String,
        publicIp: String = "1.2.3.4",
        privateIp: String = "10.0.0.1",
    ): Instance =
        Instance
            .builder()
            .instanceId(instanceId)
            .publicIpAddress(publicIp)
            .privateIpAddress(privateIp)
            .state(InstanceState.builder().name(InstanceStateName.RUNNING).build())
            .placement(Placement.builder().availabilityZone(az).build())
            .tags(
                Tag
                    .builder()
                    .key("Name")
                    .value(alias)
                    .build(),
                Tag
                    .builder()
                    .key("ServerType")
                    .value(serverType)
                    .build(),
                Tag
                    .builder()
                    .key("ClusterId")
                    .value("test-cluster-id")
                    .build(),
            ).build()
}
