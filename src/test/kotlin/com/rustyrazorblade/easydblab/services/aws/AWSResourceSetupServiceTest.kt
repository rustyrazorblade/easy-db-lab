package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.AWS
import com.rustyrazorblade.easydblab.services.aws.AWSResourceSetupService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.koin.core.component.KoinComponent
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.iam.model.IamException

/**
 * Tests for AWSResourceSetupService.
 *
 * Validates AWS IAM resource setup orchestration including:
 * - Early return when IAM resources already exist and valid
 * - Full IAM setup workflow when resources missing
 * - Repair workflow when validation fails
 * - Error handling for various failure scenarios
 *
 * Note: S3 bucket creation is now handled per-environment in the Up command,
 * not in this service. This service only handles IAM role setup with wildcard S3 policy.
 */
internal class AWSResourceSetupServiceTest :
    BaseKoinTest(),
    KoinComponent {
    // Core service under test - recreated in each test with mocked AWS
    private lateinit var service: AWSResourceSetupService

    // Mock dependencies - fully mocked AWS for service-level orchestration testing
    private val mockAws: AWS = mock()
    private val mockOutputHandler: OutputHandler = mock()

    @BeforeEach
    fun setupTest() {
        // Create service with mocked dependencies (no more userConfigProvider)
        service = AWSResourceSetupService(mockAws, mockOutputHandler)
    }

    @Test
    fun `ensureAWSResources should skip setup when IAM resources exist and valid`() {
        // Given: IAM resources already configured
        val userConfig = createUserConfig()

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
        verify(mockAws, never()).createRoleWithS3Policy(any())
    }

    @Test
    fun `ensureAWSResources should create IAM resources when validation fails`() {
        // Given: IAM resources not configured (validation fails initially)
        val userConfig = createUserConfig()

        val invalidValidation =
            AWS.RoleValidationResult(
                isValid = false,
                instanceProfileExists = false,
                roleAttached = false,
                hasPolicies = false,
                errorMessage = "Resources not found",
            )
        val validValidation =
            AWS.RoleValidationResult(
                isValid = true,
                instanceProfileExists = true,
                roleAttached = true,
                hasPolicies = true,
                errorMessage = "",
            )

        // First call returns invalid, second call (after creation) returns valid
        whenever(mockAws.validateRoleSetup(Constants.AWS.Roles.EC2_INSTANCE_ROLE))
            .thenReturn(invalidValidation, validValidation)

        // Mock successful operations
        org.mockito.kotlin
            .doNothing()
            .whenever(mockAws)
            .checkPermissions()
        whenever(mockAws.createRoleWithS3Policy(any())).thenReturn(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
        whenever(mockAws.createServiceRole()).thenReturn(Constants.AWS.Roles.EMR_SERVICE_ROLE)
        whenever(mockAws.createEMREC2Role()).thenReturn(Constants.AWS.Roles.EMR_EC2_ROLE)

        // When
        service.ensureAWSResources(userConfig)

        // Then: Should execute full IAM setup workflow
        verify(mockAws).checkPermissions()
        verify(mockAws).createRoleWithS3Policy(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
        verify(mockAws).createServiceRole()
        verify(mockAws).createEMREC2Role()
        // Should NOT create S3 bucket or apply bucket policy (that's done in Up command now)
        verify(mockAws, never()).createS3Bucket(any())
        verify(mockAws, never()).putS3BucketPolicy(any())
    }

    @Test
    fun `ensureAWSResources should output repair warning when validation initially fails`() {
        // Given: IAM resources partially configured
        val userConfig = createUserConfig()

        val invalidValidation =
            AWS.RoleValidationResult(
                isValid = false,
                instanceProfileExists = true,
                roleAttached = false,
                hasPolicies = true,
                errorMessage = "Role not attached to instance profile",
            )
        val validValidation =
            AWS.RoleValidationResult(
                isValid = true,
                instanceProfileExists = true,
                roleAttached = true,
                hasPolicies = true,
                errorMessage = "",
            )

        // First call returns invalid, second returns valid
        whenever(mockAws.validateRoleSetup(Constants.AWS.Roles.EC2_INSTANCE_ROLE))
            .thenReturn(invalidValidation, validValidation)

        // Mock successful operations
        org.mockito.kotlin
            .doNothing()
            .whenever(mockAws)
            .checkPermissions()
        whenever(mockAws.createRoleWithS3Policy(any())).thenReturn(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
        whenever(mockAws.createServiceRole()).thenReturn(Constants.AWS.Roles.EMR_SERVICE_ROLE)
        whenever(mockAws.createEMREC2Role()).thenReturn(Constants.AWS.Roles.EMR_EC2_ROLE)

        // When
        service.ensureAWSResources(userConfig)

        // Then: Should output repair warning
        verify(mockOutputHandler).handleMessage("Warning: IAM role configuration incomplete or invalid. Will attempt to repair.")
    }

    @Test
    fun `ensureAWSResources should validate credentials before setup`() {
        // Given: IAM resources not configured
        val userConfig = createUserConfig()

        val invalidValidation =
            AWS.RoleValidationResult(
                isValid = false,
                instanceProfileExists = false,
                roleAttached = false,
                hasPolicies = false,
                errorMessage = "Resources not found",
            )
        whenever(mockAws.validateRoleSetup(Constants.AWS.Roles.EC2_INSTANCE_ROLE))
            .thenReturn(invalidValidation)

        // Mock credential validation failure
        val exception = IamException.builder().message("Access denied").build()
        whenever(mockAws.checkPermissions()).thenThrow(exception)

        // When/Then: Should throw exception from credential validation
        assertThrows<IamException> {
            service.ensureAWSResources(userConfig)
        }

        // Should call checkPermissions but not proceed to resource creation
        verify(mockAws).checkPermissions()
        verify(mockAws, never()).createRoleWithS3Policy(any())
        // Verify user-facing error message was displayed
        verify(mockOutputHandler).handleMessage(org.mockito.kotlin.argThat { contains("AWS PERMISSION ERROR") })
    }

    @Test
    fun `ensureAWSResources should create all 3 IAM roles`() {
        // Given: IAM resources not configured
        val userConfig = createUserConfig()

        val invalidValidation =
            AWS.RoleValidationResult(
                isValid = false,
                instanceProfileExists = false,
                roleAttached = false,
                hasPolicies = false,
                errorMessage = "Resources not found",
            )
        val validValidation =
            AWS.RoleValidationResult(
                isValid = true,
                instanceProfileExists = true,
                roleAttached = true,
                hasPolicies = true,
                errorMessage = "",
            )
        whenever(mockAws.validateRoleSetup(Constants.AWS.Roles.EC2_INSTANCE_ROLE))
            .thenReturn(invalidValidation, validValidation)

        // Mock successful operations
        org.mockito.kotlin
            .doNothing()
            .whenever(mockAws)
            .checkPermissions()
        whenever(mockAws.createRoleWithS3Policy(any())).thenReturn(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
        whenever(mockAws.createServiceRole()).thenReturn(Constants.AWS.Roles.EMR_SERVICE_ROLE)
        whenever(mockAws.createEMREC2Role()).thenReturn(Constants.AWS.Roles.EMR_EC2_ROLE)

        // When
        service.ensureAWSResources(userConfig)

        // Then: Should create all 3 roles
        verify(mockAws).createRoleWithS3Policy(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
        verify(mockAws).createServiceRole()
        verify(mockAws).createEMREC2Role()
    }

    @Test
    fun `ensureAWSResources should throw when final validation fails`() {
        // Given: IAM resources not configured and validation keeps failing
        val userConfig = createUserConfig()

        val invalidValidation =
            AWS.RoleValidationResult(
                isValid = false,
                instanceProfileExists = true,
                roleAttached = false,
                hasPolicies = true,
                errorMessage = "Role not attached to instance profile",
            )

        // Both calls return invalid
        whenever(mockAws.validateRoleSetup(Constants.AWS.Roles.EC2_INSTANCE_ROLE))
            .thenReturn(invalidValidation)

        // Mock successful operations
        org.mockito.kotlin
            .doNothing()
            .whenever(mockAws)
            .checkPermissions()
        whenever(mockAws.createRoleWithS3Policy(any())).thenReturn(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
        whenever(mockAws.createServiceRole()).thenReturn(Constants.AWS.Roles.EMR_SERVICE_ROLE)
        whenever(mockAws.createEMREC2Role()).thenReturn(Constants.AWS.Roles.EMR_EC2_ROLE)

        // When/Then: Should throw IllegalStateException
        val exception =
            assertThrows<IllegalStateException> {
                service.ensureAWSResources(userConfig)
            }

        assertThat(exception.message).contains("AWS resource setup completed but final validation failed")
        assertThat(exception.message).contains("Role not attached to instance profile")
    }

    @Test
    fun `ensureAWSResources should handle IAM permission errors`() {
        // Given: IAM resources not configured
        val userConfig = createUserConfig()

        val invalidValidation =
            AWS.RoleValidationResult(
                isValid = false,
                instanceProfileExists = false,
                roleAttached = false,
                hasPolicies = false,
                errorMessage = "Resources not found",
            )
        whenever(mockAws.validateRoleSetup(Constants.AWS.Roles.EC2_INSTANCE_ROLE))
            .thenReturn(invalidValidation)

        // Mock successful credential check but IAM permission error during role creation
        org.mockito.kotlin
            .doNothing()
            .whenever(mockAws)
            .checkPermissions()

        val iamException =
            IamException
                .builder()
                .message("User is not authorized to perform: iam:CreateRole")
                .build()
        whenever(mockAws.createRoleWithS3Policy(any())).thenThrow(iamException)

        // When/Then: Should throw IamException
        assertThrows<IamException> {
            service.ensureAWSResources(userConfig)
        }

        // Should handle error with appropriate message
        verify(mockOutputHandler).handleError(any<String>(), any())
    }

    // Helper method to create test user config
    private fun createUserConfig(): User =
        User(
            email = "test@example.com",
            region = "us-west-2",
            keyName = "test-key",
            awsProfile = "default",
            awsAccessKey = "",
            awsSecret = "",
        )
}
