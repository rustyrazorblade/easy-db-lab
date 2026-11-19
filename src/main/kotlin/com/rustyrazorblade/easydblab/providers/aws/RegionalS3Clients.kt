package com.rustyrazorblade.easydblab.providers.aws

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

/**
 * S3 clients for a region named at call time. The account bucket can sit in a region other than the
 * profile's, so the profile-region [S3Client] in [awsClientsModule] cannot read it; the account
 * compactor's lease reader asks for a client in the bucket's own region.
 *
 * It exists so `services/aws` never builds an SDK client itself. Each call builds a new client; the
 * caller closes it.
 */
fun interface RegionalS3Clients {
    /** A new S3 client for [region], with the profile's credentials. */
    fun s3(region: String): S3Client
}

/**
 * [RegionalS3Clients] with the profile's credentials.
 *
 * @property credentials the profile's credentials provider.
 */
class DefaultRegionalS3Clients(
    private val credentials: AwsCredentialsProvider,
) : RegionalS3Clients {
    override fun s3(region: String): S3Client =
        S3Client
            .builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .overrideConfiguration(ClientOverrideConfiguration.builder().build())
            .build()
}
