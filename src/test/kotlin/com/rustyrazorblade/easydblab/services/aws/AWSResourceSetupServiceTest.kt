package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.aws.AWS
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
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.iam.model.EntityAlreadyExistsException
import software.amazon.awssdk.services.iam.model.GetInstanceProfileRequest
import software.amazon.awssdk.services.iam.model.GetInstanceProfileResponse
import software.amazon.awssdk.services.iam.model.IamException
import software.amazon.awssdk.services.iam.model.InstanceProfile
import software.amazon.awssdk.services.iam.model.ListRolePoliciesRequest
import software.amazon.awssdk.services.iam.model.ListRolePoliciesResponse
import software.amazon.awssdk.services.iam.model.NoSuchEntityException
import software.amazon.awssdk.services.iam.model.Role
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse

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
    // Core service under test - recreated in each test with real AWS + mocked clients
    private lateinit var service: AWSResourceSetupService

    // Mock AWS SDK clients - extension functions need real AWS with mocked clients
    private val mockIamClient: IamClient = mock()
    private val mockS3Client: S3Client = mock()
    private val mockStsClient: StsClient = mock()
    private lateinit var aws: AWS

    private lateinit var eventBus: EventBus
    private val capturedEvents = mutableListOf<EventEnvelope>()

    @BeforeEach
    fun setupTest() {
        capturedEvents.clear()
        eventBus = EventBus()
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    capturedEvents.add(envelope)
                }

                override fun close() = Unit
            },
        )

        // Use real AWS with mocked clients so extension functions work correctly
        aws = AWS(mockIamClient, mockS3Client, mockStsClient)

        // Mock STS for checkPermissions
        whenever(mockStsClient.getCallerIdentity(any<software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest>()))
            .thenReturn(
                GetCallerIdentityResponse
                    .builder()
                    .account("123456789012")
                    .arn("arn:aws:iam::123456789012:user/test")
                    .userId("TESTID")
                    .build(),
            )

        service =
            AWSResourceSetupService(
                aws,
                eventBus,
            )
    }

    /**
     * Configures mockIamClient to make validateRoleSetup return a valid result.
     * The extension function calls getInstanceProfile and listRolePolicies.
     */
    private fun stubValidRoleSetup(roleName: String) {
        val role = Role.builder().roleName(roleName).build()
        val instanceProfile =
            InstanceProfile
                .builder()
                .instanceProfileName(roleName)
                .roles(role)
                .build()
        val profileResponse =
            GetInstanceProfileResponse
                .builder()
                .instanceProfile(instanceProfile)
                .build()

        whenever(mockIamClient.getInstanceProfile(any<GetInstanceProfileRequest>()))
            .thenReturn(profileResponse)

        whenever(mockIamClient.listRolePolicies(any<ListRolePoliciesRequest>()))
            .thenReturn(
                ListRolePoliciesResponse
                    .builder()
                    .policyNames("S3Access")
                    .build(),
            )
    }

    /**
     * Configures mockIamClient to make validateRoleSetup return invalid (no instance profile).
     */
    private fun stubInvalidRoleSetup() {
        whenever(mockIamClient.getInstanceProfile(any<GetInstanceProfileRequest>()))
            .thenThrow(NoSuchEntityException.builder().message("Not found").build())
    }

    /**
     * Configures mockIamClient to make validateRoleSetup return invalid first, then valid.
     * Uses a counter to alternate behavior.
     */
    private fun stubInvalidThenValidRoleSetup(roleName: String) {
        val role = Role.builder().roleName(roleName).build()
        val instanceProfile =
            InstanceProfile
                .builder()
                .instanceProfileName(roleName)
                .roles(role)
                .build()
        val profileResponse =
            GetInstanceProfileResponse
                .builder()
                .instanceProfile(instanceProfile)
                .build()

        // First call throws (invalid), subsequent calls return valid
        whenever(mockIamClient.getInstanceProfile(any<GetInstanceProfileRequest>()))
            .thenThrow(NoSuchEntityException.builder().message("Not found").build())
            .thenReturn(profileResponse)

        whenever(mockIamClient.listRolePolicies(any<ListRolePoliciesRequest>()))
            .thenReturn(
                ListRolePoliciesResponse
                    .builder()
                    .policyNames("S3Access")
                    .build(),
            )
    }

    /**
     * Stubs IAM client to allow role/instance-profile creation (idempotent - already exists is OK).
     */
    private fun stubSuccessfulRoleCreation() {
        // createRole - already exists is fine
        whenever(mockIamClient.createRole(any<software.amazon.awssdk.services.iam.model.CreateRoleRequest>()))
            .thenThrow(EntityAlreadyExistsException.builder().message("Already exists").build())

        // attachRolePolicy
        whenever(mockIamClient.attachRolePolicy(any<software.amazon.awssdk.services.iam.model.AttachRolePolicyRequest>()))
            .thenReturn(
                software.amazon.awssdk.services.iam.model.AttachRolePolicyResponse
                    .builder()
                    .build(),
            )

        // putRolePolicy
        whenever(mockIamClient.putRolePolicy(any<software.amazon.awssdk.services.iam.model.PutRolePolicyRequest>()))
            .thenReturn(
                software.amazon.awssdk.services.iam.model.PutRolePolicyResponse
                    .builder()
                    .build(),
            )

        // createInstanceProfile - already exists
        whenever(mockIamClient.createInstanceProfile(any<software.amazon.awssdk.services.iam.model.CreateInstanceProfileRequest>()))
            .thenThrow(EntityAlreadyExistsException.builder().message("Already exists").build())

        // addRoleToInstanceProfile - limit exceeded means already attached
        whenever(mockIamClient.addRoleToInstanceProfile(any<software.amazon.awssdk.services.iam.model.AddRoleToInstanceProfileRequest>()))
            .thenThrow(
                software.amazon.awssdk.services.iam.model.LimitExceededException
                    .builder()
                    .message("Already attached")
                    .build(),
            )

        // createServiceLinkedRole - already exists
        whenever(mockIamClient.createServiceLinkedRole(any<software.amazon.awssdk.services.iam.model.CreateServiceLinkedRoleRequest>()))
            .thenThrow(
                software.amazon.awssdk.services.iam.model.InvalidInputException
                    .builder()
                    .message("has been taken")
                    .build(),
            )
    }

    @Test
    fun `ensureAWSResources should skip setup when IAM resources exist and valid`() {
        // Given: IAM resources already configured
        val userConfig = createUserConfig()
        stubValidRoleSetup(Constants.AWS.Roles.EC2_INSTANCE_ROLE)

        // When
        service.ensureAWSResources(userConfig)

        // Then: Should validate but not create any resources
        verify(mockIamClient).getInstanceProfile(any<GetInstanceProfileRequest>())
        verify(mockStsClient, never()).getCallerIdentity(any<software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest>())
    }

    @Test
    fun `ensureAWSResources should create IAM resources when validation fails`() {
        // Given: IAM resources not configured (validation fails initially, then succeeds)
        val userConfig = createUserConfig()
        stubInvalidThenValidRoleSetup(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
        stubSuccessfulRoleCreation()

        // When
        service.ensureAWSResources(userConfig)

        // Then: Should execute full IAM setup workflow
        verify(mockStsClient).getCallerIdentity(any<software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest>())
        // Should NOT create S3 bucket or apply bucket policy (that's done in Up command now)
        verify(mockS3Client, never()).createBucket(any<software.amazon.awssdk.services.s3.model.CreateBucketRequest>())
    }

    @Test
    fun `ensureAWSResources should output repair warning when validation initially fails`() {
        // Given: IAM resources partially configured (fails initially, then succeeds)
        val userConfig = createUserConfig()
        stubInvalidThenValidRoleSetup(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
        stubSuccessfulRoleCreation()

        // When
        service.ensureAWSResources(userConfig)

        // Then: Should emit repair warning event
        assertThat(capturedEvents).anyMatch { it.event is Event.AwsSetup.RepairWarning }
    }

    @Test
    fun `ensureAWSResources should validate credentials before setup`() {
        // Given: IAM resources not configured
        val userConfig = createUserConfig()
        stubInvalidRoleSetup()

        // Mock credential validation failure (STS throws)
        val exception = IamException.builder().message("Access denied").build()
        whenever(mockStsClient.getCallerIdentity(any<software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest>()))
            .thenThrow(exception)

        // When/Then: Should throw exception from credential validation
        assertThrows<IamException> {
            service.ensureAWSResources(userConfig)
        }

        // Verify permission error event was emitted (IamException is an SdkServiceException)
        assertThat(capturedEvents).anyMatch { it.event is Event.AwsSetup.IamPermissionError }
    }

    @Test
    fun `ensureAWSResources should create all 3 IAM roles`() {
        // Given: IAM resources not configured (fails initially, then succeeds)
        val userConfig = createUserConfig()
        stubInvalidThenValidRoleSetup(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
        stubSuccessfulRoleCreation()

        // When
        service.ensureAWSResources(userConfig)

        // Then: Should have attempted to create roles via IAM client
        // createRole is called for EC2 instance role, EMR service role, and EMR EC2 role
        verify(mockIamClient, org.mockito.kotlin.atLeast(3))
            .createRole(any<software.amazon.awssdk.services.iam.model.CreateRoleRequest>())
    }

    @Test
    fun `ensureAWSResources should throw when final validation fails`() {
        // Given: validation fails initially, createRoleWithS3Policy's internal validation passes,
        // but the final validation in finalizeSetup fails (e.g., no policies)
        val userConfig = createUserConfig()

        val role = Role.builder().roleName(Constants.AWS.Roles.EC2_INSTANCE_ROLE).build()
        val validProfile =
            GetInstanceProfileResponse
                .builder()
                .instanceProfile(
                    InstanceProfile
                        .builder()
                        .instanceProfileName(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
                        .roles(role)
                        .build(),
                ).build()

        // 1st call: initial validation -> not found
        // 2nd call: createRoleWithS3Policy internal validation -> valid
        // 3rd call: finalizeSetup validation -> valid (profile exists, role attached)
        whenever(mockIamClient.getInstanceProfile(any<GetInstanceProfileRequest>()))
            .thenThrow(NoSuchEntityException.builder().message("Not found").build())
            .thenReturn(validProfile)
            .thenReturn(validProfile)

        // listRolePolicies: 1st call (from createRoleWithS3Policy validation) returns policies,
        // 2nd call (from finalizeSetup validation) returns empty (simulates eventual consistency issue)
        whenever(mockIamClient.listRolePolicies(any<ListRolePoliciesRequest>()))
            .thenReturn(ListRolePoliciesResponse.builder().policyNames("S3Access").build())
            .thenReturn(ListRolePoliciesResponse.builder().build())

        stubSuccessfulRoleCreation()

        // When/Then: Should throw IllegalStateException from finalizeSetup
        val exception =
            assertThrows<IllegalStateException> {
                service.ensureAWSResources(userConfig)
            }

        assertThat(exception.message).contains("AWS resource setup completed but final validation failed")
    }

    @Test
    fun `ensureAWSResources should handle IAM permission errors`() {
        // Given: IAM resources not configured
        val userConfig = createUserConfig()
        stubInvalidRoleSetup()

        // Mock IAM permission error during role creation
        val iamException =
            IamException
                .builder()
                .message("User is not authorized to perform: iam:CreateRole")
                .build()
        whenever(mockIamClient.createRole(any<software.amazon.awssdk.services.iam.model.CreateRoleRequest>()))
            .thenThrow(iamException)

        // When/Then: Should throw IamException
        assertThrows<IamException> {
            service.ensureAWSResources(userConfig)
        }

        // Should emit IAM permission error event
        assertThat(capturedEvents).anyMatch { it.event is Event.AwsSetup.IamPermissionError }
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
