package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.exceptions.AwsTimeoutException
import com.rustyrazorblade.easydblab.services.aws.EMRSparkService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.emr.model.DescribeStepRequest
import software.amazon.awssdk.services.emr.model.DescribeStepResponse
import software.amazon.awssdk.services.emr.model.Step
import software.amazon.awssdk.services.emr.model.StepState
import software.amazon.awssdk.services.emr.model.StepStatus
import java.time.Duration

/**
 * How `waitForJobCompletion` waits for an EMR step: a throttled describe is retried, a failure that
 * waiting cannot fix ends the wait at once, and a step that never finishes raises
 * [AwsTimeoutException] after the configured wait. Runs with a zero poll interval.
 */
class EMRSparkServiceJobWaitTest {
    private val emrClient: EmrClient = mock()

    private fun service(jobWaitTimeout: Duration = Duration.ofSeconds(5)) =
        EMRSparkService(
            emrClient,
            mock(),
            mock(),
            mock(),
            EventBus(),
            pollInterval = Duration.ZERO,
            jobWaitTimeout = jobWaitTimeout,
        )

    private fun step(state: StepState): DescribeStepResponse =
        DescribeStepResponse
            .builder()
            .step(Step.builder().status(StepStatus.builder().state(state).build()).build())
            .build()

    private fun awsFailure(status: Int) =
        AwsServiceException
            .builder()
            .statusCode(status)
            .message("failure $status")
            .build()

    @Test
    fun `a throttled describe is retried and the wait returns the finished step`() {
        var looks = 0
        doAnswer {
            when (++looks) {
                1 -> throw awsFailure(429)
                2 -> step(StepState.RUNNING)
                else -> step(StepState.COMPLETED)
            }
        }.whenever(emrClient).describeStep(any<DescribeStepRequest>())

        val result = service().waitForJobCompletion("j-1", "s-1")

        assertThat(result.getOrThrow().state).isEqualTo(SparkJobState.COMPLETED)
        verify(emrClient, times(3)).describeStep(any<DescribeStepRequest>())
    }

    @Test
    fun `an access denied describe fails the wait at once`() {
        whenever(emrClient.describeStep(any<DescribeStepRequest>())).thenThrow(awsFailure(403))

        val result = service().waitForJobCompletion("j-1", "s-1")

        assertThat(result.exceptionOrNull()).isInstanceOf(AwsServiceException::class.java)
        verify(emrClient, times(1)).describeStep(any<DescribeStepRequest>())
    }

    @Test
    fun `a step that never finishes fails with AwsTimeoutException naming the step`() {
        whenever(emrClient.describeStep(any<DescribeStepRequest>())).thenReturn(step(StepState.RUNNING))

        val result = service(jobWaitTimeout = Duration.ofMillis(50)).waitForJobCompletion("j-1", "s-1")

        assertThat(result.exceptionOrNull())
            .isInstanceOf(AwsTimeoutException::class.java)
            .hasMessage("Timeout waiting for EMR step s-1 to finish after 50ms")
    }
}
