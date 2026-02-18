package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.model.AMI
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DeleteSnapshotRequest
import software.amazon.awssdk.services.ec2.model.DeregisterImageRequest
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest
import software.amazon.awssdk.services.ec2.model.DescribeSnapshotsRequest
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.ec2.model.Filter
import java.time.Instant

/**
 * Service for managing the full AMI lifecycle: listing, pruning, validation, and cleanup.
 *
 * Consolidates low-level EC2 AMI operations, pruning logic, and validation into a single
 * cohesive service.
 *
 * @property ec2Client The AWS SDK EC2 client for making API calls
 * @property outputHandler Handler for user-facing messages
 * @property aws AWS service for retrieving account ID
 * @property validationRetryConfig Retry configuration for AMI validation resilience
 */
@Suppress("TooManyFunctions")
class AMIService(
    private val ec2Client: Ec2Client,
    private val outputHandler: OutputHandler,
    private val aws: AWS,
    validationRetryConfig: RetryConfig = defaultValidationRetryConfig,
) : AMIValidator {
    private val validationRetry = Retry.of("ami-validation", validationRetryConfig)

    companion object {
        private val log = KotlinLogging.logger {}

        // AMI pattern template - architecture is injected at runtime
        const val DEFAULT_AMI_PATTERN_TEMPLATE = "rustyrazorblade/images/easy-db-lab-cassandra-%s-*"

        /**
         * Default retry configuration for AMI validation:
         * - Max 3 attempts
         * - 2 second initial wait with exponential backoff
         * - Retries on transient AWS errors (429, 5xx)
         * - Does NOT retry on permission errors (403)
         */
        val defaultValidationRetryConfig: RetryConfig =
            RetryConfig
                .custom<AMI>()
                .maxAttempts(3)
                .intervalFunction { attemptCount ->
                    // Exponential backoff: 2s, 4s, 8s
                    2000L * (1L shl (attemptCount - 1))
                }.retryOnException { throwable ->
                    when {
                        throwable !is Ec2Exception -> false
                        throwable.statusCode() == 403 -> {
                            log.warn { "Permission denied - will not retry: ${throwable.message}" }
                            false
                        }
                        throwable.statusCode() in 500..599 -> {
                            log.warn { "AWS service error ${throwable.statusCode()} - will retry" }
                            true
                        }
                        throwable.statusCode() == 429 -> {
                            log.warn { "Rate limited - will retry with backoff" }
                            true
                        }
                        else -> false
                    }
                }.build()
    }

    /**
     * Result of a pruning operation.
     *
     * @property kept List of AMIs that were kept (not deleted), sorted by groupKey for display
     * @property deleted List of AMIs that were deleted (or would be deleted in dry-run mode),
     *                   sorted by creation date ascending (oldest first)
     */
    data class PruneResult(
        val kept: List<AMI>,
        val deleted: List<AMI>,
    )

    // ==================== Low-Level EC2 AMI Operations ====================

    /**
     * Lists all private AMIs owned by the specified account matching the name pattern.
     *
     * @param namePattern Wildcard pattern for filtering AMI names
     * @param ownerId AWS account ID to filter AMIs by owner (defaults to "self")
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

    // ==================== Pruning ====================

    /**
     * Prunes older AMIs while keeping the newest N AMIs per architecture and type.
     *
     * @param namePattern Wildcard pattern for filtering AMI names
     * @param keepCount Number of newest AMIs to keep per architecture/type combination
     * @param dryRun If true, identify AMIs to delete but don't actually delete them
     * @param typeFilter Optional filter to only prune AMIs of specific type
     * @return PruneResult containing lists of kept and deleted AMIs
     */
    fun pruneAMIs(
        namePattern: String,
        keepCount: Int,
        dryRun: Boolean,
        typeFilter: String? = null,
    ): PruneResult {
        val allAMIs = listPrivateAMIs(namePattern)

        val filteredAMIs =
            if (typeFilter != null) {
                allAMIs.filter { it.type.equals(typeFilter, ignoreCase = true) }
            } else {
                allAMIs
            }

        val groupedAMIs = filteredAMIs.groupBy { it.groupKey }

        val keptAMIs = mutableListOf<AMI>()
        val deletedAMIs = mutableListOf<AMI>()

        for ((_, amis) in groupedAMIs) {
            val sortedAMIs = amis.sorted()

            val toKeep = sortedAMIs.take(keepCount)
            val toDelete = sortedAMIs.drop(keepCount)

            keptAMIs.addAll(toKeep)
            deletedAMIs.addAll(toDelete)

            if (!dryRun) {
                deleteAMIsWithSnapshots(toDelete)
            }
        }

        val sortedKept = keptAMIs.sortedBy { it.groupKey }
        val sortedDeleted = deletedAMIs.sortedBy { it.creationDate }

        return PruneResult(kept = sortedKept, deleted = sortedDeleted)
    }

    // ==================== Validation (AMIValidator) ====================

    override fun validateAMI(
        overrideAMI: String,
        requiredArchitecture: Arch,
        amiPattern: String?,
    ): AMI {
        if (overrideAMI.isNotEmpty()) {
            return validateExplicitAMI(overrideAMI, requiredArchitecture)
        }

        return findAndValidateAMI(requiredArchitecture, amiPattern)
    }

    // ==================== Private Helpers ====================

    private fun normalizeArchitecture(awsArchitecture: String): String =
        when (awsArchitecture) {
            "x86_64" -> "amd64"
            else -> awsArchitecture
        }

    private fun deleteAMIsWithSnapshots(amis: List<AMI>) {
        for (ami in amis) {
            deregisterAMI(ami.id)
            ami.snapshotIds.forEach { snapshotId ->
                deleteSnapshot(snapshotId)
            }
        }
    }

    private fun validateExplicitAMI(
        amiId: String,
        requiredArchitecture: Arch,
    ): AMI {
        log.info { "Validating explicit AMI: $amiId for architecture: ${requiredArchitecture.type}" }

        val ami =
            Retry
                .decorateSupplier(validationRetry) {
                    val amis = listPrivateAMIs(amiId, aws.getAccountId())
                    if (amis.isEmpty()) {
                        throw AMIValidationException.NoAMIFound(amiId, requiredArchitecture)
                    }
                    amis.first()
                }.get()

        if (ami.architecture != requiredArchitecture.type) {
            throw AMIValidationException.ArchitectureMismatch(
                ami.id,
                requiredArchitecture,
                ami.architecture,
            )
        }

        log.info { "Successfully validated AMI: ${ami.id} with architecture: ${ami.architecture}" }
        return ami
    }

    private fun findAndValidateAMI(
        requiredArchitecture: Arch,
        customPattern: String?,
    ): AMI {
        val pattern =
            customPattern
                ?: System.getProperty("easydblab.ami.name")
                ?: String.format(DEFAULT_AMI_PATTERN_TEMPLATE, requiredArchitecture.type)

        log.info { "Searching for AMI matching pattern: $pattern" }

        val amis =
            try {
                Retry
                    .decorateSupplier(validationRetry) {
                        listPrivateAMIs(pattern, aws.getAccountId())
                    }.get()
            } catch (e: Exception) {
                throw AMIValidationException.AWSServiceError(
                    "Failed to query AMIs: ${e.message}",
                    e,
                )
            }

        if (amis.isEmpty()) {
            displayNoAMIFoundError(pattern, requiredArchitecture)
            throw AMIValidationException.NoAMIFound(pattern, requiredArchitecture)
        }

        val matchingArchAMIs = amis.filter { it.architecture == requiredArchitecture.type }

        if (matchingArchAMIs.isEmpty()) {
            log.error {
                "Found ${amis.size} AMIs matching pattern but none with architecture ${requiredArchitecture.type}"
            }
            throw AMIValidationException.ArchitectureMismatch(
                amis.first().id,
                requiredArchitecture,
                amis.first().architecture,
            )
        }

        val selectedAMI = matchingArchAMIs.maxByOrNull { it.creationDate }!!

        if (matchingArchAMIs.size > 1) {
            outputHandler.handleMessage(
                "Warning: Found ${matchingArchAMIs.size} AMIs matching pattern. " +
                    "Using newest: ${selectedAMI.id} (${selectedAMI.creationDate})",
            )
        }

        log.info {
            "Successfully validated AMI: ${selectedAMI.id} " +
                "with architecture: ${selectedAMI.architecture}"
        }

        return selectedAMI
    }

    private fun displayNoAMIFoundError(
        pattern: String,
        architecture: Arch,
    ) {
        outputHandler.handleMessage(
            """
            |
            |========================================
            |AMI NOT FOUND
            |========================================
            |
            |No AMI found in your AWS account matching:
            |  Pattern: $pattern
            |  Architecture: ${architecture.type}
            |
            |Before you can provision instances, you need to build the AMI images.
            |
            |To build the required AMI, run:
            |  easy-db-lab build-image --arch ${architecture.type}
            |
            |This will create both the base and Cassandra AMIs in your AWS account.
            |
            |Alternatively, you can specify a custom AMI with:
            |  --ami <ami-id>
            |  or set EASY_CASS_LAB_AMI environment variable
            |
            |========================================
            |
            """.trimMargin(),
        )
    }
}
