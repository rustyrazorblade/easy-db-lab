package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.DockerException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import org.apache.sshd.common.SshException
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.services.ec2.model.Ec2Exception
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
     * Creates retry configuration for EC2 instance operations.
     *
     * EC2 instance operations require special handling due to AWS eventual consistency:
     * - Higher retry count (5 attempts) to handle propagation delays after instance creation
     * - Retries on 400 errors with "does not exist" message (instance not yet visible)
     * - Retries on 5xx server errors
     * - Does NOT retry on other 4xx client errors (e.g., permission errors)
     *
     * Exponential backoff: 1s, 2s, 4s, 8s, 16s
     *
     * @return RetryConfig configured for EC2 instance operations
     */
    fun <T> createEC2InstanceRetryConfig(): RetryConfig =
        RetryConfig
            .custom<T>()
            .maxAttempts(Constants.Retry.MAX_EC2_INSTANCE_RETRIES)
            .intervalFunction { attemptCount ->
                // Exponential backoff: 1s, 2s, 4s, 8s, 16s
                Constants.Retry.EXPONENTIAL_BACKOFF_BASE_MS * (1L shl (attemptCount - 1))
            }.retryOnException { throwable ->
                when {
                    throwable !is AwsServiceException -> false
                    // Retry on "does not exist" errors - eventual consistency after instance creation
                    throwable.statusCode() == Constants.HttpStatus.BAD_REQUEST &&
                        throwable.message?.contains("does not exist") == true -> {
                        log.warn { "Instance not yet visible (eventual consistency) - will retry" }
                        true
                    }
                    // Retry on server errors
                    throwable.statusCode() in
                        Constants.HttpStatus.SERVER_ERROR_MIN..Constants.HttpStatus.SERVER_ERROR_MAX -> {
                        log.warn { "AWS service error ${throwable.statusCode()} - will retry" }
                        true
                    }
                    else -> false
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
     * For EC2 instance operations (describeInstances after create), use
     * createEC2InstanceRetryConfig() instead.
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

    /**
     * Creates retry configuration for VPC teardown deletion operations.
     *
     * During VPC teardown, resources may have lingering dependencies (e.g., ENIs
     * still detaching after OpenSearch/NAT gateway deletion). AWS returns
     * DependencyViolation (400) errors for these transient states.
     *
     * - 5 attempts with longer exponential backoff (5s, 10s, 20s, 40s)
     * - Retries on Ec2Exception with error code "DependencyViolation"
     * - Retries on 5xx server errors
     * - Does NOT retry on other 4xx client errors
     *
     * @return RetryConfig configured for VPC teardown deletion operations
     */
    fun <T> createVpcTeardownRetryConfig(): RetryConfig =
        RetryConfig
            .custom<T>()
            .maxAttempts(Constants.Retry.MAX_VPC_TEARDOWN_RETRIES)
            .intervalFunction { attemptCount ->
                // Exponential backoff: 5s, 10s, 20s, 40s
                Constants.Retry.VPC_TEARDOWN_BACKOFF_BASE_MS * (1L shl (attemptCount - 1))
            }.retryOnException { throwable ->
                when {
                    throwable !is Ec2Exception -> false
                    throwable.awsErrorDetails()?.errorCode() == "DependencyViolation" -> {
                        log.warn {
                            "DependencyViolation during VPC teardown - will retry: ${throwable.message}"
                        }
                        true
                    }
                    throwable.statusCode() in
                        Constants.HttpStatus.SERVER_ERROR_MIN..Constants.HttpStatus.SERVER_ERROR_MAX -> {
                        log.warn {
                            "AWS service error ${throwable.statusCode()} during VPC teardown - will retry"
                        }
                        true
                    }
                    else -> false
                }
            }.build()

    // ==================== Helper Functions ====================

    /**
     * Executes an operation with standard AWS retry logic.
     *
     * This is a convenience function that wraps the common pattern of:
     * 1. Creating an AWS retry config
     * 2. Creating a Retry instance
     * 3. Decorating and executing the operation
     *
     * @param operationName Name of the operation for logging and metrics
     * @param operation The operation to execute with retry logic
     * @return The result of the operation
     */
    fun <T> withAwsRetry(
        operationName: String,
        operation: () -> T,
    ): T {
        val retryConfig = createAwsRetryConfig<T>()
        val retry = Retry.of(operationName, retryConfig)
        return Retry.decorateSupplier(retry, operation).get()
    }

    /**
     * Executes an operation with EC2 instance retry logic (handles eventual consistency).
     *
     * @param operationName Name of the operation for logging and metrics
     * @param operation The operation to execute with retry logic
     * @return The result of the operation
     */
    fun <T> withEc2InstanceRetry(
        operationName: String,
        operation: () -> T,
    ): T {
        val retryConfig = createEC2InstanceRetryConfig<T>()
        val retry = Retry.of(operationName, retryConfig)
        return Retry.decorateSupplier(retry, operation).get()
    }

    /**
     * Executes an operation with VPC teardown retry logic (handles DependencyViolation).
     *
     * @param operationName Name of the operation for logging and metrics
     * @param operation The operation to execute with retry logic
     * @return The result of the operation
     */
    fun <T> withVpcTeardownRetry(
        operationName: String,
        operation: () -> T,
    ): T {
        val retryConfig = createVpcTeardownRetryConfig<T>()
        val retry = Retry.of(operationName, retryConfig)
        return Retry.decorateSupplier(retry, operation).get()
    }
}
