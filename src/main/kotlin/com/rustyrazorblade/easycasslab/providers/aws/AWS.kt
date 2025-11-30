package com.rustyrazorblade.easycasslab.providers.aws

import com.rustyrazorblade.easycasslab.Constants
import com.rustyrazorblade.easycasslab.output.OutputHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import software.amazon.awssdk.core.exception.SdkServiceException
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.iam.model.AddRoleToInstanceProfileRequest
import software.amazon.awssdk.services.iam.model.AttachRolePolicyRequest
import software.amazon.awssdk.services.iam.model.CreateInstanceProfileRequest
import software.amazon.awssdk.services.iam.model.CreateRoleRequest
import software.amazon.awssdk.services.iam.model.EntityAlreadyExistsException
import software.amazon.awssdk.services.iam.model.IamException
import software.amazon.awssdk.services.iam.model.PutRolePolicyRequest
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest
import software.amazon.awssdk.services.sts.model.StsException

/**
 * AWS infrastructure provider - wraps AWS SDK clients with domain-specific operations.
 *
 * This class provides LOW-LEVEL AWS operations that can be reused across multiple services
 * and commands. It focuses on direct AWS SDK interactions and does NOT contain orchestration
 * logic or complex setup workflows.
 *
 * ## Responsibilities
 *
 * ### AWS SDK Client Management
 * - Manages IAM, S3, and STS client instances
 * - Provides domain-appropriate method signatures
 * - Handles AWS SDK exceptions and error translation
 *
 * ### Account & Permission Operations
 * - Account ID retrieval and caching ([getAccountId])
 * - Credential validation ([checkPermissions])
 *
 * ### IAM Role Operations
 * - IAM role creation with trust policies ([createServiceRole], [createEMREC2Role], [createRoleWithS3Policy])
 * - Policy attachment to roles ([attachPolicy], [attachEMRRole], [attachEMREC2Role])
 * - Instance profile creation and role association
 * - Role validation ([validateRoleSetup], [roleExists])
 *
 * ### S3 Bucket Operations
 * - S3 bucket creation ([createS3Bucket])
 * - Bucket policy application ([putS3BucketPolicy])
 * - Idempotent operations (safe to call multiple times)
 *
 * ### Shared Constants
 * - Standard role names ([Constants.AWS.Roles.EMR_SERVICE_ROLE], [Constants.AWS.Roles.EC2_INSTANCE_ROLE], [Constants.AWS.Roles.EMR_EC2_ROLE])
 * - IAM policy templates with account ID substitution
 *
 * ## NOT Responsible For
 *
 * - **Setup orchestration** - Use [com.rustyrazorblade.easycasslab.services.AWSResourceSetupService]
 * - **Retry logic** - Handled by service layer using resilience4j
 * - **User-facing workflow coordination** - Services handle this
 * - **Configuration management** - Managed by UserConfigProvider
 * - **Complex validation workflows** - Delegated to services
 *
 * ## Usage Guidelines
 *
 * ### When to Add Methods Here
 * - Direct AWS SDK operations (create, get, attach, delete)
 * - Operations needed by multiple services or commands
 * - Infrastructure-level operations without business logic
 * - Error handling for AWS-specific exceptions
 *
 * ### When to Use Service Layer Instead
 * - Multi-step workflows requiring coordination
 * - Operations requiring retry logic or resilience
 * - User-facing setup or provisioning workflows
 * - Operations that modify user configuration
 * - Complex validation requiring multiple checks
 *
 * ## Relationship with AWSResourceSetupService
 *
 * - **AWS** (this class): Infrastructure layer - "how to talk to AWS"
 * - **AWSResourceSetupService**: Service layer - "how to set up resources"
 *
 * The service layer uses this class for low-level operations while adding:
 * - Orchestration logic (order of operations)
 * - Retry and error recovery
 * - User messaging and output
 * - Configuration updates
 * - Validation workflows
 *
 * ## Testing
 *
 * This class is mocked by default in [com.rustyrazorblade.easycasslab.BaseKoinTest],
 * allowing tests to verify behavior without making real AWS API calls.
 *
 * @property iamClient AWS IAM client for identity and access management operations
 * @property s3Client AWS S3 client for bucket operations
 * @property stsClient AWS STS client for credential and account operations
 * @property outputHandler Handler for user-facing messages and error output
 */
class AWS(
    private val iamClient: IamClient,
    private val s3Client: S3Client,
    private val stsClient: StsClient,
    private val outputHandler: OutputHandler,
) {
    private var cachedAccountId: String? = null

    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Retrieves the AWS account ID for the authenticated credentials.
     * Caches the result to avoid repeated STS API calls.
     *
     * @return The AWS account ID
     * @throws StsException if unable to retrieve account information
     */
    fun getAccountId(): String {
        if (cachedAccountId == null) {
            val response =
                stsClient.getCallerIdentity(
                    GetCallerIdentityRequest.builder().build(),
                )
            cachedAccountId = response.account()
            log.debug { "Retrieved and cached account ID: $cachedAccountId" }
        }
        return cachedAccountId!!
    }

    /**
     * Validates AWS credentials by calling STS GetCallerIdentity.
     * This ensures credentials are valid and the user can authenticate with AWS.
     * Displays account ID and user ARN for verification.
     *
     * @throws StsException if credentials are invalid or lack permissions
     * @throws SdkServiceException if there's a service error
     */
    fun checkPermissions() {
        try {
            val request = GetCallerIdentityRequest.builder().build()
            val response = stsClient.getCallerIdentity(request)

            // Cache the account ID for later use
            cachedAccountId = response.account()

            log.info { "AWS credentials validated successfully" }
            log.info { "Account: ${response.account()}" }
            log.info { "User ARN: ${response.arn()}" }
            log.info { "User ID: ${response.userId()}" }
        } catch (e: StsException) {
            log.error(e) { "AWS credential validation failed: ${e.message}" }
            throw e
        } catch (e: SdkServiceException) {
            log.error(e) { "AWS service error during credential validation: ${e.message}" }
            throw e
        }
    }

    /**
     * Creates an IAM role for EMR service with necessary permissions
     */
    fun createServiceRole(): String {
        try {
            // Create the IAM role
            val createRoleRequest =
                CreateRoleRequest
                    .builder()
                    .roleName(Constants.AWS.Roles.EMR_SERVICE_ROLE)
                    .assumeRolePolicyDocument(AWSPolicy.Trust.EMRService.toJson())
                    .description("IAM role for EMR service")
                    .build()

            iamClient.createRole(createRoleRequest)

            attachEMRRole()
        } catch (ignored: EntityAlreadyExistsException) {
            // Role already exists, continue
        }

        return Constants.AWS.Roles.EMR_SERVICE_ROLE
    }

    /**
     * Attaches a managed policy to an IAM role with retry logic for transient AWS failures.
     *
     * @param roleName The name of the IAM role
     * @param policy The ARN of the managed policy to attach
     */
    private fun attachPolicy(
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

    private fun attachEMRRole() {
        // Attach necessary managed policy
        return attachPolicy(Constants.AWS.Roles.EMR_SERVICE_ROLE, AWSPolicy.Managed.EMRServiceRole.arn)
    }

    private fun attachEMREC2Role() =
        attachPolicy(
            Constants.AWS.Roles.EMR_EC2_ROLE,
            AWSPolicy.Managed.EMRForEC2.arn,
        )

    /**
     * Creates an IAM role with the specified assume role policy and description.
     * Idempotent - succeeds even if role already exists.
     *
     * @param roleName The name of the IAM role to create
     * @param assumeRolePolicy The assume role policy document (JSON)
     * @param description Human-readable description of the role's purpose
     * @throws IamException if IAM operations fail
     */
    private fun createRole(
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
            log.info { "✓ Created IAM role: $roleName" }
        } catch (e: EntityAlreadyExistsException) {
            log.info { "✓ IAM role already exists: $roleName" }
        } catch (e: IamException) {
            log.error(e) { "Failed to create IAM role: $roleName - ${e.message}" }
            throw e
        }
    }

    /**
     * Attaches an inline S3 access policy to an IAM role, granting full access to the specified bucket.
     *
     * Uses retry logic with exponential backoff for transient AWS failures.
     *
     * @param roleName The name of the IAM role to attach the policy to
     * @param bucketName The S3 bucket name to grant access to
     * @throws IamException if IAM operations fail after retries
     */
    private fun attachS3Policy(
        roleName: String,
        bucketName: String,
    ) {
        val s3Policy = AWSPolicy.Inline.S3Access(bucketName).toJson()
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
            log.info { "✓ Attached S3 access policy to role: $roleName for bucket: $bucketName" }
        } catch (e: IamException) {
            log.error(e) { "Failed to attach S3 policy to role: $roleName - ${e.message}" }
            throw e
        }
    }

    /**
     * Creates an instance profile and attaches a role to it with retry logic for AWS eventual consistency.
     * Idempotent - succeeds even if profile already exists or role already attached.
     *
     * @param profileName The name of the instance profile to create
     * @param roleName The name of the IAM role to attach to the profile
     * @throws IamException if IAM operations fail after retries
     */
    private fun createInstanceProfile(
        profileName: String,
        roleName: String,
    ) {
        val retryConfig = RetryUtil.createIAMRetryConfig()
        val retry =
            io.github.resilience4j.retry.Retry
                .of("createInstanceProfile-$profileName", retryConfig)

        try {
            io.github.resilience4j.retry.Retry
                .decorateRunnable(retry) {
                    try {
                        // Create instance profile
                        val createProfileRequest =
                            CreateInstanceProfileRequest
                                .builder()
                                .instanceProfileName(profileName)
                                .build()

                        iamClient.createInstanceProfile(createProfileRequest)
                        log.info { "✓ Created instance profile: $profileName" }
                    } catch (e: EntityAlreadyExistsException) {
                        log.info { "✓ Instance profile already exists: $profileName" }
                    }

                    // Add role to instance profile
                    try {
                        val addRoleRequest =
                            AddRoleToInstanceProfileRequest
                                .builder()
                                .instanceProfileName(profileName)
                                .roleName(roleName)
                                .build()

                        iamClient.addRoleToInstanceProfile(addRoleRequest)
                        log.info { "✓ Added role $roleName to instance profile: $profileName" }
                    } catch (e: software.amazon.awssdk.services.iam.model.LimitExceededException) {
                        // Role already in instance profile - this is OK
                        log.info { "✓ Role already attached to instance profile: $profileName" }
                    }
                }.run()
        } catch (e: IamException) {
            log.error(e) { "Failed to create instance profile after retries: $profileName - ${e.message}" }
            throw e
        }
    }

    /**
     * Creates an IAM role and instance profile for EMR EC2 instances
     */
    fun createEMREC2Role(): String {
        // Create IAM role using shared helper
        createRole(
            Constants.AWS.Roles.EMR_EC2_ROLE,
            AWSPolicy.Trust.EC2Service.toJson(),
            "IAM role for EMR EC2 instances",
        )

        // Attach EMR EC2 managed policy
        attachEMREC2Role()

        // Create instance profile with retry logic
        createInstanceProfile(Constants.AWS.Roles.EMR_EC2_ROLE, Constants.AWS.Roles.EMR_EC2_ROLE)

        return Constants.AWS.Roles.EMR_EC2_ROLE
    }

    /**
     * Creates an S3 bucket with the specified name.
     * Idempotent - will succeed even if bucket already exists and is owned by you.
     *
     * @param bucketName The name of the bucket to create. Must be valid S3 bucket name (lowercase, 3-63 chars).
     * @return The bucket name
     * @throws IllegalArgumentException if bucket name is invalid
     */
    fun createS3Bucket(bucketName: String): String {
        require(bucketName.matches(Regex("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$"))) {
            "Invalid S3 bucket name: $bucketName. Must be lowercase, 3-63 chars, alphanumeric and hyphens only."
        }

        try {
            val request =
                CreateBucketRequest
                    .builder()
                    .bucket(bucketName)
                    .build()

            s3Client.createBucket(request)
            log.info { "Created S3 bucket: $bucketName" }
        } catch (e: BucketAlreadyOwnedByYouException) {
            log.info { "S3 bucket already exists and is owned by you: $bucketName" }
        } catch (e: BucketAlreadyExistsException) {
            log.info { "S3 bucket already exists: $bucketName" }
        } catch (e: S3Exception) {
            log.error(e) { "Failed to create S3 bucket: $bucketName - ${e.message}" }
            throw e
        }

        return bucketName
    }

    /**
     * Creates and applies an S3 bucket policy that grants access to all easy-cass-lab IAM roles.
     * Idempotent - will succeed even if policy already exists.
     *
     * The policy grants full S3 access to:
     * - EasyCassLabEC2Role (EC2 instances)
     * - EasyCassLabEMRServiceRole (EMR service)
     * - EasyCassLabEMREC2Role (EMR EC2 instances)
     *
     * @param bucketName The S3 bucket name to apply the policy to
     * @throws IllegalArgumentException if bucket name is invalid
     * @throws S3Exception if S3 operations fail
     */
    fun putS3BucketPolicy(bucketName: String) {
        require(bucketName.matches(Regex("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$"))) {
            "Invalid S3 bucket name: $bucketName. Must be lowercase, 3-63 chars, alphanumeric and hyphens only."
        }

        val accountId = getAccountId()
        val bucketPolicy = AWSPolicy.Inline.S3BucketPolicy(accountId, bucketName).toJson()

        try {
            val request =
                software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest
                    .builder()
                    .bucket(bucketName)
                    .policy(bucketPolicy)
                    .build()

            s3Client.putBucketPolicy(request)
            log.info { "✓ Applied S3 bucket policy granting access to all 3 IAM roles: $bucketName" }
        } catch (e: software.amazon.awssdk.services.s3.model.S3Exception) {
            log.error(e) { "Failed to apply S3 bucket policy: $bucketName - ${e.message}" }
            throw e
        }
    }

    /**
     * Checks if an IAM role exists.
     *
     * @param roleName The name of the IAM role to check
     * @return true if the role exists, false otherwise
     */
    fun roleExists(roleName: String): Boolean =
        try {
            val request =
                software.amazon.awssdk.services.iam.model.GetInstanceProfileRequest
                    .builder()
                    .instanceProfileName(roleName)
                    .build()
            iamClient.getInstanceProfile(request)
            true
        } catch (e: software.amazon.awssdk.services.iam.model.NoSuchEntityException) {
            false
        } catch (e: software.amazon.awssdk.services.iam.model.IamException) {
            log.error(e) { "Failed to get instance profile: $roleName - ${e.message}" }
            throw e
        }

    /**
     * Data class representing the validation result for IAM role setup.
     *
     * @property isValid True if all required components exist and are properly configured
     * @property instanceProfileExists True if the instance profile exists
     * @property roleAttached True if the role is attached to the instance profile
     * @property hasPolicies True if the role has inline policies attached
     * @property errorMessage Optional error message if validation failed
     */
    data class RoleValidationResult(
        val isValid: Boolean,
        val instanceProfileExists: Boolean,
        val roleAttached: Boolean,
        val hasPolicies: Boolean,
        val errorMessage: String? = null,
    )

    /**
     * Validates that the IAM role is properly set up with instance profile and policies.
     *
     * Checks that:
     * 1. Instance profile exists
     * 2. Role is attached to the instance profile
     * 3. Role has inline policies (S3 policy)
     *
     * @param roleName The name of the IAM role and instance profile to validate
     * @return RoleValidationResult with detailed validation status
     */
    fun validateRoleSetup(roleName: String): RoleValidationResult {
        try {
            // Check if instance profile exists and has role attached
            val getProfileRequest =
                software.amazon.awssdk.services.iam.model.GetInstanceProfileRequest
                    .builder()
                    .instanceProfileName(roleName)
                    .build()

            val profileResponse =
                try {
                    iamClient.getInstanceProfile(getProfileRequest)
                } catch (e: software.amazon.awssdk.services.iam.model.NoSuchEntityException) {
                    return RoleValidationResult(
                        isValid = false,
                        instanceProfileExists = false,
                        roleAttached = false,
                        hasPolicies = false,
                        errorMessage = "Instance profile '$roleName' does not exist",
                    )
                }

            // Check if role is attached to instance profile
            val roles = profileResponse.instanceProfile().roles()
            val roleAttached = roles.any { it.roleName() == roleName }

            if (!roleAttached) {
                return RoleValidationResult(
                    isValid = false,
                    instanceProfileExists = true,
                    roleAttached = false,
                    hasPolicies = false,
                    errorMessage = "Role '$roleName' is not attached to instance profile",
                )
            }

            // Check if role has inline policies
            val listPoliciesRequest =
                software.amazon.awssdk.services.iam.model.ListRolePoliciesRequest
                    .builder()
                    .roleName(roleName)
                    .build()

            val policiesResponse = iamClient.listRolePolicies(listPoliciesRequest)
            val hasPolicies = policiesResponse.policyNames().isNotEmpty()

            return RoleValidationResult(
                isValid = hasPolicies,
                instanceProfileExists = true,
                roleAttached = true,
                hasPolicies = hasPolicies,
                errorMessage =
                    if (!hasPolicies) {
                        "Role '$roleName' exists but has no inline policies"
                    } else {
                        null
                    },
            )
        } catch (e: software.amazon.awssdk.services.iam.model.IamException) {
            log.error(e) { "Failed to validate role: $roleName - ${e.message}" }
            return RoleValidationResult(
                isValid = false,
                instanceProfileExists = false,
                roleAttached = false,
                hasPolicies = false,
                errorMessage = "IAM error: ${e.message}",
            )
        }
    }

    /**
     * Creates an IAM role with EC2 trust policy and attaches an inline S3 policy granting full access to the specified bucket.
     * Idempotent - will succeed even if role already exists.
     *
     * Uses retry logic with exponential backoff for instance profile creation to handle AWS eventual consistency.
     *
     * @param roleName The name of the IAM role to create
     * @param bucketName The S3 bucket name to grant access to
     * @return The role name
     * @throws IllegalArgumentException if role name is invalid
     * @throws IamException if IAM operations fail after retries
     */
    fun createRoleWithS3Policy(
        roleName: String,
        bucketName: String,
    ): String {
        require(roleName.matches(Regex("^[\\w+=,.@-]{1,64}$"))) {
            "Invalid IAM role name: $roleName. Must be 1-64 chars, alphanumeric plus +=,.@-_ only."
        }

        log.info { "Setting up IAM role and instance profile: $roleName" }

        // Step 1: Create IAM role
        createRole(roleName, AWSPolicy.Trust.EC2Service.toJson(), "IAM role for easy-cass-lab with S3 access")

        // Step 2: Attach S3 access policy
        attachS3Policy(roleName, bucketName)

        // Step 3: Create instance profile with retry logic for AWS eventual consistency
        createInstanceProfile(roleName, roleName)

        // Step 4: Validate the complete setup
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

        log.info { "✓ IAM role setup complete and validated: $roleName" }
        return roleName
    }
}
