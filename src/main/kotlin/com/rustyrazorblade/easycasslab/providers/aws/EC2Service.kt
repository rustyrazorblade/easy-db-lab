package com.rustyrazorblade.easycasslab.providers.aws

import com.rustyrazorblade.easycasslab.providers.aws.model.AMI
import io.github.resilience4j.retry.Retry
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DeleteSnapshotRequest
import software.amazon.awssdk.services.ec2.model.DeregisterImageRequest
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest
import software.amazon.awssdk.services.ec2.model.DescribeSnapshotsRequest
import software.amazon.awssdk.services.ec2.model.Filter
import java.time.Instant

/**
 * Service for managing EC2 AMI operations.
 *
 * This service provides low-level operations for interacting with AWS EC2 AMIs,
 * including listing, deregistering AMIs, and managing associated EBS snapshots.
 * It wraps the AWS SDK EC2 client to provide domain-specific functionality.
 *
 * @property ec2Client The AWS SDK EC2 client for making API calls
 */
class EC2Service(
    private val ec2Client: Ec2Client,
) {
    /**
     * Lists all private AMIs owned by the specified account matching the name pattern.
     *
     * This method queries AWS for AMIs owned by the specified account and
     * filters them by the provided name pattern. It converts AWS SDK Image objects
     * into domain AMI objects with normalized architecture values.
     *
     * Uses retry logic for transient AWS service errors.
     *
     * @param namePattern Wildcard pattern for filtering AMI names (e.g., "rustyrazorblade/images/easy-cass-lab-*")
     * @param ownerId AWS account ID to filter AMIs by owner (defaults to "self" for backward compatibility)
     * @return List of AMI objects representing private AMIs matching the pattern
     */
    fun listPrivateAMIs(
        namePattern: String,
        ownerId: String = "self",
    ): List<AMI> {
        val request =
            DescribeImagesRequest
                .builder()
                .owners(ownerId)
                .filters(
                    Filter
                        .builder()
                        .name("name")
                        .values(namePattern)
                        .build(),
                ).build()

        val retryConfig = RetryUtil.createAwsRetryConfig<List<AMI>>()
        val retry = Retry.of("ec2-list-amis", retryConfig)

        return Retry
            .decorateSupplier(retry) {
                ec2Client.describeImages(request).images().map { image ->
                    AMI(
                        id = image.imageId(),
                        name = image.name(),
                        architecture = normalizeArchitecture(image.architecture().toString()),
                        creationDate = Instant.parse(image.creationDate()),
                        ownerId = image.ownerId(),
                        // AMIs owned by "self" are typically private unless explicitly made public
                        isPublic = false,
                        snapshotIds =
                            image
                                .blockDeviceMappings()
                                .mapNotNull { it.ebs()?.snapshotId() },
                    )
                }
            }.get()
    }

    /**
     * Retrieves snapshot IDs associated with a specific AMI.
     *
     * Queries AWS for EBS snapshots that are associated with the given AMI ID.
     * This is useful for finding orphaned snapshots after AMI deregistration.
     *
     * Uses retry logic for transient AWS service errors.
     *
     * @param amiId The AMI identifier to find snapshots for
     * @return List of snapshot IDs associated with the AMI
     */
    fun getSnapshotsForAMI(amiId: String): List<String> {
        val request =
            DescribeSnapshotsRequest
                .builder()
                .filters(
                    Filter
                        .builder()
                        .name("description")
                        .values("*$amiId*")
                        .build(),
                ).build()

        val retryConfig = RetryUtil.createAwsRetryConfig<List<String>>()
        val retry = Retry.of("ec2-get-snapshots", retryConfig)

        return Retry
            .decorateSupplier(retry) {
                ec2Client.describeSnapshots(request).snapshots().map { it.snapshotId() }
            }.get()
    }

    /**
     * Deregisters (deletes) an AMI.
     *
     * Removes the AMI from AWS, making it unavailable for launching new instances.
     * Note: This does not automatically delete associated EBS snapshots - those must
     * be deleted separately using deleteSnapshot().
     *
     * Uses retry logic for transient AWS service errors.
     *
     * @param amiId The AMI identifier to deregister
     */
    fun deregisterAMI(amiId: String) {
        val request =
            DeregisterImageRequest
                .builder()
                .imageId(amiId)
                .build()

        val retryConfig = RetryUtil.createAwsRetryConfig<Unit>()
        val retry = Retry.of("ec2-deregister-ami", retryConfig)

        Retry
            .decorateRunnable(retry) {
                ec2Client.deregisterImage(request)
            }.run()
    }

    /**
     * Deletes an EBS snapshot.
     *
     * Permanently removes an EBS snapshot from AWS. This should typically be called
     * after deregistering an AMI to clean up orphaned snapshots and avoid storage costs.
     *
     * Uses retry logic for transient AWS service errors.
     *
     * @param snapshotId The snapshot identifier to delete
     */
    fun deleteSnapshot(snapshotId: String) {
        val request =
            DeleteSnapshotRequest
                .builder()
                .snapshotId(snapshotId)
                .build()

        val retryConfig = RetryUtil.createAwsRetryConfig<Unit>()
        val retry = Retry.of("ec2-delete-snapshot", retryConfig)

        Retry
            .decorateRunnable(retry) {
                ec2Client.deleteSnapshot(request)
            }.run()
    }

    /**
     * Normalizes AWS architecture names to consistent internal format.
     *
     * AWS uses "x86_64" for AMD/Intel processors, but the codebase uses "amd64"
     * consistently (matching Packer and Docker conventions).
     *
     * @param awsArchitecture The architecture string from AWS SDK
     * @return Normalized architecture string ("amd64" or "arm64")
     */
    private fun normalizeArchitecture(awsArchitecture: String): String =
        when (awsArchitecture) {
            "x86_64" -> "amd64"
            else -> awsArchitecture
        }
}
