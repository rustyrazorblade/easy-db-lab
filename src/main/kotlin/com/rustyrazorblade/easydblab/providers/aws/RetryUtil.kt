package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.DockerException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.RetryConfig
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.iam.model.EntityAlreadyExistsException
import software.amazon.awssdk.services.iam.model.IamException
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.IOException
import java.net.BindException
import java.time.Duration
import java.time.Instant

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
     * - Retries on InvalidInstanceID.NotFound (instance not yet visible). Matched on the error
     *   code: the message reads "does not exist" for one id and "do not exist" for several
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
                    // Instance not visible yet - eventual consistency after instance creation
                    throwable.awsErrorDetails()?.errorCode() == Constants.AWS.EC2_INSTANCE_NOT_FOUND -> {
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
     * Creates retry configuration for the EC2 calls `up` makes per batch of new instances
     * (RunInstances, CreateTags) at 1,000-node scale.
     *
     * - Retries `RequestLimitExceeded`: EC2 throttles a burst of launch and tag calls, and a
     *   1,000-node `up` makes hundreds. The SDK's own few retries are not enough for that
     * - Retries `InvalidInstanceID.NotFound`: a tag on an instance RunInstances just returned can
     *   fail until EC2 sees it
     * - Retries 5xx server errors
     * - Nothing else: a capacity, quota or permission error does not clear by waiting
     *
     * A retried RunInstances is safe only because its request carries a client token: EC2
     * answers a repeat with the instances the first call launched instead of launching more.
     *
     * Exponential backoff from [backoffBase], capped at [Constants.Scale.THROTTLE_BACKOFF_MAX_MS],
     * up to [Constants.Scale.THROTTLE_MAX_ATTEMPTS] attempts
     *
     * @param backoffBase the first wait; tests pass a short one
     * @return RetryConfig for EC2 calls that AWS throttles at scale
     */
    fun <T> createEc2ThrottleRetryConfig(
        backoffBase: Duration = Duration.ofMillis(Constants.Scale.THROTTLE_BACKOFF_BASE_MS),
    ): RetryConfig =
        RetryConfig
            .custom<T>()
            .maxAttempts(Constants.Scale.THROTTLE_MAX_ATTEMPTS)
            .intervalFunction { attemptCount ->
                minOf(backoffBase.toMillis() * (1L shl (attemptCount - 1)), Constants.Scale.THROTTLE_BACKOFF_MAX_MS)
            }.retryOnException { throwable ->
                val code = (throwable as? AwsServiceException)?.awsErrorDetails()?.errorCode()
                when {
                    throwable !is AwsServiceException -> false
                    // AWS sends this as a 500, but waiting does not create capacity: fail fast.
                    code == Constants.AWS.EC2_INSUFFICIENT_CAPACITY -> false
                    code == EC2_THROTTLED || code == Constants.AWS.EC2_INSTANCE_NOT_FOUND -> {
                        log.warn { "EC2 $code - will retry" }
                        true
                    }
                    throwable.statusCode() >= Constants.HttpStatus.SERVER_ERROR_MIN -> {
                        log.warn { "AWS service error ${throwable.statusCode()} - will retry" }
                        true
                    }
                    else -> false
                }
            }.build()

    /** EC2's error code for a throttled request. */
    private const val EC2_THROTTLED = "RequestLimitExceeded"

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
     * - Retries on IOException (network/socket issues). docker-java's HTTP transport wraps a socket
     *   error in a bare RuntimeException; callers unwrap it (see ContainerExecutor) and run the
     *   call with resilience4j's checked decorators, which are the only ones that see an IOException
     * - Retries on DockerException (API errors), except docker-java's 404 NotFoundException: the
     *   container or image does not exist, and no retry makes it appear
     * - Fails fast on everything else
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
                    // A 404: the container or image does not exist, and no retry makes it appear.
                    is com.github.dockerjava.api.exception.NotFoundException -> false
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
     * - Retries on SshException: MINA reports a refused or timed-out connection, and a failed
     *   key exchange or auth while the node is still booting, as SshException
     * - Retries on SsmForwardNotReadyException: under the `ssm` SSH transport, a fresh node's SSM
     *   agent has not registered yet (`TargetNotConnected`)
     * - Fails fast on every other exception, any other IOException included: it will not clear by
     *   waiting
     *
     * SshException is checked, so the caller must decorate with resilience4j's
     * `decorateCheckedRunnable`/`decorateCheckedSupplier`; the plain decorators catch only
     * RuntimeException and would never see it.
     *
     * @param interval the fixed wait between attempts; tests pass a short one
     * @return RetryConfig configured for SSH connection operations
     */
    fun createSshConnectionRetryConfig(
        interval: Duration = Duration.ofMillis(Constants.Retry.SSH_CONNECTION_RETRY_DELAY_MS),
    ): RetryConfig =
        RetryConfig
            .custom<Unit>()
            .maxAttempts(Constants.Retry.MAX_SSH_CONNECTION_RETRIES)
            .intervalFunction { _ -> interval.toMillis() }
            .retryOnException(::isSshNotReady)
            .build()

    /**
     * Creates retry configuration for polling a check that returns a boolean until it is `true`.
     *
     * The check returns a boolean, not an exception: `false` means the awaited state has not
     * arrived yet, so the retry is on the result. When the attempts run out the last result,
     * `false`, is returned. The interval is a parameter so tests can run every attempt without
     * sleeping. Used for the pre-K3s probe of the control node over the tailnet
     * ([Constants.Tailscale.REACHABILITY_MAX_ATTEMPTS]) and for the final stderr EMR uploads
     * for a failed step ([Constants.EMR.FINAL_STDERR_MAX_ATTEMPTS]).
     *
     * Fixed interval: [retryInterval] between attempts, up to [maxAttempts] attempts
     *
     * @param maxAttempts how many times to run the check
     * @param retryInterval how long to wait between checks
     * @return RetryConfig configured for the poll
     */
    fun createBooleanPollRetryConfig(
        maxAttempts: Int,
        retryInterval: Duration,
    ): RetryConfig =
        RetryConfig
            .custom<Boolean>()
            .maxAttempts(maxAttempts)
            .intervalFunction { _ -> retryInterval.toMillis() }
            .retryOnResult { done -> !done }
            .build()

    /**
     * Creates retry configuration for creating a VPC on an auto-selected CIDR.
     *
     * Each attempt picks a new random unused block, excluding the ones already tried, so there is
     * nothing to wait for between attempts; transient AWS errors are already retried with backoff
     * inside `createVpc`. Only AWS failures ([SdkException]) are retried — running out of free
     * blocks is final.
     *
     * No wait, up to [Constants.Vpc.CIDR_AUTO_SELECT_MAX_ATTEMPTS] attempts
     *
     * @return RetryConfig configured for VPC creation on an auto-selected CIDR
     */
    fun createVpcAutoCidrRetryConfig(): RetryConfig =
        RetryConfig
            .custom<Any>()
            .maxAttempts(Constants.Vpc.CIDR_AUTO_SELECT_MAX_ATTEMPTS)
            .waitDuration(Duration.ZERO)
            .retryExceptions(SdkException::class.java)
            .build()

    /**
     * Creates retry configuration for starting a local listener whose port can be taken between
     * choosing it and binding it — the SOCKS5 proxy's `ssh -D`, when two workspaces start a proxy
     * at the same moment.
     *
     * Retries only on [BindException]: the caller selects a new port on every attempt, so a
     * collision is resolved by the next one. Any other failure is not a port collision and is not
     * retried.
     *
     * Fixed interval: [Constants.Proxy.PORT_BIND_RETRY_INTERVAL_MS] between attempts, up to
     * [Constants.Proxy.PORT_BIND_MAX_ATTEMPTS] attempts
     *
     * @return RetryConfig configured for a local port bind collision
     */
    fun createLocalPortBindRetryConfig(): RetryConfig =
        RetryConfig
            .custom<Any>()
            .maxAttempts(Constants.Proxy.PORT_BIND_MAX_ATTEMPTS)
            .waitDuration(Duration.ofMillis(Constants.Proxy.PORT_BIND_RETRY_INTERVAL_MS))
            .retryExceptions(BindException::class.java)
            .build()

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

    /**
     * Creates retry configuration for applying an S3 bucket policy whose principals are
     * IAM roles that were just created.
     *
     * IAM is eventually consistent, so a freshly created role may not yet be visible to S3's
     * policy validator. S3 rejects the policy with a 400 `MalformedPolicy` ("Invalid principal
     * in policy") until the role propagates. Retrying with backoff lets propagation catch up.
     *
     * - 5 attempts to cover propagation delay
     * - Retries on `MalformedPolicy` / "Invalid principal" S3 errors and on 5xx server errors
     * - Does NOT retry on 403 (forbidden) — that is a real permission error
     *
     * Exponential backoff: 1s, 2s, 4s, 8s, 16s
     *
     * @return RetryConfig configured for S3 bucket policy application
     */
    fun <T> createS3BucketPolicyRetryConfig(): RetryConfig =
        RetryConfig
            .custom<T>()
            .maxAttempts(Constants.Retry.MAX_INSTANCE_PROFILE_RETRIES)
            .intervalFunction { attemptCount ->
                // Exponential backoff: 1s, 2s, 4s, 8s, 16s
                Constants.Retry.EXPONENTIAL_BACKOFF_BASE_MS * (1L shl (attemptCount - 1))
            }.retryOnException { throwable ->
                when {
                    throwable !is S3Exception -> false
                    throwable.statusCode() == Constants.HttpStatus.FORBIDDEN -> {
                        log.warn { "Permission denied applying S3 bucket policy - will not retry" }
                        false
                    }
                    throwable.awsErrorDetails()?.errorCode() == "MalformedPolicy" ||
                        throwable.message?.contains("Invalid principal") == true -> {
                        log.warn { "IAM role not yet visible to S3 (eventual consistency) - will retry bucket policy" }
                        true
                    }
                    throwable.statusCode() in
                        Constants.HttpStatus.SERVER_ERROR_MIN..Constants.HttpStatus.SERVER_ERROR_MAX -> {
                        log.warn { "S3 server error ${throwable.statusCode()} applying bucket policy - will retry" }
                        true
                    }
                    else -> false
                }
            }.build()

    /**
     * Creates retry configuration for polling a condition at a fixed interval.
     *
     * - A result for which [done] is false is retried, [interval] apart, up to [maxAttempts]
     *   looks; after the last look that result is returned, not thrown
     * - Any exception from a look but an interrupt is treated as transient (a dropped API call, a
     *   SOCKS hiccup during a wait that lasts minutes) and retried within the same budget; the poll
     *   fails only when the last look throws. That includes checked ones such as OkHttp's
     *   IOException, so run it with resilience4j's checked decorators, as [pollUntil] does
     * - With [retryExceptions] false no exception is retried: the look itself decides which
     *   failures mean "not done yet" (the SSH readiness wait returns the hosts still pending and
     *   throws only a failure that waiting cannot fix)
     * - An InterruptedException ends the poll at once in either mode: the thread was asked to stop
     *
     * - When [deadline] is set, nothing is retried once it has passed, so the wait is bounded by the
     *   wall clock rather than by how long each look takes
     *
     * @param maxAttempts the most looks to make
     * @param interval how long to wait between looks
     * @param done whether a look's result ends the poll
     * @param deadline when set, the instant after which no further look is made
     * @param retryExceptions whether an exception other than an interrupt is retried
     * @return RetryConfig configured for a fixed-interval poll
     */
    fun <T> createPollUntilRetryConfig(
        maxAttempts: Int,
        interval: Duration,
        done: (T) -> Boolean,
        deadline: Instant? = null,
        retryExceptions: Boolean = true,
    ): RetryConfig {
        fun beforeDeadline() = deadline == null || Instant.now().isBefore(deadline)
        return RetryConfig
            .custom<T>()
            .maxAttempts(maxAttempts)
            .intervalFunction { _ -> interval.toMillis() }
            .retryOnResult { result -> !done(result) && beforeDeadline() }
            .retryOnException { retryExceptions && it !is InterruptedException && beforeDeadline() }
            .build()
    }
}
