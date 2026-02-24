package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.AWS
import com.rustyrazorblade.easydblab.providers.aws.AWSPolicy

/**
 * Service responsible for ensuring AWS IAM resources are set up
 * before any command runs. This runs automatically in CommandLineParser before command execution.
 *
 * Creates and validates the following AWS resources:
 * - EasyDBLabEC2Role: IAM role for EC2 instances (Cassandra, Stress, Control nodes)
 * - EasyDBLabEMRServiceRole: IAM role for EMR service
 * - EasyDBLabEMREC2Role: IAM role for EMR EC2 instances (Spark clusters)
 *
 * Note: S3 buckets are now created per-environment in the Up command.
 * IAM roles use a wildcard S3 policy (easy-db-lab-*) to access all per-environment buckets.
 */
class AWSResourceSetupService(
    private val aws: AWS,
    private val eventBus: EventBus,
) {
    companion object {
        // Error messages
        private const val ERR_IAM_VALIDATION =
            "IAM roles were created but failed validation. This may indicate an AWS propagation delay or configuration issue."
        private const val ERR_IAM_UNEXPECTED = "Unexpected error during IAM role setup"

        private val ERR_IAM_PERMISSIONS =
            """
            Failed to create IAM roles and instance profiles.

            This may be due to missing IAM permissions. Please ensure your AWS user has:
            - iam:CreateRole
            - iam:PutRolePolicy
            - iam:AttachRolePolicy
            - iam:CreateInstanceProfile
            - iam:AddRoleToInstanceProfile
            - iam:GetInstanceProfile
            - iam:ListRolePolicies

            You can generate EasyDBLabEC2, EasyDBLabIAM, and EasyDBLabEMR policies using:

            $ easy-db-lab show-iam-policies

            """.trimIndent()
    }

    /**
     * Retrieves the AWS account ID for the authenticated credentials.
     *
     * @return The AWS account ID
     */
    fun getAccountId(): String = aws.getAccountId()

    /**
     * Ensures all AWS IAM resources are set up before any command runs.
     * Only creates resources if they don't exist or are invalid.
     * Validates credentials first.
     *
     * Performs comprehensive validation of IAM role setup including:
     * - Instance profile existence
     * - Role attachment to instance profile
     * - S3 policy attachment (wildcard policy for easy-db-lab-* buckets)
     *
     * Note: S3 buckets are created per-environment in the Up command, not here.
     *
     * @param userConfig The user configuration (used for validation only, not modified)
     * @throws IllegalStateException if resource setup fails validation
     */
    @Suppress("UNUSED_PARAMETER")
    fun ensureAWSResources(userConfig: User) {
        val roleName = Constants.AWS.Roles.EC2_INSTANCE_ROLE

        // Early return if resources already exist and are valid
        if (validateExistingResources(roleName)) {
            return
        }

        eventBus.emit(Event.AwsSetup.Starting)

        // Step 1: Validate credentials
        validateCredentials()

        // Step 2: Create IAM resources (with wildcard S3 policy)
        createIAMResources(roleName)

        // Step 3: Finalize setup
        finalizeSetup(roleName)
    }

    /**
     * Validates existing resources and returns true if all resources are properly configured.
     */
    private fun validateExistingResources(roleName: String): Boolean {
        val validation = aws.validateRoleSetup(roleName)
        if (validation.isValid) {
            // All resources exist and are properly configured
            return true
        }

        // Resources exist in config but validation failed - will attempt to fix
        eventBus.emit(Event.AwsSetup.RepairWarning(validation.errorMessage ?: "Unknown validation error"))
        return false
    }

    /**
     * Validates AWS credentials before attempting any operations.
     * Provides user-friendly guidance for credential and permission errors.
     */
    private fun validateCredentials() {
        try {
            aws.checkPermissions()
        } catch (e: software.amazon.awssdk.services.sts.model.StsException) {
            // Check if this is an authorization error (403) vs authentication error (401/other)
            if (e.statusCode() == Constants.HttpStatus.FORBIDDEN) {
                // Permission denied - credentials are valid but lack permissions
                handlePermissionError(e)
            } else {
                // Authentication error - invalid or missing credentials
                handleAuthenticationError(e)
            }
            throw e
        } catch (e: software.amazon.awssdk.core.exception.SdkServiceException) {
            handlePermissionError(e)
            throw e
        } catch (e: Exception) {
            eventBus.emit(Event.AwsSetup.CredentialValidationFailed)
            throw e
        }
    }

    /**
     * Creates all 3 IAM roles and instance profiles with wildcard S3 policy.
     */
    private fun createIAMResources(roleName: String) {
        try {
            // EC2 role (for Cassandra, Stress, Control nodes) with wildcard S3 policy
            aws.createRoleWithS3Policy(roleName)
            eventBus.emit(Event.AwsSetup.Ec2RoleReady(roleName))

            // EMR Service role
            aws.createServiceRole()
            eventBus.emit(Event.AwsSetup.EmrServiceRoleReady(Constants.AWS.Roles.EMR_SERVICE_ROLE))

            // EMR EC2 role (for Spark clusters)
            aws.createEMREC2Role()
            eventBus.emit(Event.AwsSetup.EmrEc2RoleReady(Constants.AWS.Roles.EMR_EC2_ROLE))
        } catch (e: software.amazon.awssdk.services.iam.model.IamException) {
            eventBus.emit(Event.AwsSetup.IamPermissionError(ERR_IAM_PERMISSIONS))
            throw e
        } catch (e: IllegalStateException) {
            eventBus.emit(Event.AwsSetup.IamValidationError(ERR_IAM_VALIDATION))
            throw e
        } catch (e: Exception) {
            eventBus.emit(Event.AwsSetup.IamUnexpectedError(ERR_IAM_UNEXPECTED))
            throw e
        }
    }

    /**
     * Performs final validation of IAM setup.
     */
    private fun finalizeSetup(roleName: String) {
        val finalValidation = aws.validateRoleSetup(roleName)
        if (!finalValidation.isValid) {
            val errorMsg =
                """
                AWS resource setup completed but final validation failed.

                Status:
                - Instance profile exists: ${finalValidation.instanceProfileExists}
                - Role attached to profile: ${finalValidation.roleAttached}
                - S3 policy attached: ${finalValidation.hasPolicies}

                Error: ${finalValidation.errorMessage}

                This may be due to AWS eventual consistency. Please wait a few seconds and try again.
                """.trimIndent()
            eventBus.emit(Event.AwsSetup.ValidationFailed(errorMsg))
            throw IllegalStateException(errorMsg)
        }

        eventBus.emit(Event.AwsSetup.Complete)
    }

    /**
     * Handles authentication errors (invalid/missing credentials).
     * Provides guidance for reconfiguring easy-db-lab profile.
     */
    private fun handleAuthenticationError(exception: software.amazon.awssdk.core.exception.SdkServiceException) {
        eventBus.emit(Event.AwsSetup.AuthenticationError(exception.message ?: "Unknown error"))

        // Show required IAM policies
        val policies = AWSPolicy.UserIAM.loadAll("ACCOUNT_ID")
        policies.forEachIndexed { index, policy ->
            eventBus.emit(
                Event.AwsSetup.PolicyDisplay(
                    index = index + 1,
                    name = policy.name,
                    body = policy.body,
                ),
            )
        }

        eventBus.emit(Event.AwsSetup.PolicySeparator)
    }

    /**
     * Handles permission errors (valid credentials but insufficient permissions).
     * Provides detailed IAM policy guidance.
     */
    private fun handlePermissionError(exception: software.amazon.awssdk.core.exception.SdkServiceException) {
        eventBus.emit(
            Event.AwsSetup.IamPermissionError(
                """
                |
                |========================================
                |AWS PERMISSION ERROR
                |========================================
                |
                |Error: ${exception.message}
                |
                |To fix this issue, add the following IAM policies to your AWS user.
                |You need to create THREE separate inline policies:
                |
                """.trimMargin(),
            ),
        )

        // Try to get account ID, fallback to placeholder if that fails
        val accountId =
            try {
                aws.getAccountId() ?: error("Account ID is null")
            } catch (e: Exception) {
                eventBus.emit(Event.AwsSetup.AccountIdNote)
                "ACCOUNT_ID"
            }

        val policies = AWSPolicy.UserIAM.loadAll(accountId)
        policies.forEachIndexed { index, policy ->
            eventBus.emit(
                Event.AwsSetup.PolicyDisplay(
                    index = index + 1,
                    name = policy.name,
                    body = policy.body,
                ),
            )
        }

        eventBus.emit(Event.AwsSetup.PolicyRecommendation)
    }
}
