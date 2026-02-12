package com.rustyrazorblade.easydblab.providers

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.providers.aws.AWS
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.iam.model.AttachRolePolicyRequest
import software.amazon.awssdk.services.iam.model.AttachRolePolicyResponse
import software.amazon.awssdk.services.iam.model.CreateRoleRequest
import software.amazon.awssdk.services.iam.model.CreateRoleResponse
import software.amazon.awssdk.services.iam.model.EntityAlreadyExistsException
import software.amazon.awssdk.services.iam.model.PutRolePolicyRequest
import software.amazon.awssdk.services.iam.model.PutRolePolicyResponse
import software.amazon.awssdk.services.iam.model.Role
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.Bucket
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.CreateBucketResponse
import software.amazon.awssdk.services.s3.model.DeleteBucketMetricsConfigurationRequest
import software.amazon.awssdk.services.s3.model.DeleteBucketMetricsConfigurationResponse
import software.amazon.awssdk.services.s3.model.GetBucketTaggingRequest
import software.amazon.awssdk.services.s3.model.GetBucketTaggingResponse
import software.amazon.awssdk.services.s3.model.ListBucketsResponse
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.PutBucketMetricsConfigurationRequest
import software.amazon.awssdk.services.s3.model.PutBucketMetricsConfigurationResponse
import software.amazon.awssdk.services.s3.model.PutBucketTaggingRequest
import software.amazon.awssdk.services.s3.model.PutBucketTaggingResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.Tag
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse

internal class AWSTest :
    BaseKoinTest(),
    KoinComponent {
    // Inject the mocked AWS service and AWS clients from BaseKoinTest
    private val aws: AWS by inject()
    private val mockIamClient: IamClient by inject()
    private val mockS3Client: S3Client by inject()
    private val mockStsClient: StsClient by inject()

    @Test
    fun createEMRServiceRoleSuccess() {
        // mockIamClient is already injected from BaseKoinTest

        // Setup mock responses
        val mockRole =
            Role
                .builder()
                .roleName(Constants.AWS.Roles.EMR_SERVICE_ROLE)
                .arn("arn:aws:iam::123456789:role/${Constants.AWS.Roles.EMR_SERVICE_ROLE}")
                .build()

        val createRoleResponse =
            CreateRoleResponse
                .builder()
                .role(mockRole)
                .build()

        whenever(mockIamClient.createRole(any<CreateRoleRequest>())).thenReturn(createRoleResponse)

        val attachPolicyResponse = AttachRolePolicyResponse.builder().build()
        whenever(mockIamClient.attachRolePolicy(any<AttachRolePolicyRequest>())).thenReturn(attachPolicyResponse)

        // Execute the method
        val result = aws.createServiceRole()

        // Verify the expected calls were made
        verify(mockIamClient).createRole(any<CreateRoleRequest>())
        verify(mockIamClient).attachRolePolicy(any<AttachRolePolicyRequest>())

        // Assert the result using AssertJ
        assertThat(result).isEqualTo(Constants.AWS.Roles.EMR_SERVICE_ROLE)
    }

    @Test
    fun createEMRServiceRoleAlreadyExists() {
        // mockIamClient is already injected from BaseKoinTest

        // Setup mock to throw EntityAlreadyExistsException
        whenever(mockIamClient.createRole(any<CreateRoleRequest>()))
            .thenThrow(
                EntityAlreadyExistsException
                    .builder()
                    .message("Role already exists")
                    .build(),
            )

        // Execute the method - should not throw exception
        val result = aws.createServiceRole()

        // Verify the createRole was attempted
        verify(mockIamClient).createRole(any<CreateRoleRequest>())

        // Assert the result using AssertJ
        assertThat(result).isEqualTo(Constants.AWS.Roles.EMR_SERVICE_ROLE)
    }

    @Test
    fun createS3BucketSuccess() {
        val bucketName = "easy-db-lab-test-bucket"

        // Setup mock response
        val createBucketResponse = CreateBucketResponse.builder().build()
        whenever(mockS3Client.createBucket(any<CreateBucketRequest>())).thenReturn(createBucketResponse)

        // Execute the method
        val result = aws.createS3Bucket(bucketName)

        // Verify the expected call was made
        verify(mockS3Client).createBucket(any<CreateBucketRequest>())

        // Assert the result
        assertThat(result).isEqualTo(bucketName)
    }

    @Test
    fun createS3BucketAlreadyExists() {
        val bucketName = "easy-db-lab-existing-bucket"

        // Setup mock to throw BucketAlreadyOwnedByYouException
        whenever(mockS3Client.createBucket(any<CreateBucketRequest>()))
            .thenThrow(
                BucketAlreadyOwnedByYouException
                    .builder()
                    .message("Bucket already owned by you")
                    .build(),
            )

        // Execute the method - should not throw exception
        val result = aws.createS3Bucket(bucketName)

        // Verify the createBucket was attempted
        verify(mockS3Client).createBucket(any<CreateBucketRequest>())

        // Assert the result
        assertThat(result).isEqualTo(bucketName)
    }

    @Test
    fun createS3BucketInvalidName() {
        // Test with uppercase letters (invalid)
        assertThrows<IllegalArgumentException> {
            aws.createS3Bucket("Invalid-Bucket-Name")
        }

        // Test with too short name
        assertThrows<IllegalArgumentException> {
            aws.createS3Bucket("ab")
        }
    }

    @Test
    fun createRoleWithS3PolicySuccess() {
        val roleName = "easy-db-lab-test-role"

        // Setup mock responses
        val mockRole =
            Role
                .builder()
                .roleName(roleName)
                .arn("arn:aws:iam::123456789:role/$roleName")
                .build()

        val createRoleResponse =
            CreateRoleResponse
                .builder()
                .role(mockRole)
                .build()

        whenever(mockIamClient.createRole(any<CreateRoleRequest>())).thenReturn(createRoleResponse)

        val putPolicyResponse = PutRolePolicyResponse.builder().build()
        whenever(mockIamClient.putRolePolicy(any<PutRolePolicyRequest>())).thenReturn(putPolicyResponse)

        // Mock instance profile operations for retry logic
        val createInstanceProfileResponse =
            software.amazon.awssdk.services.iam.model.CreateInstanceProfileResponse
                .builder()
                .build()
        whenever(mockIamClient.createInstanceProfile(any<software.amazon.awssdk.services.iam.model.CreateInstanceProfileRequest>()))
            .thenReturn(createInstanceProfileResponse)

        val addRoleResponse =
            software.amazon.awssdk.services.iam.model.AddRoleToInstanceProfileResponse
                .builder()
                .build()
        whenever(mockIamClient.addRoleToInstanceProfile(any<software.amazon.awssdk.services.iam.model.AddRoleToInstanceProfileRequest>()))
            .thenReturn(addRoleResponse)

        // Mock validation calls
        val mockInstanceProfile =
            software.amazon.awssdk.services.iam.model.InstanceProfile
                .builder()
                .instanceProfileName(roleName)
                .roles(mockRole)
                .build()
        val getInstanceProfileResponse =
            software.amazon.awssdk.services.iam.model.GetInstanceProfileResponse
                .builder()
                .instanceProfile(mockInstanceProfile)
                .build()
        whenever(mockIamClient.getInstanceProfile(any<software.amazon.awssdk.services.iam.model.GetInstanceProfileRequest>()))
            .thenReturn(getInstanceProfileResponse)

        val listPoliciesResponse =
            software.amazon.awssdk.services.iam.model.ListRolePoliciesResponse
                .builder()
                .policyNames("S3Access")
                .build()
        whenever(mockIamClient.listRolePolicies(any<software.amazon.awssdk.services.iam.model.ListRolePoliciesRequest>()))
            .thenReturn(listPoliciesResponse)

        // Execute the method
        val result = aws.createRoleWithS3Policy(roleName)

        // Verify the expected calls were made
        verify(mockIamClient).createRole(any<CreateRoleRequest>())
        verify(mockIamClient).putRolePolicy(any<PutRolePolicyRequest>())
        verify(mockIamClient).createInstanceProfile(any<software.amazon.awssdk.services.iam.model.CreateInstanceProfileRequest>())
        verify(mockIamClient).addRoleToInstanceProfile(any<software.amazon.awssdk.services.iam.model.AddRoleToInstanceProfileRequest>())
        verify(mockIamClient).getInstanceProfile(any<software.amazon.awssdk.services.iam.model.GetInstanceProfileRequest>())
        verify(mockIamClient).listRolePolicies(any<software.amazon.awssdk.services.iam.model.ListRolePoliciesRequest>())

        // Assert the result
        assertThat(result).isEqualTo(roleName)
    }

    @Test
    fun createRoleWithS3PolicyRoleAlreadyExists() {
        val roleName = "easy-db-lab-existing-role"

        val mockRole =
            Role
                .builder()
                .roleName(roleName)
                .arn("arn:aws:iam::123456789:role/$roleName")
                .build()

        // Setup mock to throw EntityAlreadyExistsException for role creation
        whenever(mockIamClient.createRole(any<CreateRoleRequest>()))
            .thenThrow(
                EntityAlreadyExistsException
                    .builder()
                    .message("Role already exists")
                    .build(),
            )

        val putPolicyResponse = PutRolePolicyResponse.builder().build()
        whenever(mockIamClient.putRolePolicy(any<PutRolePolicyRequest>())).thenReturn(putPolicyResponse)

        // Mock instance profile operations
        val createInstanceProfileResponse =
            software.amazon.awssdk.services.iam.model.CreateInstanceProfileResponse
                .builder()
                .build()
        whenever(mockIamClient.createInstanceProfile(any<software.amazon.awssdk.services.iam.model.CreateInstanceProfileRequest>()))
            .thenReturn(createInstanceProfileResponse)

        val addRoleResponse =
            software.amazon.awssdk.services.iam.model.AddRoleToInstanceProfileResponse
                .builder()
                .build()
        whenever(mockIamClient.addRoleToInstanceProfile(any<software.amazon.awssdk.services.iam.model.AddRoleToInstanceProfileRequest>()))
            .thenReturn(addRoleResponse)

        // Mock validation calls
        val mockInstanceProfile =
            software.amazon.awssdk.services.iam.model.InstanceProfile
                .builder()
                .instanceProfileName(roleName)
                .roles(mockRole)
                .build()
        val getInstanceProfileResponse =
            software.amazon.awssdk.services.iam.model.GetInstanceProfileResponse
                .builder()
                .instanceProfile(mockInstanceProfile)
                .build()
        whenever(mockIamClient.getInstanceProfile(any<software.amazon.awssdk.services.iam.model.GetInstanceProfileRequest>()))
            .thenReturn(getInstanceProfileResponse)

        val listPoliciesResponse =
            software.amazon.awssdk.services.iam.model.ListRolePoliciesResponse
                .builder()
                .policyNames("S3Access")
                .build()
        whenever(mockIamClient.listRolePolicies(any<software.amazon.awssdk.services.iam.model.ListRolePoliciesRequest>()))
            .thenReturn(listPoliciesResponse)

        // Execute the method - should not throw exception
        val result = aws.createRoleWithS3Policy(roleName)

        // Verify the createRole was attempted
        verify(mockIamClient).createRole(any<CreateRoleRequest>())
        // Verify the policy was still attached
        verify(mockIamClient).putRolePolicy(any<PutRolePolicyRequest>())

        // Assert the result
        assertThat(result).isEqualTo(roleName)
    }

    @Test
    fun createRoleWithS3PolicyInvalidRoleName() {
        // Test with invalid characters
        assertThrows<IllegalArgumentException> {
            aws.createRoleWithS3Policy("invalid role name with spaces")
        }

        // Test with too long name (> 64 chars)
        val longName = "a".repeat(65)
        assertThrows<IllegalArgumentException> {
            aws.createRoleWithS3Policy(longName)
        }
    }

    @Test
    fun checkPermissionsSuccess() {
        // Setup mock response for successful credential validation
        val mockResponse =
            GetCallerIdentityResponse
                .builder()
                .account("123456789012")
                .arn("arn:aws:iam::123456789012:user/test-user")
                .userId("AIDACKCEVSQ6C2EXAMPLE")
                .build()

        whenever(mockStsClient.getCallerIdentity(any<GetCallerIdentityRequest>()))
            .thenReturn(mockResponse)

        // Execute - should not throw exception
        aws.checkPermissions()

        // Verify STS client was called
        verify(mockStsClient).getCallerIdentity(any<GetCallerIdentityRequest>())
    }

    @Test
    fun `getAccountId should cache account ID on first call`() {
        val mockResponse =
            GetCallerIdentityResponse
                .builder()
                .account("123456789012")
                .arn("arn:aws:iam::123456789012:user/test-user")
                .userId("AIDACKCEVSQ6C2EXAMPLE")
                .build()

        whenever(mockStsClient.getCallerIdentity(any<GetCallerIdentityRequest>()))
            .thenReturn(mockResponse)

        // First call - should populate cache
        val accountId1 = aws.getAccountId()

        // Second call - should use cached value
        val accountId2 = aws.getAccountId()

        assertThat(accountId1).isEqualTo("123456789012")
        assertThat(accountId2).isEqualTo("123456789012")

        // STS should only be called once due to caching
        verify(mockStsClient, org.mockito.kotlin.times(1)).getCallerIdentity(any<GetCallerIdentityRequest>())
    }

    @Test
    fun `getAccountId should throw when STS fails`() {
        val stsException =
            software.amazon.awssdk.services.sts.model.StsException
                .builder()
                .statusCode(403)
                .message("Access denied")
                .build()

        whenever(mockStsClient.getCallerIdentity(any<GetCallerIdentityRequest>()))
            .thenThrow(stsException)

        assertThrows<software.amazon.awssdk.services.sts.model.StsException> {
            aws.getAccountId()
        }
    }

    @Test
    fun `checkPermissions should cache account ID for later use`() {
        val mockResponse =
            GetCallerIdentityResponse
                .builder()
                .account("123456789012")
                .arn("arn:aws:iam::123456789012:user/test-user")
                .userId("AIDACKCEVSQ6C2EXAMPLE")
                .build()

        whenever(mockStsClient.getCallerIdentity(any<GetCallerIdentityRequest>()))
            .thenReturn(mockResponse)

        // Call checkPermissions - should cache account ID
        aws.checkPermissions()

        // getAccountId should return cached value without additional STS call
        val accountId = aws.getAccountId()

        assertThat(accountId).isEqualTo("123456789012")

        // STS should only be called once (by checkPermissions)
        verify(mockStsClient, org.mockito.kotlin.times(1)).getCallerIdentity(any<GetCallerIdentityRequest>())
    }

    @Test
    fun `tagS3Bucket should apply tags to bucket`() {
        val bucketName = "easy-db-lab-test-bucket"
        val tags = mapOf("ClusterId" to "abc123", "Environment" to "test")

        // Setup mock response
        val putTaggingResponse = PutBucketTaggingResponse.builder().build()
        whenever(mockS3Client.putBucketTagging(any<PutBucketTaggingRequest>())).thenReturn(putTaggingResponse)

        // Execute
        aws.tagS3Bucket(bucketName, tags)

        // Verify the correct request was made
        verify(mockS3Client).putBucketTagging(
            org.mockito.kotlin.argThat<PutBucketTaggingRequest> { request ->
                request.bucket() == bucketName &&
                    request.tagging().tagSet().size == 2 &&
                    request.tagging().tagSet().any { it.key() == "ClusterId" && it.value() == "abc123" } &&
                    request.tagging().tagSet().any { it.key() == "Environment" && it.value() == "test" }
            },
        )
    }

    @Test
    fun `tagS3Bucket should throw exception for non-existent bucket`() {
        val bucketName = "non-existent-bucket"
        val tags = mapOf("ClusterId" to "abc123")

        // Setup mock to throw NoSuchBucketException
        whenever(mockS3Client.putBucketTagging(any<PutBucketTaggingRequest>()))
            .thenThrow(
                NoSuchBucketException
                    .builder()
                    .message("Bucket does not exist")
                    .build(),
            )

        // Execute - should throw exception
        assertThrows<NoSuchBucketException> {
            aws.tagS3Bucket(bucketName, tags)
        }
    }

    @Test
    fun `tagS3Bucket should validate bucket name`() {
        // Test with invalid bucket name
        assertThrows<IllegalArgumentException> {
            aws.tagS3Bucket("Invalid-Bucket-Name", mapOf("ClusterId" to "abc123"))
        }
    }

    @Test
    fun `findS3BucketByTag should return bucket name when tag matches`() {
        val targetBucket = "easy-db-lab-myproject-abc12345"
        val clusterId = "abc12345-full-uuid"

        // Setup mock for listBuckets
        val buckets =
            listOf(
                Bucket.builder().name("other-bucket-1").build(),
                Bucket.builder().name(targetBucket).build(),
                Bucket.builder().name("other-bucket-2").build(),
            )
        val listBucketsResponse = ListBucketsResponse.builder().buckets(buckets).build()
        whenever(mockS3Client.listBuckets()).thenReturn(listBucketsResponse)

        // Setup mock for getBucketTagging - first bucket has no ClusterId tag
        val otherBucket1Tags =
            GetBucketTaggingResponse
                .builder()
                .tagSet(
                    Tag
                        .builder()
                        .key("Environment")
                        .value("prod")
                        .build(),
                ).build()

        // Target bucket has the matching ClusterId tag
        val targetBucketTags =
            GetBucketTaggingResponse
                .builder()
                .tagSet(
                    Tag
                        .builder()
                        .key("ClusterId")
                        .value(clusterId)
                        .build(),
                ).build()

        whenever(mockS3Client.getBucketTagging(any<GetBucketTaggingRequest>()))
            .thenAnswer { invocation ->
                val request = invocation.getArgument<GetBucketTaggingRequest>(0)
                when (request.bucket()) {
                    "other-bucket-1" -> otherBucket1Tags
                    targetBucket -> targetBucketTags
                    else ->
                        throw S3Exception
                            .builder()
                            .message("The TagSet does not exist")
                            .statusCode(404)
                            .build()
                }
            }

        // Execute
        val result = aws.findS3BucketByTag("ClusterId", clusterId)

        // Verify
        assertThat(result).isEqualTo(targetBucket)
    }

    @Test
    fun `findS3BucketByTag should return null when no bucket has matching tag`() {
        val buckets =
            listOf(
                Bucket.builder().name("bucket-1").build(),
                Bucket.builder().name("bucket-2").build(),
            )
        val listBucketsResponse = ListBucketsResponse.builder().buckets(buckets).build()
        whenever(mockS3Client.listBuckets()).thenReturn(listBucketsResponse)

        // Setup mock - no bucket has tags
        whenever(mockS3Client.getBucketTagging(any<GetBucketTaggingRequest>()))
            .thenThrow(
                S3Exception
                    .builder()
                    .message("The TagSet does not exist")
                    .statusCode(404)
                    .build(),
            )

        // Execute
        val result = aws.findS3BucketByTag("ClusterId", "nonexistent-id")

        // Verify
        assertThat(result).isNull()
    }

    @Test
    fun `findS3BucketByTag should only check easy-db-lab prefixed buckets`() {
        val targetBucket = "easy-db-lab-myproject-abc12345"
        val nonPrefixedBucket = "my-other-bucket"
        val clusterId = "abc12345-full-uuid"

        // Setup mock for listBuckets - includes both prefixed and non-prefixed buckets
        val buckets =
            listOf(
                Bucket.builder().name(nonPrefixedBucket).build(),
                Bucket.builder().name(targetBucket).build(),
            )
        val listBucketsResponse = ListBucketsResponse.builder().buckets(buckets).build()
        whenever(mockS3Client.listBuckets()).thenReturn(listBucketsResponse)

        // Setup mock - target bucket has matching tag
        val targetBucketTags =
            GetBucketTaggingResponse
                .builder()
                .tagSet(
                    Tag
                        .builder()
                        .key("ClusterId")
                        .value(clusterId)
                        .build(),
                ).build()

        whenever(mockS3Client.getBucketTagging(any<GetBucketTaggingRequest>()))
            .thenReturn(targetBucketTags)

        // Execute
        val result = aws.findS3BucketByTag("ClusterId", clusterId)

        // Verify - should find the target bucket
        assertThat(result).isEqualTo(targetBucket)

        // Verify that only easy-db-lab prefixed buckets were checked
        verify(mockS3Client, org.mockito.kotlin.never()).getBucketTagging(
            org.mockito.kotlin.argThat<GetBucketTaggingRequest> { request ->
                request.bucket() == nonPrefixedBucket
            },
        )
    }

    @Test
    fun `findS3BucketByTag should return null when no buckets exist`() {
        val listBucketsResponse = ListBucketsResponse.builder().buckets(emptyList()).build()
        whenever(mockS3Client.listBuckets()).thenReturn(listBucketsResponse)

        // Execute
        val result = aws.findS3BucketByTag("ClusterId", "some-id")

        // Verify
        assertThat(result).isNull()
    }

    @Test
    fun `enableBucketRequestMetrics should call putBucketMetricsConfiguration`() {
        val bucketName = "test-bucket"

        whenever(mockS3Client.putBucketMetricsConfiguration(any<PutBucketMetricsConfigurationRequest>()))
            .thenReturn(PutBucketMetricsConfigurationResponse.builder().build())

        aws.enableBucketRequestMetrics(bucketName)

        verify(mockS3Client).putBucketMetricsConfiguration(any<PutBucketMetricsConfigurationRequest>())
    }

    @Test
    fun `enableBucketRequestMetrics should use correct configuration ID`() {
        val bucketName = "test-bucket"

        whenever(mockS3Client.putBucketMetricsConfiguration(any<PutBucketMetricsConfigurationRequest>()))
            .thenReturn(PutBucketMetricsConfigurationResponse.builder().build())

        aws.enableBucketRequestMetrics(bucketName)

        val captor = argumentCaptor<PutBucketMetricsConfigurationRequest>()
        verify(mockS3Client).putBucketMetricsConfiguration(captor.capture())

        assertThat(captor.firstValue.id()).isEqualTo(Constants.S3.METRICS_CONFIGURATION_ID)
        assertThat(captor.firstValue.bucket()).isEqualTo(bucketName)
    }

    @Test
    fun `disableBucketRequestMetrics should call deleteBucketMetricsConfiguration`() {
        val bucketName = "test-bucket"

        whenever(mockS3Client.deleteBucketMetricsConfiguration(any<DeleteBucketMetricsConfigurationRequest>()))
            .thenReturn(DeleteBucketMetricsConfigurationResponse.builder().build())

        aws.disableBucketRequestMetrics(bucketName)

        verify(mockS3Client).deleteBucketMetricsConfiguration(any<DeleteBucketMetricsConfigurationRequest>())
    }

    @Test
    fun `disableBucketRequestMetrics should handle not found gracefully`() {
        val bucketName = "test-bucket"

        whenever(mockS3Client.deleteBucketMetricsConfiguration(any<DeleteBucketMetricsConfigurationRequest>()))
            .thenThrow(
                S3Exception
                    .builder()
                    .statusCode(404)
                    .message("not found")
                    .build(),
            )

        // Should not throw
        aws.disableBucketRequestMetrics(bucketName)
    }
}
