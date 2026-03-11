package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration
import software.amazon.awssdk.services.s3.model.DeleteBucketMetricsConfigurationRequest
import software.amazon.awssdk.services.s3.model.ExpirationStatus
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationRequest
import software.amazon.awssdk.services.s3.model.LifecycleExpiration
import software.amazon.awssdk.services.s3.model.LifecycleRule
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter
import software.amazon.awssdk.services.s3.model.MetricsConfiguration
import software.amazon.awssdk.services.s3.model.MetricsFilter
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest
import software.amazon.awssdk.services.s3.model.PutBucketMetricsConfigurationRequest
import software.amazon.awssdk.services.s3.model.S3Exception

private val log = KotlinLogging.logger {}

/**
 * Sets an S3 lifecycle expiration rule on an entire bucket (no prefix filter).
 * Replaces any existing lifecycle rules on the bucket.
 *
 * @param bucketName The S3 bucket
 * @param days Number of days before expiration
 */
fun AWS.setFullBucketLifecycleExpiration(
    bucketName: String,
    days: Int,
) {
    val rule =
        LifecycleRule
            .builder()
            .id("expire-all-objects")
            .filter(LifecycleRuleFilter.builder().prefix("").build())
            .expiration(LifecycleExpiration.builder().days(days).build())
            .status(ExpirationStatus.ENABLED)
            .build()

    val config =
        BucketLifecycleConfiguration
            .builder()
            .rules(listOf(rule))
            .build()

    val retryConfig = RetryUtil.createAwsRetryConfig<Unit>()
    val retry = Retry.of("s3-set-full-bucket-lifecycle", retryConfig)
    Retry
        .decorateRunnable(retry) {
            s3Client.putBucketLifecycleConfiguration(
                PutBucketLifecycleConfigurationRequest
                    .builder()
                    .bucket(bucketName)
                    .lifecycleConfiguration(config)
                    .build(),
            )
        }.run()
    log.info { "Set lifecycle expiration on entire bucket: $bucketName, days: $days" }
}

/**
 * Enables S3 request metrics on a bucket for CloudWatch monitoring.
 * Idempotent - overwrites any existing configuration with the same ID.
 *
 * @param bucketName The S3 bucket to enable metrics on
 * @param prefix Optional prefix to scope metrics to a specific folder/cluster
 * @param configId The metrics configuration ID
 */
fun AWS.enableBucketRequestMetrics(
    bucketName: String,
    prefix: String? = null,
    configId: String = Constants.S3.METRICS_CONFIGURATION_ID,
) {
    val metricsConfigBuilder = MetricsConfiguration.builder().id(configId)
    if (prefix != null) {
        metricsConfigBuilder.filter(MetricsFilter.builder().prefix(prefix).build())
    }
    val metricsConfig = metricsConfigBuilder.build()

    val request =
        PutBucketMetricsConfigurationRequest
            .builder()
            .bucket(bucketName)
            .id(configId)
            .metricsConfiguration(metricsConfig)
            .build()

    val retryConfig = RetryUtil.createAwsRetryConfig<Unit>()
    val retry = Retry.of("s3-enable-bucket-metrics", retryConfig)
    Retry
        .decorateRunnable(retry) {
            s3Client.putBucketMetricsConfiguration(request)
            log.info { "Enabled S3 request metrics on bucket: $bucketName (prefix: ${prefix ?: "whole-bucket"})" }
        }.run()
}

/**
 * Disables S3 request metrics on a bucket to stop CloudWatch charges.
 * Safe to call even if metrics were never enabled.
 *
 * @param bucketName The S3 bucket to disable metrics on
 * @param configId The metrics configuration ID to delete
 */
fun AWS.disableBucketRequestMetrics(
    bucketName: String,
    configId: String = Constants.S3.METRICS_CONFIGURATION_ID,
) {
    val request =
        DeleteBucketMetricsConfigurationRequest
            .builder()
            .bucket(bucketName)
            .id(configId)
            .build()

    try {
        val retryConfig = RetryUtil.createAwsRetryConfig<Unit>()
        val retry = Retry.of("s3-disable-bucket-metrics", retryConfig)
        Retry
            .decorateRunnable(retry) {
                s3Client.deleteBucketMetricsConfiguration(request)
            }.run()
        log.info { "Disabled S3 request metrics on bucket: $bucketName (configId: $configId)" }
    } catch (e: S3Exception) {
        if (e.statusCode() == Constants.HttpStatus.NOT_FOUND) {
            log.info { "No metrics configuration found on bucket: $bucketName for configId: $configId (already disabled)" }
        } else {
            throw e
        }
    }
}

/**
 * Sets an S3 lifecycle expiration rule on a prefix within a bucket.
 * Preserves any existing lifecycle rules on other prefixes.
 *
 * @param bucketName The S3 bucket
 * @param prefix The key prefix to expire
 * @param days Number of days before expiration
 */
fun AWS.setLifecycleExpirationRule(
    bucketName: String,
    prefix: String,
    days: Int,
) {
    // Get existing rules to preserve them
    val existingRules =
        try {
            s3Client
                .getBucketLifecycleConfiguration(
                    GetBucketLifecycleConfigurationRequest.builder().bucket(bucketName).build(),
                )?.rules()
                ?.filter { it.filter()?.prefix() != prefix }
                ?: emptyList()
        } catch (e: S3Exception) {
            if (e.statusCode() == Constants.HttpStatus.NOT_FOUND) {
                emptyList()
            } else {
                throw e
            }
        }

    val newRule =
        LifecycleRule
            .builder()
            .id("expire-${prefix.trimEnd('/').replace("/", "-")}")
            .filter(LifecycleRuleFilter.builder().prefix(prefix).build())
            .expiration(LifecycleExpiration.builder().days(days).build())
            .status(ExpirationStatus.ENABLED)
            .build()

    val config =
        BucketLifecycleConfiguration
            .builder()
            .rules(existingRules + newRule)
            .build()

    val retryConfig = RetryUtil.createAwsRetryConfig<Unit>()
    val retry = Retry.of("s3-set-lifecycle-rule", retryConfig)
    Retry
        .decorateRunnable(retry) {
            s3Client.putBucketLifecycleConfiguration(
                PutBucketLifecycleConfigurationRequest
                    .builder()
                    .bucket(bucketName)
                    .lifecycleConfiguration(config)
                    .build(),
            )
        }.run()
    log.info { "Set lifecycle expiration rule on bucket: $bucketName, prefix: $prefix, days: $days" }
}
