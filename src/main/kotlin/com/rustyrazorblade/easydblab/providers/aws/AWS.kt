package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.core.exception.SdkServiceException
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest
import software.amazon.awssdk.services.s3.model.GetBucketTaggingRequest
import software.amazon.awssdk.services.s3.model.PutBucketTaggingRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.Tag
import software.amazon.awssdk.services.s3.model.Tagging
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
 * - **Setup orchestration** - Use [com.rustyrazorblade.easydblab.services.AWSResourceSetupService]
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
 * This class is mocked by default in [com.rustyrazorblade.easydblab.BaseKoinTest],
 * allowing tests to verify behavior without making real AWS API calls.
 *
 * @property iamClient AWS IAM client for identity and access management operations
 * @property s3Client AWS S3 client for bucket operations
 * @property stsClient AWS STS client for credential and account operations
 */
class AWS(
    internal val iamClient: IamClient,
    internal val s3Client: S3Client,
    private val stsClient: StsClient,
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

    // IAM role operations are in AWSIamExtensions.kt

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
     * Creates and applies an S3 bucket policy that grants access to all easy-db-lab IAM roles.
     * Idempotent - will succeed even if policy already exists.
     *
     * The policy grants full S3 access to:
     * - EasyDBLabEC2Role (EC2 instances)
     * - EasyDBLabEMRServiceRole (EMR service)
     * - EasyDBLabEMREC2Role (EMR EC2 instances)
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
     * Applies tags to an existing S3 bucket.
     *
     * This method is idempotent - calling it multiple times with the same tags will succeed.
     * Note that this replaces ALL existing tags on the bucket.
     *
     * @param bucketName The name of the S3 bucket to tag
     * @param tags Map of tag key-value pairs to apply
     * @throws IllegalArgumentException if bucket name is invalid
     * @throws S3Exception if S3 operations fail (e.g., bucket doesn't exist)
     */
    fun tagS3Bucket(
        bucketName: String,
        tags: Map<String, String>,
    ) {
        require(bucketName.matches(Regex("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$"))) {
            "Invalid S3 bucket name: $bucketName. Must be lowercase, 3-63 chars, alphanumeric and hyphens only."
        }

        val s3Tags =
            tags.map { (key, value) ->
                Tag
                    .builder()
                    .key(key)
                    .value(value)
                    .build()
            }

        val tagging =
            Tagging
                .builder()
                .tagSet(s3Tags)
                .build()

        val request =
            PutBucketTaggingRequest
                .builder()
                .bucket(bucketName)
                .tagging(tagging)
                .build()

        s3Client.putBucketTagging(request)
        log.info { "✓ Applied tags to S3 bucket: $bucketName (${tags.keys.joinToString(", ")})" }
    }

    /**
     * Finds an S3 bucket by a specific tag key-value pair.
     *
     * Only searches buckets with the "easy-db-lab-" prefix to avoid checking
     * unrelated buckets in the account.
     *
     * @param tagKey The tag key to search for
     * @param tagValue The tag value to match
     * @return The bucket name if found, null otherwise
     */
    fun findS3BucketByTag(
        tagKey: String,
        tagValue: String,
    ): String? {
        val buckets = s3Client.listBuckets().buckets()

        // Only check easy-db-lab prefixed buckets
        val easyDbLabBuckets = buckets.filter { it.name().startsWith(Constants.S3.BUCKET_PREFIX) }

        for (bucket in easyDbLabBuckets) {
            try {
                val taggingRequest =
                    GetBucketTaggingRequest
                        .builder()
                        .bucket(bucket.name())
                        .build()

                val taggingResponse = s3Client.getBucketTagging(taggingRequest)
                val matchingTag =
                    taggingResponse.tagSet().find {
                        it.key() == tagKey && it.value() == tagValue
                    }

                if (matchingTag != null) {
                    log.info { "Found bucket with $tagKey=$tagValue: ${bucket.name()}" }
                    return bucket.name()
                }
            } catch (e: S3Exception) {
                // Bucket may not have tags or we may not have permission - continue searching
                log.debug { "Could not get tags for bucket ${bucket.name()}: ${e.message}" }
            }
        }

        log.info { "No bucket found with tag $tagKey=$tagValue" }
        return null
    }

    /**
     * Finds all S3 buckets with the data bucket prefix and the easy_cass_lab tag.
     *
     * @return List of data bucket names
     */
    fun findDataBuckets(): List<String> {
        val buckets = s3Client.listBuckets().buckets()
        val dataBuckets =
            buckets.filter { it.name().startsWith(Constants.S3.DATA_BUCKET_PREFIX) }

        return dataBuckets.mapNotNull { bucket ->
            try {
                val taggingRequest =
                    GetBucketTaggingRequest
                        .builder()
                        .bucket(bucket.name())
                        .build()

                val taggingResponse = s3Client.getBucketTagging(taggingRequest)
                val hasTag =
                    taggingResponse.tagSet().any {
                        it.key() == Constants.Vpc.TAG_KEY && it.value() == Constants.Vpc.TAG_VALUE
                    }

                if (hasTag) bucket.name() else null
            } catch (e: S3Exception) {
                log.debug { "Could not get tags for bucket ${bucket.name()}: ${e.message}" }
                null
            }
        }
    }

    /**
     * Deletes an S3 bucket. Fails gracefully if the bucket is non-empty.
     *
     * @param bucketName The bucket to delete
     * @return true if deleted, false if non-empty or other non-fatal error
     */
    fun deleteS3Bucket(bucketName: String): Boolean =
        try {
            val request =
                DeleteBucketRequest
                    .builder()
                    .bucket(bucketName)
                    .build()
            s3Client.deleteBucket(request)
            log.info { "Deleted S3 bucket: $bucketName" }
            true
        } catch (e: S3Exception) {
            log.warn { "Could not delete bucket $bucketName: ${e.message}" }
            false
        }

    // IAM role validation and OpenSearch service-linked role are in AWSIamExtensions.kt

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
}
