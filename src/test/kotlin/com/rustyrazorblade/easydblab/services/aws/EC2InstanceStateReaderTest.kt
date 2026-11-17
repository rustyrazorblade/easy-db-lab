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
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.ec2.model.Instance
import software.amazon.awssdk.services.ec2.model.InstanceState
import software.amazon.awssdk.services.ec2.model.InstanceStateName
import software.amazon.awssdk.services.ec2.model.InstanceStatus
import software.amazon.awssdk.services.ec2.model.InstanceStatusSummary
import software.amazon.awssdk.services.ec2.model.Placement
import software.amazon.awssdk.services.ec2.model.Reservation
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
    fun `the state read retries a throttle`() {
        whenever(ec2.describeInstances(any<DescribeInstancesRequest>()))
            .thenThrow(ec2Error(503, "RequestLimitExceeded"))
            .thenReturn(
                DescribeInstancesResponse.builder().reservations(Reservation.builder().instances(runningInstance("i-1")).build()).build(),
            )

        assertThat(service.describeStates(listOf("i-1")).map { it.instanceId }).containsExactly("i-1")
        verify(ec2, times(2)).describeInstances(any<DescribeInstancesRequest>())
    }

    /**
     * EC2 fails a request naming an ID it does not know yet as a whole, which would hide every
     * other instance in the batch, so the IDs go in an `instance-id` filter: EC2 then returns the
     * ones it knows and leaves out the rest.
     */
    @Test
    fun `the state read asks by filter, so one ID EC2 does not know yet does not hide the others`() {
        val known = setOf("i-1", "i-3")
        whenever(ec2.describeInstances(any<DescribeInstancesRequest>())).thenAnswer { invocation ->
            val request = invocation.getArgument<DescribeInstancesRequest>(0)
            if (request.instanceIds().any { it !in known }) throw notFound()
            val asked = request.filters().filter { it.name() == "instance-id" }.flatMap { it.values() }
            DescribeInstancesResponse
                .builder()
                .reservations(
                    Reservation
                        .builder()
                        .instances(asked.filter { it in known }.map(::runningInstance))
                        .build(),
                ).build()
        }

        assertThat(service.describeStates(listOf("i-1", "i-2", "i-3")).map { it.instanceId }).containsExactlyInAnyOrder("i-1", "i-3")
    }

    private fun runningInstance(id: String): Instance =
        Instance
            .builder()
            .instanceId(id)
            .state(InstanceState.builder().name(InstanceStateName.RUNNING).build())
            .placement(Placement.builder().availabilityZone("us-west-2a").build())
            .build()

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
