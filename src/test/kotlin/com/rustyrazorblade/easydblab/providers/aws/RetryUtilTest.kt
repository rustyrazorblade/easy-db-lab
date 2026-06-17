package com.rustyrazorblade.easydblab.providers.aws

import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.s3.model.S3Exception
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies the retry decision logic for applying an S3 bucket policy: transient IAM-propagation
 * failures ("Invalid principal" / MalformedPolicy) are retried, while real permission errors are
 * not. Uses a config with no backoff delay so the test runs instantly.
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

    private fun retryNoDelay() =
        Retry.of(
            "test",
            RetryConfig
                .from<Any>(RetryUtil.createS3BucketPolicyRetryConfig<Unit>())
                .intervalFunction { 1L }
                .build(),
        )

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
}
