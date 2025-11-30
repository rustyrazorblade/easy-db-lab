package com.rustyrazorblade.easycasslab.providers.aws

import com.rustyrazorblade.easycasslab.configuration.Arch
import com.rustyrazorblade.easycasslab.output.OutputHandler
import com.rustyrazorblade.easycasslab.providers.aws.model.AMI
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import software.amazon.awssdk.services.ec2.model.Ec2Exception

/**
 * Service for validating AMI availability and architecture requirements.
 *
 * This service ensures that:
 * 1. A suitable AMI exists for the requested architecture
 * 2. The AMI architecture matches the --arch flag
 * 3. AWS API calls are resilient to transient failures
 * 4. AMI queries use the authenticated account ID (not "self" which can resolve incorrectly in AWS Organizations)
 *
 * @property ec2Service Low-level EC2 service for AWS API calls
 * @property outputHandler Handler for user-facing messages
 * @property aws AWS service for retrieving account ID
 * @property retryConfig Retry configuration for AWS API resilience
 */
class AMIValidationService(
    private val ec2Service: EC2Service,
    private val outputHandler: OutputHandler,
    private val aws: AWS,
    retryConfig: RetryConfig = defaultRetryConfig,
) : AMIValidator {
    private val retry = Retry.of("ami-validation", retryConfig)

    companion object {
        private val log = KotlinLogging.logger {}

        // AMI pattern template - architecture is injected at runtime
        const val DEFAULT_AMI_PATTERN_TEMPLATE = "rustyrazorblade/images/easy-cass-lab-cassandra-%s-*"

        /**
         * Default retry configuration for AMI validation:
         * - Max 3 attempts
         * - 2 second initial wait with exponential backoff
         * - Retries on transient AWS errors (429, 5xx)
         * - Does NOT retry on permission errors (403)
         */
        val defaultRetryConfig: RetryConfig =
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

    override fun validateAMI(
        overrideAMI: String,
        requiredArchitecture: Arch,
        amiPattern: String?,
    ): AMI {
        // If explicit AMI provided, validate its architecture
        if (overrideAMI.isNotEmpty()) {
            return validateExplicitAMI(overrideAMI, requiredArchitecture)
        }

        // Otherwise, find AMI matching pattern and architecture
        return findAndValidateAMI(requiredArchitecture, amiPattern)
    }

    private fun validateExplicitAMI(
        amiId: String,
        requiredArchitecture: Arch,
    ): AMI {
        log.info { "Validating explicit AMI: $amiId for architecture: ${requiredArchitecture.type}" }

        val ami =
            Retry
                .decorateSupplier(retry) {
                    val amis = ec2Service.listPrivateAMIs(amiId, aws.getAccountId())
                    if (amis.isEmpty()) {
                        throw AMIValidationException.NoAMIFound(amiId, requiredArchitecture)
                    }
                    amis.first()
                }.get()

        // Validate architecture matches
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
                ?: System.getProperty("easycasslab.ami.name")
                ?: String.format(DEFAULT_AMI_PATTERN_TEMPLATE, requiredArchitecture.type)

        log.info { "Searching for AMI matching pattern: $pattern" }

        val amis =
            try {
                Retry
                    .decorateSupplier(retry) {
                        ec2Service.listPrivateAMIs(pattern, aws.getAccountId())
                    }.get()
            } catch (e: Exception) {
                throw AMIValidationException.AWSServiceError(
                    "Failed to query AMIs: ${e.message}",
                    e,
                )
            }

        // No AMIs found - provide helpful error message
        if (amis.isEmpty()) {
            displayNoAMIFoundError(pattern, requiredArchitecture)
            throw AMIValidationException.NoAMIFound(pattern, requiredArchitecture)
        }

        // Filter by architecture
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

        // Select newest AMI if multiple found
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
            |  easy-cass-lab build-image --arch ${architecture.type}
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

/**
 * Interface for AMI validation operations.
 * Enables dependency inversion and testability.
 */
interface AMIValidator {
    /**
     * Validates that a suitable AMI exists for the given architecture.
     *
     * @param overrideAMI Explicit AMI ID to validate (empty if using pattern)
     * @param requiredArchitecture Required CPU architecture
     * @param amiPattern Optional custom AMI name pattern
     * @return Validated AMI object
     * @throws AMIValidationException if validation fails
     */
    fun validateAMI(
        overrideAMI: String,
        requiredArchitecture: Arch,
        amiPattern: String? = null,
    ): AMI
}

/**
 * Exceptions thrown during AMI validation.
 */
sealed class AMIValidationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    class NoAMIFound(
        pattern: String,
        architecture: Arch,
    ) : AMIValidationException(
            "No AMI found matching pattern: $pattern for architecture: ${architecture.type}",
        )

    class ArchitectureMismatch(
        amiId: String,
        expected: Arch,
        actual: String,
    ) : AMIValidationException(
            "AMI $amiId has architecture $actual but expected ${expected.type}",
        )

    class AWSServiceError(
        message: String,
        cause: Throwable,
    ) : AMIValidationException(
            "AWS service error during AMI validation: $message",
            cause,
        )
}
