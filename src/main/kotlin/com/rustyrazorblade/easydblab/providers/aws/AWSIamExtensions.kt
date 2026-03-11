package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import software.amazon.awssdk.services.iam.model.AddRoleToInstanceProfileRequest
import software.amazon.awssdk.services.iam.model.AttachRolePolicyRequest
import software.amazon.awssdk.services.iam.model.CreateInstanceProfileRequest
import software.amazon.awssdk.services.iam.model.CreateRoleRequest
import software.amazon.awssdk.services.iam.model.CreateServiceLinkedRoleRequest
import software.amazon.awssdk.services.iam.model.EntityAlreadyExistsException
import software.amazon.awssdk.services.iam.model.GetInstanceProfileRequest
import software.amazon.awssdk.services.iam.model.GetInstanceProfileResponse
import software.amazon.awssdk.services.iam.model.IamException
import software.amazon.awssdk.services.iam.model.InvalidInputException
import software.amazon.awssdk.services.iam.model.ListRolePoliciesRequest
import software.amazon.awssdk.services.iam.model.NoSuchEntityException
import software.amazon.awssdk.services.iam.model.PutRolePolicyRequest

private val log = KotlinLogging.logger {}

// IAM extension functions for [AWS].
// Extracted from the main class to keep function count manageable.

/**
 * Creates an IAM role for EMR service with necessary permissions.
 */
fun AWS.createServiceRole(): String {
    try {
        val createRoleRequest =
            CreateRoleRequest
                .builder()
                .roleName(Constants.AWS.Roles.EMR_SERVICE_ROLE)
                .assumeRolePolicyDocument(AWSPolicy.Trust.EMRService.toJson())
                .description("IAM role for EMR service")
                .build()

        iamClient.createRole(createRoleRequest)

        attachEmrManagedPolicy()
    } catch (ignored: EntityAlreadyExistsException) {
        // Role already exists, continue
    }

    return Constants.AWS.Roles.EMR_SERVICE_ROLE
}

/**
 * Creates an IAM role and instance profile for EMR EC2 instances.
 */
fun AWS.createEMREC2Role(): String {
    createIamRole(
        Constants.AWS.Roles.EMR_EC2_ROLE,
        AWSPolicy.Trust.EC2Service.toJson(),
        "IAM role for EMR EC2 instances",
    )

    attachIamPolicy(
        Constants.AWS.Roles.EMR_EC2_ROLE,
        AWSPolicy.Managed.EMRForEC2.arn,
    )

    createIamInstanceProfile(Constants.AWS.Roles.EMR_EC2_ROLE, Constants.AWS.Roles.EMR_EC2_ROLE)

    return Constants.AWS.Roles.EMR_EC2_ROLE
}

/**
 * Creates an IAM role with EC2 trust policy and attaches an inline S3 policy granting
 * access to all easy-db-lab buckets. Idempotent.
 *
 * @param roleName The name of the IAM role to create
 * @return The role name
 */
fun AWS.createRoleWithS3Policy(roleName: String): String {
    require(roleName.matches(Regex("^[\\w+=,.@-]{1,64}$"))) {
        "Invalid IAM role name: $roleName. Must be 1-64 chars, alphanumeric plus +=,.@-_ only."
    }

    log.info { "Setting up IAM role and instance profile: $roleName" }

    createIamRole(roleName, AWSPolicy.Trust.EC2Service.toJson(), "IAM role for easy-db-lab with S3 access")
    attachS3Policy(roleName)
    createIamInstanceProfile(roleName, roleName)

    log.info { "Validating IAM role setup: $roleName" }
    val validation = validateRoleSetup(roleName)
    if (!validation.isValid) {
        val errorMsg =
            "IAM role setup validation failed: ${validation.errorMessage}\n" +
                "  - Instance profile exists: ${validation.instanceProfileExists}\n" +
                "  - Role attached: ${validation.roleAttached}\n" +
                "  - Has policies: ${validation.hasPolicies}"
        log.error { errorMsg }
        throw IllegalStateException(errorMsg)
    }

    log.info { "IAM role setup complete and validated: $roleName" }
    return roleName
}

/**
 * Attaches an inline S3 access policy to an IAM role, granting full access to all easy-db-lab buckets.
 *
 * @param roleName The name of the IAM role to attach the policy to
 */
fun AWS.attachS3Policy(roleName: String) {
    val s3Policy = AWSPolicy.Inline.S3AccessWildcard.toJson()
    val retryConfig = RetryUtil.createIAMRetryConfig()
    val retry = Retry.of("attach-s3-policy-$roleName", retryConfig)

    try {
        Retry
            .decorateRunnable(retry) {
                val putPolicyRequest =
                    PutRolePolicyRequest
                        .builder()
                        .roleName(roleName)
                        .policyName("S3Access")
                        .policyDocument(s3Policy)
                        .build()

                iamClient.putRolePolicy(putPolicyRequest)
            }.run()
        log.info { "Attached S3 wildcard access policy to role: $roleName" }
    } catch (e: IamException) {
        log.error(e) { "Failed to attach S3 policy to role: $roleName - ${e.message}" }
        throw e
    }
}

/**
 * Checks if an IAM role exists.
 *
 * @param roleName The name of the IAM role to check
 * @return true if the role exists, false otherwise
 */
fun AWS.roleExists(roleName: String): Boolean =
    try {
        val request =
            GetInstanceProfileRequest
                .builder()
                .instanceProfileName(roleName)
                .build()
        iamClient.getInstanceProfile(request)
        true
    } catch (e: NoSuchEntityException) {
        false
    } catch (e: IamException) {
        log.error(e) { "Failed to get instance profile: $roleName - ${e.message}" }
        throw e
    }

/**
 * Validates that the IAM role is properly set up with instance profile and policies.
 *
 * @param roleName The name of the IAM role and instance profile to validate
 * @return RoleValidationResult with detailed validation status
 */
fun AWS.validateRoleSetup(roleName: String): AWS.RoleValidationResult =
    try {
        val profileResponse =
            getInstanceProfileOrNull(roleName)
                ?: return AWS.RoleValidationResult(
                    isValid = false,
                    instanceProfileExists = false,
                    roleAttached = false,
                    hasPolicies = false,
                    errorMessage = "Instance profile '$roleName' does not exist",
                )

        val roleAttached = profileResponse.instanceProfile().roles().any { it.roleName() == roleName }
        if (!roleAttached) {
            return AWS.RoleValidationResult(
                isValid = false,
                instanceProfileExists = true,
                roleAttached = false,
                hasPolicies = false,
                errorMessage = "Role '$roleName' is not attached to instance profile",
            )
        }

        val hasPolicies = checkRoleHasPolicies(roleName)
        AWS.RoleValidationResult(
            isValid = hasPolicies,
            instanceProfileExists = true,
            roleAttached = true,
            hasPolicies = hasPolicies,
            errorMessage = if (!hasPolicies) "Role '$roleName' exists but has no inline policies" else null,
        )
    } catch (e: IamException) {
        log.error(e) { "Failed to validate role: $roleName - ${e.message}" }
        AWS.RoleValidationResult(
            isValid = false,
            instanceProfileExists = false,
            roleAttached = false,
            hasPolicies = false,
            errorMessage = "IAM error: ${e.message}",
        )
    }

private fun AWS.getInstanceProfileOrNull(roleName: String): GetInstanceProfileResponse? =
    try {
        iamClient.getInstanceProfile(
            GetInstanceProfileRequest.builder().instanceProfileName(roleName).build(),
        )
    } catch (e: NoSuchEntityException) {
        null
    }

private fun AWS.checkRoleHasPolicies(roleName: String): Boolean {
    val response =
        iamClient.listRolePolicies(
            ListRolePoliciesRequest.builder().roleName(roleName).build(),
        )
    return response.policyNames().isNotEmpty()
}

/**
 * Ensures the OpenSearch service-linked role exists.
 */
fun AWS.ensureOpenSearchServiceLinkedRole() {
    val serviceName = "opensearchservice.amazonaws.com"
    log.info { "Ensuring OpenSearch service-linked role exists..." }

    try {
        val request =
            CreateServiceLinkedRoleRequest
                .builder()
                .awsServiceName(serviceName)
                .description("Service-linked role for Amazon OpenSearch Service VPC access")
                .build()

        iamClient.createServiceLinkedRole(request)
        log.info { "Created OpenSearch service-linked role" }
    } catch (e: InvalidInputException) {
        if (e.message?.contains("has been taken") == true ||
            e.message?.contains("already exists") == true
        ) {
            log.info { "OpenSearch service-linked role already exists" }
        } else {
            log.error(e) { "Failed to create OpenSearch service-linked role: ${e.message}" }
            throw e
        }
    } catch (e: IamException) {
        log.error(e) { "Failed to create OpenSearch service-linked role: ${e.message}" }
        throw e
    }
}

// Internal helpers used by the public IAM extension functions above.

internal fun AWS.attachEmrManagedPolicy() {
    attachIamPolicy(Constants.AWS.Roles.EMR_SERVICE_ROLE, AWSPolicy.Managed.EMRServiceRole.arn)
}

internal fun AWS.attachIamPolicy(
    roleName: String,
    policy: String,
) {
    val retryConfig = RetryUtil.createIAMRetryConfig()
    val retry = Retry.of("attach-policy-$roleName", retryConfig)

    Retry
        .decorateRunnable(retry) {
            val attachPolicyRequest =
                AttachRolePolicyRequest
                    .builder()
                    .roleName(roleName)
                    .policyArn(policy)
                    .build()
            iamClient.attachRolePolicy(attachPolicyRequest)
        }.run()
}

internal fun AWS.createIamRole(
    roleName: String,
    assumeRolePolicy: String,
    description: String,
) {
    try {
        val createRoleRequest =
            CreateRoleRequest
                .builder()
                .roleName(roleName)
                .assumeRolePolicyDocument(assumeRolePolicy)
                .description(description)
                .build()

        iamClient.createRole(createRoleRequest)
        log.info { "Created IAM role: $roleName" }
    } catch (e: EntityAlreadyExistsException) {
        log.info { "IAM role already exists: $roleName" }
    } catch (e: IamException) {
        log.error(e) { "Failed to create IAM role: $roleName - ${e.message}" }
        throw e
    }
}

internal fun AWS.createIamInstanceProfile(
    profileName: String,
    roleName: String,
) {
    val retryConfig = RetryUtil.createIAMRetryConfig()
    val retry = Retry.of("createInstanceProfile-$profileName", retryConfig)

    try {
        Retry
            .decorateRunnable(retry) {
                try {
                    val createProfileRequest =
                        CreateInstanceProfileRequest
                            .builder()
                            .instanceProfileName(profileName)
                            .build()

                    iamClient.createInstanceProfile(createProfileRequest)
                    log.info { "Created instance profile: $profileName" }
                } catch (e: EntityAlreadyExistsException) {
                    log.info { "Instance profile already exists: $profileName" }
                }

                try {
                    val addRoleRequest =
                        AddRoleToInstanceProfileRequest
                            .builder()
                            .instanceProfileName(profileName)
                            .roleName(roleName)
                            .build()

                    iamClient.addRoleToInstanceProfile(addRoleRequest)
                    log.info { "Added role $roleName to instance profile: $profileName" }
                } catch (
                    e: software.amazon.awssdk.services.iam.model.LimitExceededException,
                ) {
                    log.info { "Role already attached to instance profile: $profileName" }
                }
            }.run()
    } catch (e: IamException) {
        log.error(e) { "Failed to create instance profile after retries: $profileName - ${e.message}" }
        throw e
    }
}
