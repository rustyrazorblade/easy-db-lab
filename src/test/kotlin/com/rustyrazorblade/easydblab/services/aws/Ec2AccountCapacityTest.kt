package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.providers.aws.InstanceCreationConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypesRequest
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypesResponse
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.ec2.model.Instance
import software.amazon.awssdk.services.ec2.model.InstanceLifecycleType
import software.amazon.awssdk.services.ec2.model.InstanceTypeInfo
import software.amazon.awssdk.services.ec2.model.Reservation
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse
import software.amazon.awssdk.services.ec2.model.VCpuInfo
import software.amazon.awssdk.services.ec2.paginators.DescribeInstanceTypesIterable
import software.amazon.awssdk.services.ec2.paginators.DescribeInstancesIterable
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient
import software.amazon.awssdk.services.servicequotas.model.GetServiceQuotaRequest
import software.amazon.awssdk.services.servicequotas.model.GetServiceQuotaResponse
import software.amazon.awssdk.services.servicequotas.model.ServiceQuota
import java.time.Duration

/**
 * The parts of [Ec2AccountCapacity] LocalStack cannot exercise, tested at the SDK boundary: the
 * quota reads (LocalStack serves no Service Quotas), which instances count against the On-Demand
 * standard vCPU quota (LocalStack does not model Spot lifecycles), and how the RunInstances dry
 * run maps AWS's answer, including a throttle that must be retried rather than reported as a
 * refusal. `Ec2AccountCapacityIntegrationTest` covers the rest against LocalStack.
 */
class Ec2AccountCapacityTest {
    private val ec2: Ec2Client = mock()
    private val quotas: ServiceQuotasClient = mock()
    private val capacity = Ec2AccountCapacity(ec2, quotas, throttleBackoff = Duration.ofMillis(1))

    init {
        whenever(ec2.describeInstancesPaginator(any<DescribeInstancesRequest>())).thenAnswer {
            DescribeInstancesIterable(ec2, it.getArgument(0))
        }
        whenever(ec2.describeInstanceTypesPaginator(any<DescribeInstanceTypesRequest>())).thenAnswer {
            DescribeInstanceTypesIterable(ec2, it.getArgument(0))
        }
    }

    private fun ec2Error(
        status: Int,
        code: String,
        message: String = "",
    ): Ec2Exception =
        Ec2Exception
            .builder()
            .statusCode(status)
            .awsErrorDetails(
                AwsErrorDetails
                    .builder()
                    .errorCode(code)
                    .errorMessage(message)
                    .build(),
            ).build() as Ec2Exception

    private val launch =
        InstanceCreationConfig(
            serverType = ServerType.Cassandra,
            indexes = listOf(0, 1),
            instanceType = "i4i.xlarge",
            amiId = "ami-1",
            keyName = "key",
            securityGroupId = "sg-1",
            subnetIds = listOf("subnet-a"),
            iamInstanceProfile = "profile",
            ebsConfig = null,
            tags = emptyMap(),
            clusterName = "lab",
        )

    @Test
    fun `the standard vCPU quota is read from Service Quotas`() {
        whenever(quotas.getServiceQuota(any<GetServiceQuotaRequest>())).thenReturn(
            GetServiceQuotaResponse.builder().quota(ServiceQuota.builder().value(1152.0).build()).build(),
        )

        assertThat(capacity.standardVcpuQuota()).isEqualTo(1152)

        val request = argumentCaptor<GetServiceQuotaRequest>()
        verify(quotas).getServiceQuota(request.capture())
        assertThat(request.firstValue.serviceCode()).isEqualTo("ec2")
        assertThat(request.firstValue.quotaCode()).isEqualTo("L-1216C47A")
    }

    @Test
    fun `vCPUs in use count pending and running On-Demand standard instances, not Spot or other families`() {
        fun instance(
            type: String,
            spot: Boolean = false,
        ) = Instance
            .builder()
            .instanceType(type)
            .apply { if (spot) instanceLifecycle(InstanceLifecycleType.SPOT) }
            .build()
        whenever(ec2.describeInstances(any<DescribeInstancesRequest>())).thenReturn(
            DescribeInstancesResponse
                .builder()
                .reservations(
                    Reservation
                        .builder()
                        .instances(
                            instance("i4i.xlarge"),
                            instance("i4i.xlarge"),
                            instance("i4i.xlarge", spot = true),
                            instance("g5.xlarge"),
                            instance("m5.2xlarge"),
                        ).build(),
                ).build(),
        )
        whenever(ec2.describeInstanceTypes(any<DescribeInstanceTypesRequest>())).thenReturn(
            DescribeInstanceTypesResponse
                .builder()
                .instanceTypes(
                    InstanceTypeInfo
                        .builder()
                        .instanceType("i4i.xlarge")
                        .vCpuInfo(VCpuInfo.builder().defaultVCpus(4).build())
                        .build(),
                    InstanceTypeInfo
                        .builder()
                        .instanceType("m5.2xlarge")
                        .vCpuInfo(VCpuInfo.builder().defaultVCpus(8).build())
                        .build(),
                ).build(),
        )

        assertThat(capacity.standardVcpusInUse()).isEqualTo(4 + 4 + 8)

        val request = argumentCaptor<DescribeInstancesRequest>()
        verify(ec2).describeInstances(request.capture())
        assertThat(
            request.firstValue
                .filters()
                .single { it.name() == "instance-state-name" }
                .values(),
        ).containsExactlyInAnyOrder("pending", "running")
    }

    @Test
    fun `a dry run AWS would allow is permitted`() {
        whenever(ec2.runInstances(any<RunInstancesRequest>())).thenThrow(ec2Error(412, "DryRunOperation", "would have succeeded"))

        assertThat(capacity.dryRunLaunch(launch)).isEqualTo(LaunchDryRun.Permitted)
    }

    @Test
    fun `a refused dry run carries AWS's code and message, and is not retried`() {
        whenever(ec2.runInstances(any<RunInstancesRequest>()))
            .thenThrow(ec2Error(403, "UnauthorizedOperation", "You are not authorized to perform this operation."))

        val answer = capacity.dryRunLaunch(launch)

        assertThat(answer).isEqualTo(LaunchDryRun.Refused("UnauthorizedOperation", "You are not authorized to perform this operation."))
        verify(ec2, times(1)).runInstances(any<RunInstancesRequest>())
    }

    @Test
    fun `a throttled dry run is retried, and only the final answer is mapped`() {
        whenever(ec2.runInstances(any<RunInstancesRequest>()))
            .thenThrow(ec2Error(503, "RequestLimitExceeded"))
            .thenThrow(ec2Error(500, "InternalError"))
            .thenThrow(ec2Error(412, "DryRunOperation"))

        assertThat(capacity.dryRunLaunch(launch)).isEqualTo(LaunchDryRun.Permitted)
        verify(ec2, times(3)).runInstances(any<RunInstancesRequest>())
    }

    @Test
    fun `a dry run that returns instances instead of an error fails`() {
        whenever(ec2.runInstances(any<RunInstancesRequest>())).thenReturn(RunInstancesResponse.builder().build())

        assertThatThrownBy { capacity.dryRunLaunch(launch) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("DryRunOperation")
            .hasMessageContaining("lab")
    }
}
