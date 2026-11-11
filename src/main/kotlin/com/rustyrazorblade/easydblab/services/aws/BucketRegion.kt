package com.rustyrazorblade.easydblab.services.aws

import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest

/**
 * Finds the region an S3 bucket lives in.
 *
 * The account bucket need not be in the cluster's region. Anything that signs requests to it (the
 * account compactor, the documents proxy in the Grafana pod) must sign for the bucket's own region,
 * or S3 answers `AuthorizationHeaderMalformed`.
 */
class BucketRegion(
    private val s3: S3Client,
) {
    private companion object {
        /** S3 reports the original region as an empty location constraint. */
        const val DEFAULT_BUCKET_REGION = "us-east-1"
    }

    /** The region of [bucket], from `GetBucketLocation`. */
    fun of(bucket: String): String =
        s3
            .getBucketLocation(GetBucketLocationRequest.builder().bucket(bucket).build())
            .locationConstraintAsString()
            .orEmpty()
            .ifEmpty { DEFAULT_BUCKET_REGION }
}
