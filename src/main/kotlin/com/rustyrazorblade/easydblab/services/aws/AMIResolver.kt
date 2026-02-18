package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.providers.aws.AWS
import com.rustyrazorblade.easydblab.providers.aws.model.AMI

/**
 * Service for resolving AMI IDs for cluster provisioning.
 *
 * This service handles the logic to determine which AMI to use for
 * provisioning EC2 instances. It can either use an explicitly provided
 * AMI ID or automatically select the most recent AMI matching the
 * requested architecture.
 */
interface AMIResolver {
    /**
     * Resolves the AMI ID to use for provisioning.
     *
     * If an explicit AMI ID is provided in the configuration, it is returned directly.
     * Otherwise, the service searches for private AMIs matching the architecture pattern
     * and returns the most recently created one.
     *
     * @param explicitAmiId AMI ID from configuration, or blank to auto-resolve
     * @param architecture CPU architecture (e.g., "amd64", "arm64")
     * @return Result containing the AMI ID, or failure with descriptive error
     */
    fun resolveAmiId(
        explicitAmiId: String,
        architecture: String,
    ): Result<String>

    /**
     * Generates the AMI name pattern for a given architecture.
     *
     * @param architecture CPU architecture (e.g., "amd64", "arm64")
     * @return Pattern string for matching AMIs (e.g., "rustyrazorblade/images/easy-db-lab-cassandra-amd64-*")
     */
    fun generateAmiPattern(architecture: String): String

    /**
     * Finds all AMIs matching the given architecture.
     *
     * @param architecture CPU architecture (e.g., "amd64", "arm64")
     * @return List of matching AMIs, sorted by creation date (most recent first)
     */
    fun findAmisForArchitecture(architecture: String): List<AMI>

    /**
     * Selects the most recent AMI from a list.
     *
     * @param amis List of AMIs to choose from
     * @return The most recently created AMI, or null if list is empty
     */
    fun selectMostRecentAmi(amis: List<AMI>): AMI?
}

/**
 * Default implementation of AMIResolver using AMIService.
 *
 * @property amiService Service for interacting with EC2 API
 */
class DefaultAMIResolver(
    private val amiService: AMIService,
) : AMIResolver {
    override fun resolveAmiId(
        explicitAmiId: String,
        architecture: String,
    ): Result<String> {
        // If explicit AMI ID is provided, use it directly
        if (explicitAmiId.isNotBlank()) {
            return Result.success(explicitAmiId)
        }

        // Auto-resolve based on architecture
        val arch = architecture.lowercase()
        val amis = findAmisForArchitecture(arch)

        if (amis.isEmpty()) {
            return Result.failure(
                NoAmiFoundException(
                    "No AMI found for architecture $arch. " +
                        "Please build an AMI first with 'easy-db-lab build-image'.",
                ),
            )
        }

        val selectedAmi =
            selectMostRecentAmi(amis)
                ?: return Result.failure(
                    NoAmiFoundException("No AMI found for architecture $arch"),
                )

        return Result.success(selectedAmi.id)
    }

    override fun generateAmiPattern(architecture: String): String = Constants.AWS.AMI_PATTERN_TEMPLATE.format(architecture.lowercase())

    override fun findAmisForArchitecture(architecture: String): List<AMI> {
        val pattern = generateAmiPattern(architecture)
        return amiService.listPrivateAMIs(pattern)
    }

    override fun selectMostRecentAmi(amis: List<AMI>): AMI? = amis.maxByOrNull { it.creationDate }
}

/**
 * Exception thrown when no AMI can be found for the requested configuration.
 */
class NoAmiFoundException(
    message: String,
) : RuntimeException(message)
