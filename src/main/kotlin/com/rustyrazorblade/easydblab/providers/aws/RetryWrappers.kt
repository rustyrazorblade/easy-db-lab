package com.rustyrazorblade.easydblab.providers.aws

import io.github.resilience4j.retry.Retry

// Convenience wrappers that run an operation under one of the RetryUtil retry configs.
// The config factories themselves stay in RetryUtil.

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
    val retryConfig = RetryUtil.createAwsRetryConfig<T>()
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
    val retryConfig = RetryUtil.createEC2InstanceRetryConfig<T>()
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
    val retryConfig = RetryUtil.createVpcTeardownRetryConfig<T>()
    val retry = Retry.of(operationName, retryConfig)
    return Retry.decorateSupplier(retry, operation).get()
}

/**
 * Executes an operation with S3 bucket policy retry logic (handles IAM eventual consistency).
 *
 * @param operationName Name of the operation for logging and metrics
 * @param operation The operation to execute with retry logic
 * @return The result of the operation
 */
fun <T> withS3BucketPolicyRetry(
    operationName: String,
    operation: () -> T,
): T {
    val retryConfig = RetryUtil.createS3BucketPolicyRetryConfig<T>()
    val retry = Retry.of(operationName, retryConfig)
    return Retry.decorateSupplier(retry, operation).get()
}
