package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.providers.aws.model.AMI

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
