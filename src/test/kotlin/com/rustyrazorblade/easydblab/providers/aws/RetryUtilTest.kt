package com.rustyrazorblade.easydblab.providers.aws

import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.s3.model.S3Exception
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies the retry decisions for AWS eventual consistency: applying an S3 bucket policy retries
 * transient IAM-propagation failures ("Invalid principal" / MalformedPolicy), and EC2 instance
 * operations retry instances that are not visible yet, while real errors are not retried. Uses
 * configs with no backoff delay so the tests run instantly.
 */
class RetryUtilTest {
    private fun s3Exception(
        errorCode: String,
        statusCode: Int,
        message: String,
    ): S3Exception =
        S3Exception
            .builder()
            .awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).build())
            .statusCode(statusCode)
            .message(message)
            .build() as S3Exception

    private fun ec2Exception(
        errorCode: String,
        statusCode: Int,
        message: String,
    ): Ec2Exception =
        Ec2Exception
            .builder()
            .awsErrorDetails(
                AwsErrorDetails
                    .builder()
                    .errorCode(errorCode)
                    .errorMessage(message)
                    .build(),
            ).statusCode(statusCode)
            .message(message)
            .build() as Ec2Exception

    private fun retryNoDelay(config: RetryConfig = RetryUtil.createS3BucketPolicyRetryConfig<Unit>()) =
        Retry.of(
            "test",
            RetryConfig
                .from<Any>(config)
                .intervalFunction { 1L }
                .build(),
        )

    /** Runs [failure] on every attempt before the [succeedOn]th and returns the attempt count. */
    private fun attemptsUntilSuccess(
        retry: Retry,
        succeedOn: Int,
        failure: () -> Exception,
    ): Int {
        val attempts = AtomicInteger(0)
        Retry
            .decorateSupplier(retry) {
                if (attempts.incrementAndGet() < succeedOn) throw failure()
                "ok"
            }.get()
        return attempts.get()
    }

    @Test
    fun `retries invalid-principal errors until the role propagates`() {
        val attempts = AtomicInteger(0)
        val retry = retryNoDelay()

        val result =
            Retry
                .decorateSupplier(retry) {
                    // Fail the first two attempts as if the IAM role is not yet visible, then succeed.
                    if (attempts.incrementAndGet() < 3) {
                        throw s3Exception("MalformedPolicy", 400, "Invalid principal in policy")
                    }
                    "ok"
                }.get()

        assertThat(result).isEqualTo("ok")
        assertThat(attempts.get()).isEqualTo(3)
    }

    @Test
    fun `does not retry permission errors`() {
        val attempts = AtomicInteger(0)
        val retry = retryNoDelay()

        assertThatThrownBy {
            Retry
                .decorateSupplier(retry) {
                    attempts.incrementAndGet()
                    throw s3Exception("AccessDenied", 403, "Access Denied")
                }.get()
        }.isInstanceOf(S3Exception::class.java)

        assertThat(attempts.get()).isEqualTo(1)
    }

    @Test
    fun `EC2 retries an instance that is not visible yet`() {
        val attempts =
            attemptsUntilSuccess(retryNoDelay(RetryUtil.createEC2InstanceRetryConfig<Unit>()), succeedOn = 3) {
                ec2Exception("InvalidInstanceID.NotFound", 400, "The instance ID 'i-0de7469325c785662' does not exist")
            }

        assertThat(attempts).isEqualTo(3)
    }

    @Test
    fun `EC2 retries several instances that are not visible yet`() {
        // With more than one id EC2 says "do not exist", not "does not exist". This failed init --up
        // on the three db instances while the single control and app instances were retried.
        val attempts =
            attemptsUntilSuccess(retryNoDelay(RetryUtil.createEC2InstanceRetryConfig<Unit>()), succeedOn = 3) {
                ec2Exception(
                    "InvalidInstanceID.NotFound",
                    400,
                    "The instance IDs 'i-0de7469325c785662, i-0ebb82a19cdf6fa31' do not exist",
                )
            }

        assertThat(attempts).isEqualTo(3)
    }

    @Test
    fun `EC2 does not retry other client errors`() {
        val attempts = AtomicInteger(0)

        assertThatThrownBy {
            Retry
                .decorateSupplier(retryNoDelay(RetryUtil.createEC2InstanceRetryConfig<Unit>())) {
                    attempts.incrementAndGet()
                    throw ec2Exception("InvalidParameterValue", 400, "Invalid id: 'not-an-id'")
                }.get()
        }.isInstanceOf(Ec2Exception::class.java)

        assertThat(attempts.get()).isEqualTo(1)
    }
}
