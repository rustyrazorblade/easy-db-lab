package com.rustyrazorblade.easycasslab.services

import com.rustyrazorblade.easycasslab.BaseKoinTest
import com.rustyrazorblade.easycasslab.Constants
import com.rustyrazorblade.easycasslab.configuration.User
import com.rustyrazorblade.easycasslab.configuration.UserConfigProvider
import com.rustyrazorblade.easycasslab.output.OutputHandler
import com.rustyrazorblade.easycasslab.providers.aws.AWS
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.koin.core.component.KoinComponent
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.iam.model.IamException

/**
 * Tests for AWSResourceSetupService.
 *
 * Validates AWS resource setup orchestration including:
 * - Early return when resources already exist and valid
 * - Full setup workflow when resources missing
 * - Repair workflow when validation fails
 * - Error handling for various failure scenarios
 *
 * Note: These tests use mocks for all dependencies including AWS service
 * to isolate and verify the orchestration logic at the service layer.
 */
internal class AWSResourceSetupServiceTest :
    BaseKoinTest(),
    KoinComponent {
    // Core service under test - recreated in each test with mocked AWS
    private lateinit var service: AWSResourceSetupService

    // Mock dependencies - fully mocked AWS for service-level orchestration testing
    private val mockAws: AWS = mock()
    private val mockUserConfigProvider: UserConfigProvider = mock()
    private val mockOutputHandler: OutputHandler = mock()

    @BeforeEach
    fun setupTest() {
        // Create service with mocked dependencies
        service = AWSResourceSetupService(mockAws, mockUserConfigProvider, mockOutputHandler)
    }

    @Test
    fun `ensureAWSResources should skip setup when resources exist and valid`() {
        // Given: User config with existing S3 bucket
        val userConfig = createUserConfig(s3Bucket = "test-bucket")

        // Mock validation to return valid
        val validValidation =
            AWS.RoleValidationResult(
                isValid = true,
                instanceProfileExists = true,
                roleAttached = true,
                hasPolicies = true,
                errorMessage = "",
            )
        whenever(mockAws.validateRoleSetup(Constants.AWS.Roles.EC2_INSTANCE_ROLE))
            .thenReturn(validValidation)

        // When
        service.ensureAWSResources(userConfig)

        // Then: Should validate but not create any resources
        verify(mockAws).validateRoleSetup(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
        verify(mockAws, never()).checkPermissions()
        verify(mockAws, never()).createS3Bucket(any())
        verify(mockAws, never()).createRoleWithS3Policy(any(), any())
    }

    @Test
    fun `ensureAWSResources should create resources when S3 bucket missing`() {
        // Given: User config without S3 bucket
        val userConfig = createUserConfig(s3Bucket = "")

        // Mock successful operations
        org.mockito.kotlin
            .doNothing()
            .whenever(mockAws)
            .checkPermissions()
        whenever(mockAws.createS3Bucket(any())).thenReturn("test-bucket")
        whenever(mockAws.createRoleWithS3Policy(any(), any())).thenReturn(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
        whenever(mockAws.createServiceRole()).thenReturn(Constants.AWS.Roles.EMR_SERVICE_ROLE)
        whenever(mockAws.createEMREC2Role()).thenReturn(Constants.AWS.Roles.EMR_EC2_ROLE)
        org.mockito.kotlin
            .doNothing()
            .whenever(mockAws)
            .putS3BucketPolicy(any())

        val validValidation =
            AWS.RoleValidationResult(
                isValid = true,
                instanceProfileExists = true,
                roleAttached = true,
                hasPolicies = true,
                errorMessage = "",
            )
        whenever(mockAws.validateRoleSetup(Constants.AWS.Roles.EC2_INSTANCE_ROLE))
            .thenReturn(validValidation)

        // When
        service.ensureAWSResources(userConfig)

        // Then: Should execute full setup workflow
        verify(mockAws).checkPermissions()
        verify(mockAws).createS3Bucket(any())
        verify(mockAws).createRoleWithS3Policy(eq(Constants.AWS.Roles.EC2_INSTANCE_ROLE), any())
        verify(mockAws).createServiceRole()
        verify(mockAws).createEMREC2Role()
        verify(mockAws).putS3BucketPolicy(any())
        verify(mockAws).validateRoleSetup(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
        verify(mockUserConfigProvider).saveUserConfig(userConfig)

        // Verify S3 bucket was saved to config
        assertThat(userConfig.s3Bucket).isNotEmpty()
    }

    @Test
    fun `ensureAWSResources should repair resources when validation fails initially`() {
        // Given: User config with S3 bucket but failed validation
        val userConfig = createUserConfig(s3Bucket = "test-bucket")

        val invalidValidation =
            AWS.RoleValidationResult(
                isValid = false,
                instanceProfileExists = false,
                roleAttached = false,
                hasPolicies = true,
                errorMessage = "Instance profile does not exist",
            )
        val validValidation =
            AWS.RoleValidationResult(
                isValid = true,
                instanceProfileExists = true,
                roleAttached = true,
                hasPolicies = true,
                errorMessage = "",
            )

        // First call returns invalid, subsequent calls return valid
        whenever(mockAws.validateRoleSetup(Constants.AWS.Roles.EC2_INSTANCE_ROLE))
            .thenReturn(invalidValidation, validValidation)

        // Mock successful operations
        org.mockito.kotlin
            .doNothing()
            .whenever(mockAws)
            .checkPermissions()
        whenever(mockAws.createS3Bucket(any())).thenReturn("test-bucket")
        whenever(mockAws.createRoleWithS3Policy(any(), any())).thenReturn(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
        whenever(mockAws.createServiceRole()).thenReturn(Constants.AWS.Roles.EMR_SERVICE_ROLE)
        whenever(mockAws.createEMREC2Role()).thenReturn(Constants.AWS.Roles.EMR_EC2_ROLE)
        org.mockito.kotlin
            .doNothing()
            .whenever(mockAws)
            .putS3BucketPolicy(any())

        // When
        service.ensureAWSResources(userConfig)

        // Then: Should output repair warning and execute full setup
        verify(mockOutputHandler).handleMessage("Warning: IAM role configuration incomplete or invalid. Will attempt to repair.")
        verify(mockAws).checkPermissions()
        verify(mockAws).createRoleWithS3Policy(eq(Constants.AWS.Roles.EC2_INSTANCE_ROLE), eq("test-bucket"))
    }

    @Test
    fun `ensureAWSResources should validate credentials before setup`() {
        // Given: User config without S3 bucket
        val userConfig = createUserConfig(s3Bucket = "")

        // Mock credential validation failure
        val exception = IamException.builder().message("Access denied").build()
        whenever(mockAws.checkPermissions()).thenThrow(exception)

        // When/Then: Should throw exception from credential validation
        assertThrows<IamException> {
            service.ensureAWSResources(userConfig)
        }

        // Should call checkPermissions but not proceed to resource creation
        verify(mockAws).checkPermissions()
        verify(mockAws, never()).createS3Bucket(any())
        // Verify user-facing error message was displayed
        verify(mockOutputHandler).handleMessage(org.mockito.kotlin.argThat { contains("AWS PERMISSION ERROR") })
    }

    @Test
    fun `ensureAWSResources should create all 3 IAM roles`() {
        // Given: User config without S3 bucket
        val userConfig = createUserConfig(s3Bucket = "")

        // Mock successful operations
        org.mockito.kotlin
            .doNothing()
            .whenever(mockAws)
            .checkPermissions()
        whenever(mockAws.createS3Bucket(any())).thenReturn("test-bucket")
        whenever(mockAws.createRoleWithS3Policy(any(), any())).thenReturn(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
        whenever(mockAws.createServiceRole()).thenReturn(Constants.AWS.Roles.EMR_SERVICE_ROLE)
        whenever(mockAws.createEMREC2Role()).thenReturn(Constants.AWS.Roles.EMR_EC2_ROLE)
        org.mockito.kotlin
            .doNothing()
            .whenever(mockAws)
            .putS3BucketPolicy(any())

        val validValidation =
            AWS.RoleValidationResult(
                isValid = true,
                instanceProfileExists = true,
                roleAttached = true,
                hasPolicies = true,
                errorMessage = "",
            )
        whenever(mockAws.validateRoleSetup(Constants.AWS.Roles.EC2_INSTANCE_ROLE))
            .thenReturn(validValidation)

        // When
        service.ensureAWSResources(userConfig)

        // Then: Should create all 3 roles
        verify(mockAws).createRoleWithS3Policy(eq(Constants.AWS.Roles.EC2_INSTANCE_ROLE), any())
        verify(mockAws).createServiceRole()
        verify(mockAws).createEMREC2Role()
    }

    @Test
    fun `ensureAWSResources should apply S3 bucket policy after role creation`() {
        // Given: User config without S3 bucket
        val userConfig = createUserConfig(s3Bucket = "")

        // Capture the generated bucket name
        val bucketNameCaptor = argumentCaptor<String>()

        // Mock successful operations - let createS3Bucket return whatever is passed
        org.mockito.kotlin
            .doNothing()
            .whenever(mockAws)
            .checkPermissions()
        whenever(mockAws.createS3Bucket(bucketNameCaptor.capture())).thenAnswer { bucketNameCaptor.firstValue }
        whenever(mockAws.createRoleWithS3Policy(any(), any())).thenReturn(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
        whenever(mockAws.createServiceRole()).thenReturn(Constants.AWS.Roles.EMR_SERVICE_ROLE)
        whenever(mockAws.createEMREC2Role()).thenReturn(Constants.AWS.Roles.EMR_EC2_ROLE)
        org.mockito.kotlin
            .doNothing()
            .whenever(mockAws)
            .putS3BucketPolicy(any())

        val validValidation =
            AWS.RoleValidationResult(
                isValid = true,
                instanceProfileExists = true,
                roleAttached = true,
                hasPolicies = true,
                errorMessage = "",
            )
        whenever(mockAws.validateRoleSetup(Constants.AWS.Roles.EC2_INSTANCE_ROLE))
            .thenReturn(validValidation)

        // When
        service.ensureAWSResources(userConfig)

        // Then: Should apply S3 bucket policy with the same bucket name that was created
        val createdBucketName = bucketNameCaptor.firstValue
        assertThat(createdBucketName).startsWith("easy-cass-lab-")
        verify(mockAws).putS3BucketPolicy(createdBucketName)
    }

    @Test
    fun `ensureAWSResources should save config after successful validation`() {
        // Given: User config without S3 bucket
        val userConfig = createUserConfig(s3Bucket = "")
        val bucketName = "test-bucket"

        // Mock successful operations
        org.mockito.kotlin
            .doNothing()
            .whenever(mockAws)
            .checkPermissions()
        whenever(mockAws.createS3Bucket(any())).thenReturn(bucketName)
        whenever(mockAws.createRoleWithS3Policy(any(), any())).thenReturn(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
        whenever(mockAws.createServiceRole()).thenReturn(Constants.AWS.Roles.EMR_SERVICE_ROLE)
        whenever(mockAws.createEMREC2Role()).thenReturn(Constants.AWS.Roles.EMR_EC2_ROLE)
        org.mockito.kotlin
            .doNothing()
            .whenever(mockAws)
            .putS3BucketPolicy(any())

        val validValidation =
            AWS.RoleValidationResult(
                isValid = true,
                instanceProfileExists = true,
                roleAttached = true,
                hasPolicies = true,
                errorMessage = "",
            )
        whenever(mockAws.validateRoleSetup(Constants.AWS.Roles.EC2_INSTANCE_ROLE))
            .thenReturn(validValidation)

        // When
        service.ensureAWSResources(userConfig)

        // Then: Should save config with updated S3 bucket
        verify(mockUserConfigProvider).saveUserConfig(userConfig)
        // Bucket name is generated with UUID, verify it was set and returned
        assertThat(userConfig.s3Bucket).isNotEmpty().startsWith("easy-cass-lab-")
    }

    @Test
    fun `ensureAWSResources should throw when final validation fails`() {
        // Given: User config without S3 bucket
        val userConfig = createUserConfig(s3Bucket = "")

        // Mock successful operations but final validation fails
        org.mockito.kotlin
            .doNothing()
            .whenever(mockAws)
            .checkPermissions()
        whenever(mockAws.createS3Bucket(any())).thenReturn("test-bucket")
        whenever(mockAws.createRoleWithS3Policy(any(), any())).thenReturn(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
        whenever(mockAws.createServiceRole()).thenReturn(Constants.AWS.Roles.EMR_SERVICE_ROLE)
        whenever(mockAws.createEMREC2Role()).thenReturn(Constants.AWS.Roles.EMR_EC2_ROLE)
        org.mockito.kotlin
            .doNothing()
            .whenever(mockAws)
            .putS3BucketPolicy(any())

        val invalidValidation =
            AWS.RoleValidationResult(
                isValid = false,
                instanceProfileExists = true,
                roleAttached = false,
                hasPolicies = true,
                errorMessage = "Role not attached to instance profile",
            )
        whenever(mockAws.validateRoleSetup(Constants.AWS.Roles.EC2_INSTANCE_ROLE))
            .thenReturn(invalidValidation)

        // When/Then: Should throw IllegalStateException
        val exception =
            assertThrows<IllegalStateException> {
                service.ensureAWSResources(userConfig)
            }

        assertThat(exception.message).contains("AWS resource setup completed but final validation failed")
        assertThat(exception.message).contains("Role not attached to instance profile")

        // Should not save config on validation failure
        verify(mockUserConfigProvider, never()).saveUserConfig(any())
    }

    @Test
    fun `ensureAWSResources should handle IAM permission errors`() {
        // Given: User config without S3 bucket
        val userConfig = createUserConfig(s3Bucket = "")

        // Mock successful credential check but IAM permission error during role creation
        org.mockito.kotlin
            .doNothing()
            .whenever(mockAws)
            .checkPermissions()
        whenever(mockAws.createS3Bucket(any())).thenReturn("test-bucket")

        val iamException =
            IamException
                .builder()
                .message("User is not authorized to perform: iam:CreateRole")
                .build()
        whenever(mockAws.createRoleWithS3Policy(any(), any())).thenThrow(iamException)

        // When/Then: Should throw IamException
        assertThrows<IamException> {
            service.ensureAWSResources(userConfig)
        }

        // Should handle error with appropriate message
        verify(mockOutputHandler).handleError(any<String>(), any())
    }

    @Test
    fun `ensureAWSResources should handle S3 bucket creation failure`() {
        // Given: User config without S3 bucket
        val userConfig = createUserConfig(s3Bucket = "")

        // Mock successful credential check but S3 creation failure
        org.mockito.kotlin
            .doNothing()
            .whenever(mockAws)
            .checkPermissions()

        val s3Exception =
            software.amazon.awssdk.services.s3.model.S3Exception
                .builder()
                .message("Access denied")
                .build()
        whenever(mockAws.createS3Bucket(any())).thenThrow(s3Exception)

        // When/Then: Should throw exception
        assertThrows<software.amazon.awssdk.services.s3.model.S3Exception> {
            service.ensureAWSResources(userConfig)
        }

        // Should not proceed to role creation
        verify(mockAws, never()).createRoleWithS3Policy(any(), any())
    }

    // Helper method to create test user config
    private fun createUserConfig(s3Bucket: String = ""): User =
        User(
            email = "test@example.com",
            region = "us-west-2",
            keyName = "test-key",
            sshKeyPath = "/tmp/test.pem",
            awsProfile = "default",
            awsAccessKey = "",
            awsSecret = "",
            s3Bucket = s3Bucket,
        )
}
