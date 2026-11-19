package com.rustyrazorblade.easydblab.providers.aws

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

/**
 * Builds S3 clients for a region named at call time. The account bucket's region can differ from
 * the profile's, so the profile-region [S3Client] in `awsClientsModule` cannot read it; this keeps
 * the client construction in the clients module, so `services/aws` never builds an SDK client.
 *
 * Each call builds a new client; the caller closes it.
 */
class RegionalS3Clients(
    private val credentials: AwsCredentialsProvider,
) {
    /** A new S3 client for [region], signed with the profile's credentials. */
    fun forRegion(region: String): S3Client =
        S3Client
            .builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .overrideConfiguration(ClientOverrideConfiguration.builder().build())
            .build()
}
