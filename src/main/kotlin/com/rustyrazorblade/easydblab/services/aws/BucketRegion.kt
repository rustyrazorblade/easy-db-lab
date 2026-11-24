package com.rustyrazorblade.easydblab.services.aws

import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest

/**
 * Finds the region an S3 bucket lives in.
 *
 * The account bucket need not be in the cluster's region. Anything that signs requests to it (the
 * account compactor, the documents proxy in the Grafana pod) must sign for the bucket's own region,
 * or S3 answers `AuthorizationHeaderMalformed`.
 *
 * The S3 client is built on the first lookup, not with this object: its region comes from the
 * profile, and the services holding a [BucketRegion] are also built in workspaces with no AWS
 * account and no profile, where no lookup ever happens.
 */
class BucketRegion(
    private val s3: Lazy<S3Client>,
) {
    constructor(s3: S3Client) : this(lazyOf(s3))

    private companion object {
        /** S3 reports the original region as an empty location constraint. */
        const val DEFAULT_BUCKET_REGION = "us-east-1"
    }

    /** The region of [bucket], from `GetBucketLocation`. */
    fun of(bucket: String): String =
        s3.value
            .getBucketLocation(GetBucketLocationRequest.builder().bucket(bucket).build())
            .locationConstraintAsString()
            .orEmpty()
            .ifEmpty { DEFAULT_BUCKET_REGION }
}
