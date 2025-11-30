package com.rustyrazorblade.easycasslab.services

import com.rustyrazorblade.easycasslab.Constants
import com.rustyrazorblade.easycasslab.configuration.User
import com.rustyrazorblade.easycasslab.configuration.UserConfigProvider
import com.rustyrazorblade.easycasslab.output.OutputHandler
import com.rustyrazorblade.easycasslab.providers.aws.AWS
import com.rustyrazorblade.easycasslab.providers.aws.AWSPolicy
import com.rustyrazorblade.easycasslab.providers.aws.RetryUtil
import io.github.resilience4j.retry.Retry

/**
 * Service responsible for ensuring AWS resources (credentials, S3 bucket, IAM roles) are set up
 * before any command runs. This runs automatically in CommandLineParser before command execution.
 *
 * Creates and validates the following AWS resources:
 * - S3 bucket for cluster data and logs
 * - EasyCassLabEC2Role: IAM role for EC2 instances (Cassandra, Stress, Control nodes)
 * - EasyCassLabEMRServiceRole: IAM role for EMR service
 * - EasyCassLabEMREC2Role: IAM role for EMR EC2 instances (Spark clusters)
 * - S3 bucket policy granting access to all 3 IAM roles
 */
class AWSResourceSetupService(
    private val aws: AWS,
    private val userConfigProvider: UserConfigProvider,
    private val outputHandler: OutputHandler,
) {
    companion object {
        // User-facing messages
        private const val MSG_REPAIR_WARNING = "Warning: IAM role configuration incomplete or invalid. Will attempt to repair."
        private const val MSG_SETUP_START = "Setting up AWS resources (S3 bucket, IAM roles, instance profiles)..."
        private const val MSG_SETUP_COMPLETE = "✓ AWS resources setup complete and validated"
        private const val MSG_S3_READY = "✓ S3 bucket ready:"
        private const val MSG_EC2_ROLE_READY = "✓ IAM role and instance profile ready:"
        private const val MSG_EMR_SERVICE_READY = "✓ EMR service role ready:"
        private const val MSG_EMR_EC2_READY = "✓ EMR EC2 role and instance profile ready:"
        private const val MSG_S3_POLICY_READY = "✓ S3 bucket policy applied for all IAM roles"

        // Error messages
        private const val ERR_CREDENTIAL_VALIDATION =
            "AWS credential validation failed. Please check your AWS credentials and permissions."
        private const val ERR_S3_BUCKET_CREATE = "Failed to create S3 bucket:"
        private const val ERR_S3_POLICY_APPLY = "Failed to apply S3 bucket policy:"
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

            You can generate EasyCassLabEC2, EasyCassLabIAM, and EasyCassLabEMR policies using:

            $ easy-cass-lab show-iam-policies

            """.trimIndent()
    }

    /**
     * Ensures all AWS resources are set up before any command runs.
     * Only creates resources if they don't exist in user config.
     * Validates credentials first.
     *
     * Performs comprehensive validation of IAM role setup including:
     * - Instance profile existence
     * - Role attachment to instance profile
     * - S3 policy attachment
     * - S3 bucket policy granting access to all 3 IAM roles
     *
     * @param userConfig The user configuration to check and update
     * @throws IllegalStateException if resource setup fails validation
     */
    fun ensureAWSResources(userConfig: User) {
        val roleName = Constants.AWS.Roles.EC2_INSTANCE_ROLE

        // Early return if resources already exist and are valid
        if (validateExistingResources(userConfig, roleName)) {
            return
        }

        outputHandler.handleMessage(MSG_SETUP_START)

        // Step 1: Validate credentials
        validateCredentials()

        // Step 2: Create S3 resources
        val bucketName = createS3Resources(userConfig)

        // Step 3: Create IAM resources
        createIAMResources(roleName, bucketName)

        // Step 4: Apply S3 bucket policy
        applyS3BucketPolicy(bucketName)

        // Step 5: Finalize setup
        finalizeSetup(userConfig, roleName, bucketName)
    }

    /**
     * Validates existing resources and returns true if all resources are properly configured.
     */
    private fun validateExistingResources(
        userConfig: User,
        roleName: String,
    ): Boolean {
        if (userConfig.s3Bucket.isBlank()) {
            return false
        }

        val validation = aws.validateRoleSetup(roleName)
        if (validation.isValid) {
            // All resources exist and are properly configured
            return true
        }

        // Resources exist in config but validation failed - will attempt to fix
        outputHandler.handleMessage(MSG_REPAIR_WARNING)
        outputHandler.handleMessage("  ${validation.errorMessage}")
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
            if (e.statusCode() == 403) {
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
            outputHandler.handleError(ERR_CREDENTIAL_VALIDATION, e)
            throw e
        }
    }

    /**
     * Creates S3 bucket with retry logic for transient failures.
     */
    private fun createS3Resources(userConfig: User): String {
        val bucketName = User.generateBucketName(userConfig)

        executeWithRetry(
            operationName = "createS3Bucket",
            operation = { aws.createS3Bucket(bucketName) },
            errorMessage = "$ERR_S3_BUCKET_CREATE $bucketName",
        )

        outputHandler.handleMessage("$MSG_S3_READY $bucketName")
        return bucketName
    }

    /**
     * Creates all 3 IAM roles and instance profiles.
     */
    private fun createIAMResources(
        roleName: String,
        bucketName: String,
    ) {
        try {
            // EC2 role (for Cassandra, Stress, Control nodes)
            aws.createRoleWithS3Policy(roleName, bucketName)
            outputHandler.handleMessage("$MSG_EC2_ROLE_READY $roleName")

            // EMR Service role
            aws.createServiceRole()
            outputHandler.handleMessage("$MSG_EMR_SERVICE_READY ${Constants.AWS.Roles.EMR_SERVICE_ROLE}")

            // EMR EC2 role (for Spark clusters)
            aws.createEMREC2Role()
            outputHandler.handleMessage("$MSG_EMR_EC2_READY ${Constants.AWS.Roles.EMR_EC2_ROLE}")
        } catch (e: software.amazon.awssdk.services.iam.model.IamException) {
            outputHandler.handleError(ERR_IAM_PERMISSIONS, e)
            throw e
        } catch (e: IllegalStateException) {
            outputHandler.handleError(ERR_IAM_VALIDATION, e)
            throw e
        } catch (e: Exception) {
            outputHandler.handleError(ERR_IAM_UNEXPECTED, e)
            throw e
        }
    }

    /**
     * Applies S3 bucket policy granting access to all 3 IAM roles with retry logic.
     */
    private fun applyS3BucketPolicy(bucketName: String) {
        executeWithRetry(
            operationName = "putS3BucketPolicy",
            operation = { aws.putS3BucketPolicy(bucketName) },
            errorMessage = "$ERR_S3_POLICY_APPLY $bucketName",
        )

        outputHandler.handleMessage(MSG_S3_POLICY_READY)
    }

    /**
     * Performs final validation and saves configuration.
     */
    private fun finalizeSetup(
        userConfig: User,
        roleName: String,
        bucketName: String,
    ) {
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
            outputHandler.handleError(errorMsg, null)
            throw IllegalStateException(errorMsg)
        }

        // Only update config after successful validation
        userConfig.s3Bucket = bucketName

        // Save updated config
        userConfigProvider.saveUserConfig(userConfig)

        outputHandler.handleMessage(MSG_SETUP_COMPLETE)
    }

    /**
     * Handles authentication errors (invalid/missing credentials).
     * Provides guidance for reconfiguring easy-cass-lab profile.
     */
    private fun handleAuthenticationError(exception: software.amazon.awssdk.core.exception.SdkServiceException) {
        outputHandler.handleMessage(
            """
            |
            |========================================
            |AWS CREDENTIAL ERROR
            |========================================
            |
            |Unable to validate AWS credentials: ${exception.message}
            |
            |This usually means:
            |  - AWS credentials are not configured
            |  - AWS credentials have expired
            |  - AWS access key or secret key is invalid
            |
            |To fix this issue, reconfigure your easy-cass-lab profile:
            |
            |  1. Remove incorrect profile: rm -rf ~/.easy_cass_lab/profiles/<PROFILE>
            |     (Replace <PROFILE> with your profile name, usually 'default')
            |
            |  2. Run: easy-cass-lab setup-profile
            |
            |  3. When prompted, enter your AWS access key and secret key
            |
            |To verify your credentials are working, run:
            |  aws sts get-caller-identity
            |
            |For full AWS permissions required by easy-cass-lab, add THREE inline policies:
            |
            """.trimMargin(),
        )

        // Show required IAM policies
        val policies = AWSPolicy.UserIAM.loadAll("ACCOUNT_ID")
        policies.forEachIndexed { index, policy ->
            outputHandler.handleMessage(
                """
                |========================================
                |Policy ${index + 1}: ${policy.name}
                |========================================
                |
                |${policy.body}
                |
                """.trimMargin(),
            )
        }

        outputHandler.handleMessage(
            """
            |========================================
            |
            """.trimMargin(),
        )
    }

    /**
     * Handles permission errors (valid credentials but insufficient permissions).
     * Provides detailed IAM policy guidance.
     */
    private fun handlePermissionError(exception: software.amazon.awssdk.core.exception.SdkServiceException) {
        outputHandler.handleMessage(
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
        )

        // Try to get account ID, fallback to placeholder if that fails
        val accountId =
            try {
                aws.getAccountId() ?: throw IllegalStateException("Account ID is null")
            } catch (e: Exception) {
                outputHandler.handleMessage(
                    """
                    |NOTE: Replace ACCOUNT_ID in the policies below with your AWS account ID.
                    |You can find your account ID in the error message above (the 12-digit number in the ARN).
                    |
                    """.trimMargin(),
                )
                "ACCOUNT_ID"
            }

        val policies = AWSPolicy.UserIAM.loadAll(accountId)
        policies.forEachIndexed { index, policy ->
            outputHandler.handleMessage(
                """
                |========================================
                |Policy ${index + 1}: ${policy.name}
                |========================================
                |
                |${policy.body}
                |
                """.trimMargin(),
            )
        }

        outputHandler.handleMessage(
            """
            |========================================
            |
            |RECOMMENDED: Create managed policies and attach to a group
            |  • No size limits (inline policies limited to 5,120 bytes total)
            |  • Required for EMR/Spark cluster functionality
            |  • Reusable across multiple users
            |
            |To apply these policies:
            |  1. Go to AWS IAM Console (https://console.aws.amazon.com/iam/)
            |  2. Create IAM group (e.g., "EasyCassLabUsers")
            |  3. Create three managed policies:
            |     - Policies → Create Policy → JSON tab
            |     - Paste policy content and name: EasyCassLabEC2, EasyCassLabIAM, EasyCassLabEMR
            |  4. Attach policies to your group
            |  5. Add your IAM user to the group
            |
            |ALTERNATIVE (single user): Attach as inline policies to your user
            |  WARNING: May hit 5,120 byte limit with all three policies
            |
            |========================================
            |
            """.trimMargin(),
        )
    }

    /**
     * Executes an operation with retry logic for transient AWS failures.
     * Uses shared RetryUtil for S3 operations with consistent retry behavior.
     */
    private fun <T> executeWithRetry(
        operationName: String,
        operation: () -> T,
        errorMessage: String,
    ): T {
        val retryConfig = RetryUtil.createAwsRetryConfig<T>()
        val retry = Retry.of(operationName, retryConfig)

        return try {
            Retry.decorateSupplier(retry, operation).get()
        } catch (e: Exception) {
            outputHandler.handleError(errorMessage, e)
            throw e
        }
    }

    /**
     * Executes an operation with simple error handling.
     */
    private fun <T> executeWithErrorHandling(
        operation: () -> T,
        errorMessage: String,
    ): T =
        try {
            operation()
        } catch (e: Exception) {
            outputHandler.handleError(errorMessage, e)
            throw e
        }
}
