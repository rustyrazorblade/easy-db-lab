package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.exceptions.AwsTimeoutException
import com.rustyrazorblade.easydblab.providers.aws.AccountIdentity
import com.rustyrazorblade.easydblab.providers.aws.IamRoles
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse
import software.amazon.awssdk.services.ec2.model.DescribeNatGatewaysRequest
import software.amazon.awssdk.services.ec2.model.DescribeNatGatewaysResponse
import software.amazon.awssdk.services.ec2.model.DescribeNetworkInterfacesRequest
import software.amazon.awssdk.services.ec2.model.DescribeNetworkInterfacesResponse
import software.amazon.awssdk.services.ec2.model.Instance
import software.amazon.awssdk.services.ec2.model.InstanceState
import software.amazon.awssdk.services.ec2.model.InstanceStateName
import software.amazon.awssdk.services.ec2.model.NatGateway
import software.amazon.awssdk.services.ec2.model.NatGatewayState
import software.amazon.awssdk.services.ec2.model.NetworkInterface
import software.amazon.awssdk.services.ec2.model.Reservation
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.emr.model.Cluster
import software.amazon.awssdk.services.emr.model.ClusterState
import software.amazon.awssdk.services.emr.model.ClusterStatus
import software.amazon.awssdk.services.emr.model.DescribeClusterRequest
import software.amazon.awssdk.services.emr.model.DescribeClusterResponse
import software.amazon.awssdk.services.opensearch.OpenSearchClient
import software.amazon.awssdk.services.opensearch.model.DescribeDomainRequest
import software.amazon.awssdk.services.opensearch.model.DescribeDomainResponse
import software.amazon.awssdk.services.opensearch.model.DomainStatus
import java.time.Duration

/**
 * Every hand-written AWS wait (EC2, EMR, OpenSearch) polls through `pollUntilOrTimeout`: a describe
 * call that fails with a throttle (HTTP 429, which `withAwsRetry` does not retry) does not fail the
 * wait, one look is made per iteration, and a wait that never converges raises
 * [AwsTimeoutException]. Runs with a 1 ms poll interval.
 */
internal class AwsWaitsTransientErrorTest {
    private val eventBus = EventBus()
    private val interval = Duration.ofMillis(1)

    private fun throttle(): AwsServiceException =
        AwsServiceException
            .builder()
            .statusCode(429)
            .message("Rate exceeded")
            .build()

    // ---------- EC2 ----------

    private val ec2Client: Ec2Client = mock()
    private val vpcService = EC2VpcService(ec2Client, eventBus, pollInterval = interval)

    private fun instancesIn(state: InstanceStateName): DescribeInstancesResponse =
        DescribeInstancesResponse
            .builder()
            .reservations(
                Reservation
                    .builder()
                    .instances(Instance.builder().state(InstanceState.builder().name(state).build()).build())
                    .build(),
            ).build()

    @Test
    fun `instances terminated wait retries a throttled describe and looks once per iteration`() {
        var looks = 0
        doAnswer {
            when (++looks) {
                1 -> throw throttle()
                2 -> instancesIn(InstanceStateName.SHUTTING_DOWN)
                else -> instancesIn(InstanceStateName.TERMINATED)
            }
        }.whenever(ec2Client).describeInstances(any<DescribeInstancesRequest>())

        vpcService.waitForInstancesTerminated(listOf("i-1"), 5_000)

        verify(ec2Client, times(3)).describeInstances(any<DescribeInstancesRequest>())
    }

    @Test
    fun `instances terminated wait raises AwsTimeoutException when they never terminate`() {
        whenever(ec2Client.describeInstances(any<DescribeInstancesRequest>())).thenReturn(instancesIn(InstanceStateName.RUNNING))

        assertThatThrownBy { vpcService.waitForInstancesTerminated(listOf("i-1"), 50) }
            .isInstanceOf(AwsTimeoutException::class.java)
            .hasMessage("Timeout waiting for instances to terminate after 50ms")
    }

    private fun natGateways(state: NatGatewayState): DescribeNatGatewaysResponse =
        DescribeNatGatewaysResponse
            .builder()
            .natGateways(NatGateway.builder().state(state).build())
            .build()

    @Test
    fun `NAT gateway wait retries a throttled describe and looks once per iteration`() {
        var looks = 0
        doAnswer {
            when (++looks) {
                1 -> throw throttle()
                2 -> natGateways(NatGatewayState.DELETING)
                else -> natGateways(NatGatewayState.DELETED)
            }
        }.whenever(ec2Client).describeNatGateways(any<DescribeNatGatewaysRequest>())

        vpcService.waitForNatGatewaysDeleted(listOf("nat-1"), 5_000)

        verify(ec2Client, times(3)).describeNatGateways(any<DescribeNatGatewaysRequest>())
    }

    @Test
    fun `NAT gateway wait raises AwsTimeoutException when they never delete`() {
        whenever(ec2Client.describeNatGateways(any<DescribeNatGatewaysRequest>())).thenReturn(natGateways(NatGatewayState.DELETING))

        assertThatThrownBy { vpcService.waitForNatGatewaysDeleted(listOf("nat-1"), 50) }
            .isInstanceOf(AwsTimeoutException::class.java)
            .hasMessage("Timeout waiting for NAT gateways to be deleted after 50ms")
    }

    private fun enis(vararg ids: String): DescribeNetworkInterfacesResponse =
        DescribeNetworkInterfacesResponse
            .builder()
            .networkInterfaces(ids.map { NetworkInterface.builder().networkInterfaceId(it).build() })
            .build()

    @Test
    fun `network interface wait retries a throttled describe and looks once per iteration`() {
        var looks = 0
        doAnswer {
            when (++looks) {
                1 -> enis("eni-1")
                2 -> throw throttle()
                3 -> enis("eni-1")
                else -> enis()
            }
        }.whenever(ec2Client).describeNetworkInterfaces(any<DescribeNetworkInterfacesRequest>())

        vpcService.waitForNetworkInterfacesCleared("vpc-1", 5_000)

        // one look to see whether to wait at all, then one per iteration
        verify(ec2Client, times(4)).describeNetworkInterfaces(any<DescribeNetworkInterfacesRequest>())
    }

    @Test
    fun `network interface wait raises AwsTimeoutException when they never clear`() {
        whenever(ec2Client.describeNetworkInterfaces(any<DescribeNetworkInterfacesRequest>())).thenReturn(enis("eni-1"))

        assertThatThrownBy { vpcService.waitForNetworkInterfacesCleared("vpc-1", 50) }
            .isInstanceOf(AwsTimeoutException::class.java)
            .hasMessage("Timeout waiting for network interfaces to clear in VPC vpc-1 after 50ms")
    }

    // ---------- EMR ----------

    private val emrClient: EmrClient = mock()
    private val emrService = EMRService(emrClient, eventBus, readyPollInterval = interval, terminationPollInterval = interval)

    private fun cluster(state: ClusterState): DescribeClusterResponse =
        DescribeClusterResponse
            .builder()
            .cluster(
                Cluster
                    .builder()
                    .name("test")
                    .masterPublicDnsName("ec2-1.example.com")
                    .status(ClusterStatus.builder().state(state).build())
                    .build(),
            ).build()

    @Test
    fun `cluster ready wait retries a throttled describe and returns the cluster details`() {
        var looks = 0
        doAnswer {
            when (++looks) {
                1 -> throw throttle()
                2 -> cluster(ClusterState.STARTING)
                else -> cluster(ClusterState.WAITING)
            }
        }.whenever(emrClient).describeCluster(any<DescribeClusterRequest>())

        val result = emrService.waitForClusterReady("j-1", 5_000)

        assertThat(result.masterPublicDns).isEqualTo("ec2-1.example.com")
        assertThat(result.state).isEqualTo("WAITING")
    }

    @Test
    fun `cluster ready wait fails at once, not by timeout, when the cluster terminated`() {
        whenever(emrClient.describeCluster(any<DescribeClusterRequest>())).thenReturn(cluster(ClusterState.TERMINATED_WITH_ERRORS))

        assertThatThrownBy { emrService.waitForClusterReady("j-1", 5_000) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("EMR cluster j-1 failed: TERMINATED_WITH_ERRORS")
        verify(emrClient, times(1)).describeCluster(any<DescribeClusterRequest>())
    }

    @Test
    fun `cluster ready wait raises AwsTimeoutException when the cluster never becomes ready`() {
        whenever(emrClient.describeCluster(any<DescribeClusterRequest>())).thenReturn(cluster(ClusterState.STARTING))

        assertThatThrownBy { emrService.waitForClusterReady("j-1", 50) }
            .isInstanceOf(AwsTimeoutException::class.java)
            .hasMessage("Timeout waiting for EMR cluster j-1 to be ready after 50ms")
    }

    @Test
    fun `cluster terminated wait retries a throttled describe`() {
        var looks = 0
        doAnswer {
            when (++looks) {
                1 -> throw throttle()
                2 -> cluster(ClusterState.TERMINATING)
                else -> cluster(ClusterState.TERMINATED)
            }
        }.whenever(emrClient).describeCluster(any<DescribeClusterRequest>())

        emrService.waitForClusterTerminated("j-1", 5_000)

        verify(emrClient, times(3)).describeCluster(any<DescribeClusterRequest>())
    }

    @Test
    fun `bulk cluster terminated wait describes each cluster once per iteration and retries a throttle`() {
        var looks = 0
        doAnswer {
            when (++looks) {
                1 -> throw throttle()
                2, 3 -> cluster(ClusterState.TERMINATING)
                else -> cluster(ClusterState.TERMINATED)
            }
        }.whenever(emrClient).describeCluster(any<DescribeClusterRequest>())

        emrService.waitForClustersTerminated(listOf("j-1", "j-2"), 5_000)

        // look 1 throws before any cluster is read; look 2 reads both (2 describes), look 3 reads both
        verify(emrClient, times(5)).describeCluster(any<DescribeClusterRequest>())
    }

    @Test
    fun `bulk cluster terminated wait raises AwsTimeoutException when a cluster never terminates`() {
        whenever(emrClient.describeCluster(any<DescribeClusterRequest>())).thenReturn(cluster(ClusterState.TERMINATING))

        assertThatThrownBy { emrService.waitForClustersTerminated(listOf("j-1"), 50) }
            .isInstanceOf(AwsTimeoutException::class.java)
            .hasMessage("Timeout waiting for EMR clusters to terminate after 50ms")
    }

    // ---------- OpenSearch ----------

    private val openSearchClient: OpenSearchClient = mock()
    private val openSearchService = OpenSearchService(openSearchClient, mock<IamRoles>(), mock<AccountIdentity>(), eventBus)

    private fun domain(
        processing: Boolean,
        endpoint: String? = null,
    ): DescribeDomainResponse =
        DescribeDomainResponse
            .builder()
            .domainStatus(
                DomainStatus
                    .builder()
                    .domainName("d")
                    .domainId("1/d")
                    .processing(processing)
                    .deleted(false)
                    .endpoint(endpoint)
                    .build(),
            ).build()

    @Test
    fun `domain active wait retries a throttled describe`() {
        var looks = 0
        doAnswer {
            when (++looks) {
                1 -> throw throttle()
                2 -> domain(processing = true)
                else -> domain(processing = false, endpoint = "search-d.example.com")
            }
        }.whenever(openSearchClient).describeDomain(any<DescribeDomainRequest>())

        val result = openSearchService.waitForDomainActive("d", 5_000, 1)

        assertThat(result.endpoint).isEqualTo("search-d.example.com")
        verify(openSearchClient, times(3)).describeDomain(any<DescribeDomainRequest>())
    }

    @Test
    fun `domain deleted wait retries a throttled describe`() {
        var looks = 0
        doAnswer {
            when (++looks) {
                1 -> throw throttle()
                2 -> domain(processing = true, endpoint = "search-d.example.com")
                else ->
                    throw software.amazon.awssdk.services.opensearch.model.ResourceNotFoundException
                        .builder()
                        .message("gone")
                        .build()
            }
        }.whenever(openSearchClient).describeDomain(any<DescribeDomainRequest>())

        openSearchService.waitForDomainDeleted("d", 5_000, 1)

        verify(openSearchClient, times(3)).describeDomain(any<DescribeDomainRequest>())
    }
}
