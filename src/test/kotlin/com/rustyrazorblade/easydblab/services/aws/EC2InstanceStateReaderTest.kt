package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.EventBus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeInstanceStatusRequest
import software.amazon.awssdk.services.ec2.model.DescribeInstanceStatusResponse
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.ec2.model.InstanceStatus
import software.amazon.awssdk.services.ec2.model.InstanceStatusSummary
import software.amazon.awssdk.services.ec2.model.SummaryStatus
import java.time.Duration

/**
 * [EC2InstanceService] as the [InstanceStateReader] of `up`'s shared instance watcher: an ID EC2
 * does not know yet is left out rather than failing the poll, a throttle is retried, and an
 * instance counts as checked only when both status checks are OK. The EC2 client is mocked at the
 * SDK boundary.
 */
class EC2InstanceStateReaderTest {
    private val ec2: Ec2Client = mock()
    private val service =
        EC2InstanceService(ec2, EventBus(), region = "us-west-2", throttleBackoff = Duration.ofMillis(1))

    private fun ec2Error(
        status: Int,
        code: String,
    ): Ec2Exception =
        Ec2Exception
            .builder()
            .statusCode(status)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
            .build() as Ec2Exception

    private fun notFound() = ec2Error(400, Constants.AWS.EC2_INSTANCE_NOT_FOUND)

    @Test
    fun `the state read leaves out IDs EC2 does not know yet and retries a throttle`() {
        whenever(ec2.describeInstances(any<DescribeInstancesRequest>()))
            .thenThrow(ec2Error(503, "RequestLimitExceeded"))
            .thenThrow(notFound())

        assertThat(service.describeStates(listOf("i-1"))).isEmpty()
        verify(ec2, times(2)).describeInstances(any<DescribeInstancesRequest>())
    }

    @Test
    fun `the status read counts an instance only when both checks are OK`() {
        fun status(
            id: String,
            system: SummaryStatus,
            instance: SummaryStatus,
        ) = InstanceStatus
            .builder()
            .instanceId(id)
            .systemStatus(InstanceStatusSummary.builder().status(system).build())
            .instanceStatus(InstanceStatusSummary.builder().status(instance).build())
            .build()
        whenever(ec2.describeInstanceStatus(any<DescribeInstanceStatusRequest>())).thenReturn(
            DescribeInstanceStatusResponse
                .builder()
                .instanceStatuses(
                    status("i-1", SummaryStatus.OK, SummaryStatus.OK),
                    status("i-2", SummaryStatus.OK, SummaryStatus.INITIALIZING),
                    status("i-3", SummaryStatus.IMPAIRED, SummaryStatus.OK),
                ).build(),
        )

        assertThat(service.passedStatusChecks(listOf("i-1", "i-2", "i-3", "i-4"))).containsExactly("i-1")
    }

    @Test
    fun `the status read treats an ID EC2 does not know yet as not passed`() {
        whenever(ec2.describeInstanceStatus(any<DescribeInstanceStatusRequest>())).thenThrow(notFound())

        assertThat(service.passedStatusChecks(listOf("i-1"))).isEmpty()
    }

    @Test
    fun `both reads refuse more IDs than one request takes`() {
        val ids = (0..service.batchSize).map { "i-$it" }

        assertThatThrownBy { service.describeStates(ids) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.passedStatusChecks(ids) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
