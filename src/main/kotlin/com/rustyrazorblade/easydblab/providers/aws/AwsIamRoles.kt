package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import software.amazon.awssdk.services.iam.IamClient
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
import software.amazon.awssdk.services.iam.model.LimitExceededException
import software.amazon.awssdk.services.iam.model.ListRolePoliciesRequest
import software.amazon.awssdk.services.iam.model.NoSuchEntityException
import software.amazon.awssdk.services.iam.model.PutRolePolicyRequest

/**
 * [IamRoles] on the IAM SDK client. It is the only class that holds the [IamClient].
 *
 * It does LOW-LEVEL IAM operations only: no orchestration, no user-facing output. The service layer
 * ([com.rustyrazorblade.easydblab.services.aws.AWSResourceSetupService]) sequences these calls and
 * reports on them. The account ID that the inline S3 policy needs comes from [accountIdentity].
 */
class AwsIamRoles(
    private val iamClient: IamClient,
    private val accountIdentity: AccountIdentity,
) : IamRoles {
    private companion object {
        private val log = KotlinLogging.logger {}
        private const val OPENSEARCH_SERVICE_NAME = "opensearchservice.amazonaws.com"
    }

    override fun createServiceRole(): String {
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

    override fun createEMREC2Role(): String {
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

    override fun createRoleWithS3Policy(roleName: String): String {
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
            error(errorMsg)
        }

        log.info { "IAM role setup complete and validated: $roleName" }
        return roleName
    }

    override fun attachS3Policy(roleName: String) {
        val s3Policy = AWSPolicy.Inline.S3AccessWildcard(accountIdentity.getAccountId()).toJson()
        try {
            putInlinePolicy(roleName, Constants.AWS.InlinePolicies.S3_ACCESS, s3Policy, "attach-s3-policy-$roleName")
            log.info { "Attached S3 wildcard access policy to role: $roleName" }
        } catch (e: IamException) {
            log.error(e) { "Failed to attach S3 policy to role: $roleName - ${e.message}" }
            throw e
        }
    }

    override fun putInlinePolicy(
        roleName: String,
        policyName: String,
        document: String,
    ) = putInlinePolicy(roleName, policyName, document, "put-role-policy-$roleName-$policyName")

    private fun putInlinePolicy(
        roleName: String,
        policyName: String,
        document: String,
        retryName: String,
    ) {
        Retry
            .decorateRunnable(Retry.of(retryName, RetryUtil.createIAMRetryConfig())) {
                iamClient.putRolePolicy(
                    PutRolePolicyRequest
                        .builder()
                        .roleName(roleName)
                        .policyName(policyName)
                        .policyDocument(document)
                        .build(),
                )
            }.run()
    }

    override fun inlinePolicyNames(roleName: String): Set<String> =
        iamClient
            .listRolePolicies(ListRolePoliciesRequest.builder().roleName(roleName).build())
            .policyNames()
            .toSet()

    override fun roleExists(roleName: String): Boolean =
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

    override fun validateRoleSetup(roleName: String): RoleValidationResult =
        try {
            val profileResponse =
                getInstanceProfileOrNull(roleName)
                    ?: return RoleValidationResult(
                        isValid = false,
                        instanceProfileExists = false,
                        roleAttached = false,
                        hasPolicies = false,
                        errorMessage = "Instance profile '$roleName' does not exist",
                    )

            val roleAttached = profileResponse.instanceProfile().roles().any { it.roleName() == roleName }
            if (!roleAttached) {
                return RoleValidationResult(
                    isValid = false,
                    instanceProfileExists = true,
                    roleAttached = false,
                    hasPolicies = false,
                    errorMessage = "Role '$roleName' is not attached to instance profile",
                )
            }

            val hasPolicies = checkRoleHasPolicies(roleName)
            RoleValidationResult(
                isValid = hasPolicies,
                instanceProfileExists = true,
                roleAttached = true,
                hasPolicies = hasPolicies,
                errorMessage = if (!hasPolicies) "Role '$roleName' exists but has no inline policies" else null,
            )
        } catch (e: IamException) {
            log.error(e) { "Failed to validate role: $roleName - ${e.message}" }
            RoleValidationResult(
                isValid = false,
                instanceProfileExists = false,
                roleAttached = false,
                hasPolicies = false,
                errorMessage = "IAM error: ${e.message}",
            )
        }

    private fun getInstanceProfileOrNull(roleName: String): GetInstanceProfileResponse? =
        try {
            iamClient.getInstanceProfile(
                GetInstanceProfileRequest.builder().instanceProfileName(roleName).build(),
            )
        } catch (e: NoSuchEntityException) {
            null
        }

    private fun checkRoleHasPolicies(roleName: String): Boolean {
        val response =
            iamClient.listRolePolicies(
                ListRolePoliciesRequest.builder().roleName(roleName).build(),
            )
        return response.policyNames().isNotEmpty()
    }

    override fun ensureOpenSearchServiceLinkedRole() {
        log.info { "Ensuring OpenSearch service-linked role exists..." }

        try {
            val request =
                CreateServiceLinkedRoleRequest
                    .builder()
                    .awsServiceName(OPENSEARCH_SERVICE_NAME)
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

    private fun attachEmrManagedPolicy() {
        attachIamPolicy(Constants.AWS.Roles.EMR_SERVICE_ROLE, AWSPolicy.Managed.EMRServiceRole.arn)
    }

    private fun attachIamPolicy(
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

    private fun createIamRole(
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

    private fun createIamInstanceProfile(
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
                    } catch (e: LimitExceededException) {
                        log.info { "Role already attached to instance profile: $profileName" }
                    }
                }.run()
        } catch (e: IamException) {
            log.error(e) { "Failed to create instance profile after retries: $profileName - ${e.message}" }
            throw e
        }
    }
}
