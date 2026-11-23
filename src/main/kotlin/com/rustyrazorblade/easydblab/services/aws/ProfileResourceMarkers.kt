package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsDestinationAccess

/**
 * The profile's account bucket and key pair outlive every cluster, so ones made before the
 * `easy_db_lab` marker existed carry only the legacy marker. The image builds call
 * [ensureAccountBucketAndKeyPair] so both gain the new marker before phase 2 of the tag rename
 * reads it; one place keeps the two builds from drifting. `up` and `profile setup` run the same two
 * backfills inside their own bucket and key-pair steps.
 */
class ProfileResourceMarkers(
    private val s3BucketService: AwsS3BucketService,
    private val keyPairService: AwsKeyPairService,
    private val destination: AwsDestinationAccess,
) {
    /**
     * Ensures the account bucket exists, gives it and the profile's key pair any marker tag they
     * lack, and returns the bucket name.
     */
    fun ensureAccountBucketAndKeyPair(): String {
        val bucket = s3BucketService.ensureMarkedAccountBucket()
        keyPairService.ensureMarkerTags(destination.current())
        return bucket
    }
}
