package com.rustyrazorblade.easydblab.providers.aws

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.presigner.S3Presigner

/**
 * Builds S3 presigners for a region named at call time. A presigned URL must be signed for the
 * bucket's own region, which can differ from the profile's, so the region is chosen per call, as
 * [RegionalS3Clients] does for clients. Keeps SDK construction in the clients layer.
 *
 * Signing is local: no request is made. Each call builds a new presigner; the caller closes it.
 */
class RegionalS3Presigners(
    private val credentials: AwsCredentialsProvider,
) {
    /** A new presigner for [region], signing with the profile's credentials. */
    fun forRegion(region: String): S3Presigner =
        S3Presigner
            .builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .build()
}
