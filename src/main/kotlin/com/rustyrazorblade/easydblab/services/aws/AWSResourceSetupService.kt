package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.kernel.ProviderErrorHandler
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import com.rustyrazorblade.easydblab.onException
import com.rustyrazorblade.easydblab.providers.aws.AWSPolicy
import com.rustyrazorblade.easydblab.providers.aws.AccountIdentity
import com.rustyrazorblade.easydblab.providers.aws.IamRoles
import com.rustyrazorblade.easydblab.providers.aws.InstanceRolePolicies
import com.rustyrazorblade.easydblab.services.toEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.core.exception.SdkException

private val log = KotlinLogging.logger {}

/**
 * Service responsible for ensuring AWS IAM resources are set up
 * before any command runs. This runs automatically in CommandLineParser before command execution.
 *
 * Creates and validates the following AWS resources:
 * - EasyDBLabEC2Role: IAM role for EC2 instances (Cassandra, Stress, Control nodes, AMI builders),
 *   carrying the [InstanceRolePolicies] set
 * - EasyDBLabEMRServiceRole: IAM role for EMR service
 * - EasyDBLabEMREC2Role: IAM role for EMR EC2 instances (Spark clusters)
 *
 * Note: S3 buckets are now created per-environment in the Up command.
 * IAM roles use a wildcard S3 policy (easy-db-lab-*) to access all per-environment buckets.
 */
class AWSResourceSetupService(
    private val accountIdentity: AccountIdentity,
    private val iamRoles: IamRoles,
    private val eventBus: EventBus,
    private val instanceRolePolicies: InstanceRolePolicies,
    private val errorHandler: ProviderErrorHandler,
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
     * A failure propagates unchanged; the command failure reporter turns an AWS credentials or
     * permission failure into one provider error with its remedy.
     *
     * @return The AWS account ID
     */
    fun getAccountId(): String = accountIdentity.getAccountId()

    /**
     * Re-applies the instance role's inline policies, so `up` always launches instances under the
     * current permissions, the latest S3 delete denies included. Idempotent.
     */
    fun reapplyInstanceRolePolicies() = instanceRolePolicies.apply(Constants.AWS.Roles.EC2_INSTANCE_ROLE)

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
     * @throws IllegalStateException if resource setup fails validation
     */
    fun ensureAWSResources() {
        val roleName = Constants.AWS.Roles.EC2_INSTANCE_ROLE

        // Early return if resources already exist and are valid, once the role carries every
        // policy in its set. AMI builds reach this path only, so a role created before a policy
        // joined the set must pick it up here, before a builder instance launches under it.
        if (validateExistingResources(roleName)) {
            addMissingInstanceRolePolicies(roleName)
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
        val validation = iamRoles.validateRoleSetup(roleName)
        if (validation.isValid) {
            // All resources exist and are properly configured
            return true
        }

        // Resources exist in config but validation failed - will attempt to fix
        eventBus.emit(Event.AwsSetup.RepairWarning(validation.errorMessage ?: "Unknown validation error"))
        return false
    }

    private fun addMissingInstanceRolePolicies(roleName: String) {
        val missing = instanceRolePolicies.missing(roleName)
        if (missing.isEmpty()) return
        instanceRolePolicies.apply(roleName)
        eventBus.emit(Event.AwsSetup.InstanceRolePoliciesAdded(roleName, missing))
    }

    /**
     * Validates AWS credentials before attempting any operations.
     *
     * A credentials or permission failure is reported here as the same [Event.Command.ProviderError]
     * the command failure reporter emits, with the handler's remedy, and is rethrown as a
     * [CommandFailedException] so the reporter does not report it again. Any other failure (a
     * network error, clock skew, a bug) is rethrown unchanged: the reporter shows its real cause.
     */
    private fun validateCredentials() {
        try {
            accountIdentity.checkPermissions()
        } catch (e: SdkException) {
            if (reportCredentialFailure(e)) {
                log.error(e) { "AWS credential validation failed" }
                // This exception's own message goes to the debug log only, never to the user.
                throw CommandFailedException("AWS credential validation failed: ${e.message}", e)
            }
            throw e
        }
    }

    /**
     * Emits the provider error for a credentials or permission failure; returns whether it did.
     *
     * Credentials that do not work are followed by the lab's IAM policies, which a new user attaches
     * once the credentials work. A permission failure is not: the only call here is STS
     * GetCallerIdentity, which needs no IAM permission, so no lab policy fixes it, and the remedy
     * says what does.
     */
    private fun reportCredentialFailure(e: SdkException): Boolean {
        val description = errorHandler.describe(e) ?: return false
        if (description.kind == ProviderErrorKind.UNAVAILABLE) return false
        eventBus.emit(description.toEvent())
        if (description.kind == ProviderErrorKind.CREDENTIALS) showRequiredPolicies()
        return true
    }

    /**
     * Creates all 3 IAM roles and instance profiles with wildcard S3 policy.
     */
    private fun createIAMResources(roleName: String) {
        // Every failure is reported by its kind, then propagates unchanged; an Error is not reported.
        runCatching {
            // EC2 role (for Cassandra, Stress, Control nodes) with wildcard S3 policy
            iamRoles.createRoleWithS3Policy(roleName)
            instanceRolePolicies.apply(roleName)
            eventBus.emit(Event.AwsSetup.Ec2RoleReady(roleName))

            // EMR Service role
            iamRoles.createServiceRole()
            eventBus.emit(Event.AwsSetup.EmrServiceRoleReady(Constants.AWS.Roles.EMR_SERVICE_ROLE))

            // EMR EC2 role (for Spark clusters)
            iamRoles.createEMREC2Role()
            eventBus.emit(Event.AwsSetup.EmrEc2RoleReady(Constants.AWS.Roles.EMR_EC2_ROLE))
        }.onException(::emitIamError).getOrThrow()
    }

    private fun emitIamError(e: Exception) {
        when (e) {
            is software.amazon.awssdk.services.iam.model.IamException ->
                eventBus.emit(Event.AwsSetup.IamPermissionError(ERR_IAM_PERMISSIONS))
            is IllegalStateException ->
                eventBus.emit(Event.AwsSetup.IamValidationError(ERR_IAM_VALIDATION))
            else ->
                eventBus.emit(Event.AwsSetup.IamUnexpectedError(ERR_IAM_UNEXPECTED))
        }
    }

    /**
     * Performs final validation of IAM setup.
     */
    private fun finalizeSetup(roleName: String) {
        val finalValidation = iamRoles.validateRoleSetup(roleName)
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
            error(errorMsg)
        }

        eventBus.emit(Event.AwsSetup.Complete)
    }

    /**
     * Shows every lab IAM policy, numbered, after a credentials failure: the header names them and
     * their count, and the policies read [Constants.AWS.ACCOUNT_ID_PLACEHOLDER] where the account
     * goes, since no account is known.
     */
    private fun showRequiredPolicies() {
        val policies = AWSPolicy.UserIAM.loadAll(Constants.AWS.ACCOUNT_ID_PLACEHOLDER)
        eventBus.emit(Event.AwsSetup.RequiredPolicies(policies.map { it.name }))
        policies.forEachIndexed { index, policy ->
            eventBus.emit(Event.AwsSetup.PolicyDisplay(index = index + 1, name = policy.name, body = policy.body))
        }
        eventBus.emit(Event.AwsSetup.PolicySeparator)
    }
}
