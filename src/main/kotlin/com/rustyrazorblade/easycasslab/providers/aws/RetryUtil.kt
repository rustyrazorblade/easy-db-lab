package com.rustyrazorblade.easycasslab.providers.aws

import com.rustyrazorblade.easycasslab.Constants
import com.rustyrazorblade.easycasslab.DockerException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.RetryConfig
import org.apache.sshd.common.SshException
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.services.iam.model.EntityAlreadyExistsException
import software.amazon.awssdk.services.iam.model.IamException
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.IOException

/**
 * Utility for creating standardized retry configurations for remote service operations.
 *
 * Provides pre-configured retry policies for:
 * - AWS services (IAM, S3, EMR, EC2) with eventual consistency handling
 * - Docker operations with container lifecycle management
 * - SSH connections for remote host access
 * - Generic network operations
 *
 * All configurations use exponential backoff (1s, 2s, 4s, 8s, ...) by default.
 * This consolidates retry logic to ensure consistent behavior across all remote operations.
 */
object RetryUtil {
    private val log = KotlinLogging.logger {}

    /**
     * Creates retry configuration for IAM operations.
     *
     * IAM operations require special handling due to AWS eventual consistency:
     * - Higher retry count (5 attempts) to handle propagation delays
     * - Retries on 404 (not found) errors as resources may not be visible immediately
     * - Retries on 5xx server errors
     * - Does NOT retry on 403 (forbidden) as these are permission errors
     * - Does NOT retry on EntityAlreadyExistsException
     *
     * Exponential backoff: 1s, 2s, 4s, 8s, 16s
     *
     * @return RetryConfig configured for IAM operations
     */
    fun createIAMRetryConfig(): RetryConfig =
        RetryConfig
            .custom<Unit>()
            .maxAttempts(Constants.Retry.MAX_INSTANCE_PROFILE_RETRIES)
            .intervalFunction { attemptCount ->
                // Exponential backoff: 1s, 2s, 4s, 8s, 16s
                Constants.Retry.EXPONENTIAL_BACKOFF_BASE_MS * (1L shl (attemptCount - 1))
            }.retryOnException { throwable ->
                when {
                    throwable !is IamException -> false
                    throwable is EntityAlreadyExistsException -> false // Don't retry if already exists
                    throwable.statusCode() == Constants.HttpStatus.FORBIDDEN -> {
                        log.warn { "Permission denied for IAM operation - will not retry" }
                        false
                    }
                    throwable.statusCode() in
                        Constants.HttpStatus.SERVER_ERROR_MIN..Constants.HttpStatus.SERVER_ERROR_MAX -> {
                        log.warn {
                            "AWS service error ${throwable.statusCode()} for IAM operation - will retry"
                        }
                        true
                    }
                    throwable.statusCode() == Constants.HttpStatus.NOT_FOUND -> {
                        log.warn { "Resource not found (eventual consistency) - will retry IAM operation" }
                        true
                    }
                    else -> {
                        log.warn { "IAM error: ${throwable.message} - will retry" }
                        true
                    }
                }
            }.build()

    /**
     * Creates retry configuration for standard AWS operations (S3, EC2, EMR, etc.).
     *
     * Standard AWS operations:
     * - Standard retry count (3 attempts)
     * - Retries only on 5xx server errors
     * - Does NOT retry on 4xx client errors
     *
     * Note: For IAM operations, use createIAMRetryConfig() instead, which has
     * special handling for eventual consistency (404 errors) and higher retry count.
     *
     * Exponential backoff: 1s, 2s, 4s
     *
     * @return RetryConfig configured for standard AWS operations
     */
    fun <T> createAwsRetryConfig(): RetryConfig =
        RetryConfig
            .custom<T>()
            .maxAttempts(Constants.Retry.MAX_AWS_RETRIES)
            .intervalFunction { attemptCount ->
                // Exponential backoff: 1s, 2s, 4s
                Constants.Retry.EXPONENTIAL_BACKOFF_BASE_MS * (1L shl (attemptCount - 1))
            }.retryOnException { throwable ->
                // Retry on AWS service errors (5xx), but not on client errors (4xx)
                throwable is AwsServiceException &&
                    throwable.statusCode() >= Constants.HttpStatus.CLIENT_ERROR_THRESHOLD
            }.build()

    /**
     * Creates retry configuration for Docker operations.
     *
     * Docker operations (start, inspect, remove containers):
     * - Standard retry count (3 attempts)
     * - Retries on IOException (network/socket issues)
     * - Retries on DockerException (API errors)
     *
     * Exponential backoff: 1s, 2s, 4s
     *
     * @return RetryConfig configured for Docker operations
     */
    fun <T> createDockerRetryConfig(): RetryConfig =
        RetryConfig
            .custom<T>()
            .maxAttempts(Constants.Retry.MAX_DOCKER_RETRIES)
            .intervalFunction { attemptCount ->
                // Exponential backoff: 1s, 2s, 4s
                Constants.Retry.EXPONENTIAL_BACKOFF_BASE_MS * (1L shl (attemptCount - 1))
            }.retryOnException { throwable ->
                when (throwable) {
                    is IOException -> {
                        log.warn { "IO error during Docker operation - will retry: ${throwable.message}" }
                        true
                    }
                    is DockerException -> {
                        log.warn { "Docker error - will retry: ${throwable.message}" }
                        true
                    }
                    is com.github.dockerjava.api.exception.DockerException -> {
                        log.warn { "Docker API error - will retry: ${throwable.message}" }
                        true
                    }
                    else -> false
                }
            }.build()

    /**
     * Creates retry configuration for generic network operations.
     *
     * General network operations:
     * - Standard retry count (3 attempts)
     * - Retries on IOException (network errors)
     * - Retries on RuntimeException (transient failures)
     *
     * Exponential backoff: 1s, 2s, 4s
     *
     * @return RetryConfig configured for network operations
     */
    fun <T> createNetworkRetryConfig(): RetryConfig =
        RetryConfig
            .custom<T>()
            .maxAttempts(Constants.Retry.MAX_NETWORK_RETRIES)
            .intervalFunction { attemptCount ->
                // Exponential backoff: 1s, 2s, 4s
                Constants.Retry.EXPONENTIAL_BACKOFF_BASE_MS * (1L shl (attemptCount - 1))
            }.retryOnException { throwable ->
                throwable is IOException || throwable is RuntimeException
            }.build()

    /**
     * Creates retry configuration for SSH connection operations.
     *
     * SSH connections during instance boot-up:
     * - High retry count (30 attempts) to accommodate boot time
     * - Fixed 10-second delay between attempts (not exponential)
     * - Total wait time: approximately 5 minutes
     * - Retries on SshException (connection refused, timeout)
     * - Retries on IOException (network errors)
     *
     * Fixed interval: 10 seconds between attempts
     *
     * @return RetryConfig configured for SSH connection operations
     */
    fun createSshConnectionRetryConfig(): RetryConfig =
        RetryConfig
            .custom<Unit>()
            .maxAttempts(Constants.Retry.MAX_SSH_CONNECTION_RETRIES)
            .intervalFunction { _ ->
                // Fixed 10-second delay for SSH boot-up waiting
                Constants.Retry.SSH_CONNECTION_RETRY_DELAY_MS
            }.retryOnException { throwable ->
                when (throwable) {
                    is SshException -> {
                        log.debug { "SSH not ready - will retry: ${throwable.message}" }
                        true
                    }
                    is IOException -> {
                        log.debug { "IO error during SSH connection - will retry: ${throwable.message}" }
                        true
                    }
                    else -> false
                }
            }.build()

    /**
     * Creates retry configuration for S3 log retrieval with eventual consistency.
     *
     * EMR logs may not be immediately available after job completion:
     * - Retries on NoSuchKeyException (404) since logs may not be uploaded yet
     * - Retries on 5xx server errors
     * - Fixed delay (not exponential) since we're waiting for external upload
     *
     * Fixed interval: 3 seconds between attempts, up to 10 attempts (~30s total wait)
     *
     * @return RetryConfig configured for S3 log retrieval operations
     */
    fun <T> createS3LogRetrievalRetryConfig(): RetryConfig =
        RetryConfig
            .custom<T>()
            .maxAttempts(Constants.Retry.MAX_LOG_RETRIEVAL_RETRIES)
            .intervalFunction { _ ->
                // Fixed delay since we're waiting for external upload
                Constants.Retry.LOG_RETRIEVAL_RETRY_DELAY_MS
            }.retryOnException { throwable ->
                when {
                    throwable is S3Exception && throwable.statusCode() == Constants.HttpStatus.NOT_FOUND -> {
                        log.debug { "Log file not yet available in S3 (404) - will retry" }
                        true
                    }
                    throwable is S3Exception && throwable.statusCode() >= Constants.HttpStatus.SERVER_ERROR_MIN -> {
                        log.warn { "S3 server error ${throwable.statusCode()} - will retry" }
                        true
                    }
                    else -> false
                }
            }.build()
}
