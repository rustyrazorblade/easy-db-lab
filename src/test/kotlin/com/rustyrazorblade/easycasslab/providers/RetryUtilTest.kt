package com.rustyrazorblade.easycasslab.providers

import com.rustyrazorblade.easycasslab.Constants
import com.rustyrazorblade.easycasslab.DockerException
import com.rustyrazorblade.easycasslab.providers.aws.RetryUtil
import org.apache.sshd.common.SshException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.services.iam.model.EntityAlreadyExistsException
import software.amazon.awssdk.services.iam.model.IamException
import java.io.IOException

/**
 * Tests for RetryUtil retry configuration builders.
 *
 * Validates that retry configurations:
 * - Have appropriate max attempts
 * - Retry on expected exception types
 * - Do not retry on forbidden (403) errors
 * - Do not retry on EntityAlreadyExistsException
 */
class RetryUtilTest {
    @Nested
    inner class IAMRetryConfigTests {
        @Test
        fun `createIAMRetryConfig should use correct max attempts from constants`() {
            val config = RetryUtil.createIAMRetryConfig()

            assertThat(config.maxAttempts).isEqualTo(Constants.Retry.MAX_INSTANCE_PROFILE_RETRIES)
        }

        @Test
        fun `createIAMRetryConfig should not retry on EntityAlreadyExistsException`() {
            val config = RetryUtil.createIAMRetryConfig()

            val exception = EntityAlreadyExistsException.builder().message("Already exists").build()

            assertThat(config.exceptionPredicate.test(exception)).isFalse()
        }

        @Test
        fun `createIAMRetryConfig should not retry on forbidden errors`() {
            val config = RetryUtil.createIAMRetryConfig()

            val forbiddenException =
                IamException
                    .builder()
                    .message("Forbidden")
                    .statusCode(Constants.HttpStatus.FORBIDDEN)
                    .build()

            assertThat(config.exceptionPredicate.test(forbiddenException)).isFalse()
        }

        @Test
        fun `createIAMRetryConfig should retry on 5xx server errors`() {
            val config = RetryUtil.createIAMRetryConfig()

            val serverError =
                IamException
                    .builder()
                    .message("Service unavailable")
                    .statusCode(503)
                    .build()

            assertThat(config.exceptionPredicate.test(serverError)).isTrue()
        }

        @Test
        fun `createIAMRetryConfig should retry on 404 not found (eventual consistency)`() {
            val config = RetryUtil.createIAMRetryConfig()

            val notFoundException =
                IamException
                    .builder()
                    .message("Not found")
                    .statusCode(Constants.HttpStatus.NOT_FOUND)
                    .build()

            assertThat(config.exceptionPredicate.test(notFoundException)).isTrue()
        }

        @Test
        fun `createIAMRetryConfig should retry on other IAM exceptions`() {
            val config = RetryUtil.createIAMRetryConfig()

            val iamException =
                IamException
                    .builder()
                    .message("Some IAM error")
                    .statusCode(400)
                    .build()

            assertThat(config.exceptionPredicate.test(iamException)).isTrue()
        }

        @Test
        fun `createIAMRetryConfig should not retry on non-IAM exceptions`() {
            val config = RetryUtil.createIAMRetryConfig()

            val exception = RuntimeException("Some error")

            assertThat(config.exceptionPredicate.test(exception)).isFalse()
        }
    }

    @Nested
    inner class AwsRetryConfigTests {
        @Test
        fun `createAwsRetryConfig should use correct max attempts from constants`() {
            val config = RetryUtil.createAwsRetryConfig<Unit>()

            assertThat(config.maxAttempts).isEqualTo(Constants.Retry.MAX_AWS_RETRIES)
        }

        @Test
        fun `createAwsRetryConfig should retry on AWS service errors 5xx`() {
            val config = RetryUtil.createAwsRetryConfig<Unit>()

            val serverError =
                AwsServiceException
                    .builder()
                    .message("Service unavailable")
                    .statusCode(503)
                    .build()

            assertThat(config.exceptionPredicate.test(serverError)).isTrue()
        }

        @Test
        fun `createAwsRetryConfig should not retry on client errors 4xx`() {
            val config = RetryUtil.createAwsRetryConfig<Unit>()

            val clientError =
                AwsServiceException
                    .builder()
                    .message("Bad request")
                    .statusCode(400)
                    .build()

            assertThat(config.exceptionPredicate.test(clientError)).isFalse()
        }

        @Test
        fun `createAwsRetryConfig should not retry on non-AWS exceptions`() {
            val config = RetryUtil.createAwsRetryConfig<Unit>()

            val exception = RuntimeException("Some error")

            assertThat(config.exceptionPredicate.test(exception)).isFalse()
        }
    }

    @Nested
    inner class DockerRetryConfigTests {
        @Test
        fun `createDockerRetryConfig should use correct max attempts from constants`() {
            val config = RetryUtil.createDockerRetryConfig<Unit>()

            assertThat(config.maxAttempts).isEqualTo(Constants.Retry.MAX_DOCKER_RETRIES)
        }

        @Test
        fun `createDockerRetryConfig should retry on IOException`() {
            val config = RetryUtil.createDockerRetryConfig<Unit>()

            val ioException = IOException("Connection refused")

            assertThat(config.exceptionPredicate.test(ioException)).isTrue()
        }

        @Test
        fun `createDockerRetryConfig should retry on DockerException`() {
            val config = RetryUtil.createDockerRetryConfig<Unit>()

            val dockerException = DockerException("Container error", RuntimeException("cause"))

            assertThat(config.exceptionPredicate.test(dockerException)).isTrue()
        }

        @Test
        fun `createDockerRetryConfig should not retry on other exceptions`() {
            val config = RetryUtil.createDockerRetryConfig<Unit>()

            val exception = IllegalArgumentException("Invalid argument")

            assertThat(config.exceptionPredicate.test(exception)).isFalse()
        }
    }

    @Nested
    inner class NetworkRetryConfigTests {
        @Test
        fun `createNetworkRetryConfig should use correct max attempts from constants`() {
            val config = RetryUtil.createNetworkRetryConfig<Unit>()

            assertThat(config.maxAttempts).isEqualTo(Constants.Retry.MAX_NETWORK_RETRIES)
        }

        @Test
        fun `createNetworkRetryConfig should retry on IOException`() {
            val config = RetryUtil.createNetworkRetryConfig<Unit>()

            val ioException = IOException("Network error")

            assertThat(config.exceptionPredicate.test(ioException)).isTrue()
        }

        @Test
        fun `createNetworkRetryConfig should retry on RuntimeException`() {
            val config = RetryUtil.createNetworkRetryConfig<Unit>()

            val runtimeException = RuntimeException("Transient failure")

            assertThat(config.exceptionPredicate.test(runtimeException)).isTrue()
        }
    }

    @Nested
    inner class SshConnectionRetryConfigTests {
        @Test
        fun `createSshConnectionRetryConfig should use correct max attempts from constants`() {
            val config = RetryUtil.createSshConnectionRetryConfig()

            assertThat(config.maxAttempts).isEqualTo(Constants.Retry.MAX_SSH_CONNECTION_RETRIES)
        }

        @Test
        fun `createSshConnectionRetryConfig should retry on SshException`() {
            val config = RetryUtil.createSshConnectionRetryConfig()

            val sshException = SshException("Connection refused")

            assertThat(config.exceptionPredicate.test(sshException)).isTrue()
        }

        @Test
        fun `createSshConnectionRetryConfig should retry on IOException`() {
            val config = RetryUtil.createSshConnectionRetryConfig()

            val ioException = IOException("Network unreachable")

            assertThat(config.exceptionPredicate.test(ioException)).isTrue()
        }

        @Test
        fun `createSshConnectionRetryConfig should not retry on other exceptions`() {
            val config = RetryUtil.createSshConnectionRetryConfig()

            val exception = IllegalStateException("Invalid state")

            assertThat(config.exceptionPredicate.test(exception)).isFalse()
        }

        @Test
        fun `createSshConnectionRetryConfig should use fixed interval delay`() {
            val config = RetryUtil.createSshConnectionRetryConfig()

            // The interval function should return the same value for all attempts
            val intervalFunction = config.intervalFunction
            val delay1 = intervalFunction.apply(1)
            val delay2 = intervalFunction.apply(2)
            val delay3 = intervalFunction.apply(3)

            assertThat(delay1).isEqualTo(Constants.Retry.SSH_CONNECTION_RETRY_DELAY_MS)
            assertThat(delay2).isEqualTo(Constants.Retry.SSH_CONNECTION_RETRY_DELAY_MS)
            assertThat(delay3).isEqualTo(Constants.Retry.SSH_CONNECTION_RETRY_DELAY_MS)
        }
    }
}
