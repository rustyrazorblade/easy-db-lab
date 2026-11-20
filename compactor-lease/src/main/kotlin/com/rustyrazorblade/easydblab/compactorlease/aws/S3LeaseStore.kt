package com.rustyrazorblade.easydblab.compactorlease.aws

import com.rustyrazorblade.easydblab.compactorlease.ConditionalObjects
import com.rustyrazorblade.easydblab.compactorlease.LeaseAgentConfig
import com.rustyrazorblade.easydblab.compactorlease.LeaseTiming
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import kotlin.time.toJavaDuration

/**
 * The agent's S3 client for [config]'s bucket. Every call, retries included, ends within
 * [LeaseTiming.storeCallTimeout], so a slow read cannot return a version old enough to let a
 * standby take over while the holder may still compact.
 */
internal fun leaseS3Client(
    config: LeaseAgentConfig,
    credentials: AwsCredentialsProvider,
): S3Client =
    S3Client
        .builder()
        .region(Region.of(config.region))
        .credentialsProvider(credentials)
        .overrideConfiguration {
            it
                .apiCallTimeout(config.timing.storeCallTimeout.toJavaDuration())
                .apiCallAttemptTimeout(config.timing.storeAttemptTimeout.toJavaDuration())
        }.apply { config.endpoint?.let { endpointOverride(it).forcePathStyle(true) } }
        .build()

/**
 * Runs [block] with the lease store on S3 for [config]'s bucket, and closes the client after it.
 *
 * It signs with the default credentials chain, which on a node is the instance role through IMDS.
 * This is the one place the agent's entry point reaches the AWS SDK; [block] sees only the
 * provider-neutral [ConditionalObjects] port.
 */
fun <T> withS3LeaseStore(
    config: LeaseAgentConfig,
    block: (ConditionalObjects) -> T,
): T =
    leaseS3Client(config, DefaultCredentialsProvider.builder().build())
        .use { s3 -> block(S3ConditionalObjects(s3, config.bucket)) }
