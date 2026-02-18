package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Prompter
import com.rustyrazorblade.easydblab.TestPrompter
import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.AWS
import com.rustyrazorblade.easydblab.providers.aws.AWSClientFactory
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.aws.AMIValidationException
import com.rustyrazorblade.easydblab.services.aws.AMIValidator
import com.rustyrazorblade.easydblab.services.aws.AWSResourceSetupService
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SetupProfileTest : BaseKoinTest() {
    private lateinit var mockUserConfigProvider: UserConfigProvider
    private lateinit var mockAwsResourceSetup: AWSResourceSetupService
    private lateinit var mockAwsInfra: AwsInfrastructureService
    private lateinit var mockAmiValidator: AMIValidator
    private lateinit var mockCommandExecutor: CommandExecutor
    private lateinit var mockAws: AWS
    private lateinit var mockAwsClientFactory: AWSClientFactory
    private lateinit var testPrompter: TestPrompter
    private lateinit var bufferedOutput: BufferedOutputHandler

    @BeforeEach
    fun setupMocks() {
        mockUserConfigProvider = mock()
        mockAwsResourceSetup = mock()
        mockAwsInfra = mock()
        mockAmiValidator = mock()
        mockCommandExecutor = mock()
        mockAws = mock()
        mockAwsClientFactory = mock()
        testPrompter = TestPrompter()
        bufferedOutput = BufferedOutputHandler()

        // Default: factory returns our mock AWS (both credential types)
        whenever(mockAwsClientFactory.createAWSClient(any(), any(), any())).thenReturn(mockAws)
        whenever(mockAwsClientFactory.createAWSClientWithProfile(any(), any())).thenReturn(mockAws)

        // Default: AWS operations succeed
        doNothing().whenever(mockAws).checkPermissions()
        whenever(mockAws.getAccountId()).thenReturn("123456789012")
        whenever(mockAws.createS3Bucket(any())).thenReturn("test-bucket")
        doNothing().whenever(mockAws).putS3BucketPolicy(any())
        doNothing().whenever(mockAws).tagS3Bucket(any(), any())
    }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<UserConfigProvider> { mockUserConfigProvider }
                single<AWSResourceSetupService> { mockAwsResourceSetup }
                single<AwsInfrastructureService> { mockAwsInfra }
                single<AMIValidator> { mockAmiValidator }
                single<CommandExecutor> { mockCommandExecutor }
                single<AWS> { mockAws }
                single<AWSClientFactory> { mockAwsClientFactory }
                single<Prompter> { testPrompter }
                single<OutputHandler> { bufferedOutput }
            },
        )

    @Nested
    inner class WhenProfileAlreadySetUp {
        @Test
        fun `execute prompts for each optional field when profile exists`() {
            // Given - all required fields already exist with static credentials

            val existingConfig =
                mapOf(
                    "email" to "test@example.com",
                    "region" to "us-west-2",
                    "awsAccessKey" to "AKIATEST",
                    "awsSecret" to "secret123",
                    "axonOpsOrg" to "myorg",
                )
            whenever(mockUserConfigProvider.loadExistingConfig()).thenReturn(existingConfig)

            // Mock getUserConfig to return a User object
            val userConfig =
                com.rustyrazorblade.easydblab.configuration.User(
                    email = "test@example.com",
                    region = "us-west-2",
                    keyName = "test-key",
                    awsProfile = "",
                    awsAccessKey = "AKIATEST",
                    awsSecret = "secret123",
                )
            whenever(mockUserConfigProvider.getUserConfig()).thenReturn(userConfig)

            // User presses Enter for all fields (keeps existing values)
            testPrompter = TestPrompter()
            setupTestModule()

            // When
            val command = SetupProfile()
            command.execute()

            // Then - should prompt for each optional field individually
            // Note: Tailscale device tag is hardcoded to "tag:easy-db-lab" and not prompted
            assertThat(testPrompter.wasPromptedFor("AxonOps Org?")).isTrue()
            assertThat(testPrompter.wasPromptedFor("AxonOps Key?")).isTrue()
            assertThat(testPrompter.wasPromptedFor("Tailscale OAuth Client ID?")).isTrue()
            assertThat(testPrompter.wasPromptedFor("Tailscale OAuth Client Secret?")).isTrue()

            // Should display masked existing value for axonOpsOrg
            assertThat(testPrompter.getCallLog().any { it.question.contains("[m****]") }).isTrue()

            // Should display profile configured message
            val output = bufferedOutput.messages.joinToString("\n")
            assertThat(output).contains("Profile 'default' is already configured")
            assertThat(output).contains("Configuration updated!")

            // Should not call any AWS operations
            verify(mockAwsResourceSetup, never()).ensureAWSResources(any())
        }

        @Test
        fun `execute shows masked values in prompts`() {
            // Given - profile exists with existing optional values
            val existingConfig =
                mapOf(
                    "email" to "test@example.com",
                    "region" to "us-west-2",
                    "awsProfile" to "my-profile",
                    "axonOpsOrg" to "acme-corp",
                    "axonOpsKey" to "secret-key-123",
                    "tailscaleClientId" to "tskey-client-abc",
                    "tailscaleClientSecret" to "tskey-secret-xyz",
                    "tailscaleTag" to "tag:my-lab",
                )
            whenever(mockUserConfigProvider.loadExistingConfig()).thenReturn(existingConfig)

            val userConfig =
                com.rustyrazorblade.easydblab.configuration.User(
                    email = "test@example.com",
                    region = "us-west-2",
                    keyName = "test-key",
                    awsProfile = "my-profile",
                    awsAccessKey = "",
                    awsSecret = "",
                )
            whenever(mockUserConfigProvider.getUserConfig()).thenReturn(userConfig)

            testPrompter = TestPrompter()
            setupTestModule()

            // When
            val command = SetupProfile()
            command.execute()

            // Then - prompts should show masked values like [a****]
            // Note: Tailscale device tag is hardcoded and not prompted
            val callLog = testPrompter.getCallLog()
            assertThat(callLog.any { it.question.contains("AxonOps Org?") && it.question.contains("[a****]") }).isTrue()
            assertThat(callLog.any { it.question.contains("AxonOps Key?") && it.question.contains("[s****]") }).isTrue()
            assertThat(callLog.any { it.question.contains("Tailscale OAuth Client ID?") && it.question.contains("[t****]") }).isTrue()
        }

        @Test
        fun `execute shows not set for empty values`() {
            // Given - profile exists but no optional values configured
            val existingConfig =
                mapOf(
                    "email" to "test@example.com",
                    "region" to "us-west-2",
                    "awsAccessKey" to "AKIATEST",
                    "awsSecret" to "secret123",
                )
            whenever(mockUserConfigProvider.loadExistingConfig()).thenReturn(existingConfig)

            val userConfig =
                com.rustyrazorblade.easydblab.configuration.User(
                    email = "test@example.com",
                    region = "us-west-2",
                    keyName = "test-key",
                    awsProfile = "",
                    awsAccessKey = "AKIATEST",
                    awsSecret = "secret123",
                )
            whenever(mockUserConfigProvider.getUserConfig()).thenReturn(userConfig)

            testPrompter = TestPrompter()
            setupTestModule()

            // When
            val command = SetupProfile()
            command.execute()

            // Then - prompts should show "(not set)" for empty values
            val callLog = testPrompter.getCallLog()
            assertThat(callLog.any { it.question.contains("AxonOps Org?") && it.question.contains("(not set)") }).isTrue()
        }

        @Test
        fun `execute updates value when user provides new input`() {
            // Given - profile exists with existing values
            val existingConfig =
                mapOf(
                    "email" to "test@example.com",
                    "region" to "us-west-2",
                    "awsAccessKey" to "AKIATEST",
                    "awsSecret" to "secret123",
                    "axonOpsOrg" to "old-org",
                )
            whenever(mockUserConfigProvider.loadExistingConfig()).thenReturn(existingConfig)

            val userConfig =
                com.rustyrazorblade.easydblab.configuration.User(
                    email = "test@example.com",
                    region = "us-west-2",
                    keyName = "test-key",
                    awsProfile = "",
                    awsAccessKey = "AKIATEST",
                    awsSecret = "secret123",
                )
            whenever(mockUserConfigProvider.getUserConfig()).thenReturn(userConfig)

            // User provides new value for AxonOps Org
            testPrompter = TestPrompter(mapOf("AxonOps Org?" to "new-org"))
            setupTestModule()

            // When
            val command = SetupProfile()
            command.execute()

            // Then - userConfig should be updated and saved
            assertThat(userConfig.axonOpsOrg).isEqualTo("new-org")
            verify(mockUserConfigProvider).saveUserConfig(userConfig)
        }
    }

    @Nested
    inner class WhenMissingRequiredFields {
        @BeforeEach
        fun setupEmptyConfig() {
            // Include keyName and s3Bucket to skip AWS resource creation that uses static methods
            whenever(mockUserConfigProvider.loadExistingConfig()).thenReturn(
                mapOf(
                    "keyName" to "test-key",
                    "s3Bucket" to "test-bucket",
                ),
            )
        }

        @Test
        fun `execute prompts for all required fields and sets up AWS resources with static credentials`() {
            // Given - prompter returns valid values, empty profile means use static credentials
            testPrompter =
                TestPrompter(
                    mapOf(
                        "email" to "user@test.com",
                        "region" to "us-west-2",
                        "AWS Profile" to "", // Empty = use manual credentials
                        "AWS Access Key" to "AKIATEST123",
                        "AWS Secret" to "secretkey123",
                        "IAM policies" to "N",
                    ),
                )
            setupTestModule()

            // When
            val command = SetupProfile()
            command.execute()

            // Then - should prompt for required fields
            assertThat(testPrompter.wasPromptedFor("email")).isTrue()
            assertThat(testPrompter.wasPromptedFor("region")).isTrue()
            assertThat(testPrompter.wasPromptedFor("AWS Profile")).isTrue()
            assertThat(testPrompter.wasPromptedFor("AWS Access Key")).isTrue()
            assertThat(testPrompter.wasPromptedFor("AWS Secret")).isTrue()

            // Should call AWS validation using static credentials
            verify(mockAwsClientFactory).createAWSClient(any(), any(), any())
            verify(mockAws).checkPermissions()

            // Should setup IAM resources
            verify(mockAwsResourceSetup).ensureAWSResources(any())

            // Should create Packer infrastructure
            verify(mockAwsInfra).ensurePackerInfrastructure(any())
        }

        @Test
        fun `execute uses profile-based auth when profile is provided`() {
            // Given - user provides an AWS profile name
            testPrompter =
                TestPrompter(
                    mapOf(
                        "email" to "user@test.com",
                        "region" to "us-west-2",
                        "AWS Profile" to "my-profile",
                        "IAM policies" to "N",
                    ),
                )
            setupTestModule()

            // When
            val command = SetupProfile()
            command.execute()

            // Then - should NOT prompt for access key/secret
            assertThat(testPrompter.wasPromptedFor("AWS Access Key")).isFalse()
            assertThat(testPrompter.wasPromptedFor("AWS Secret")).isFalse()

            // Should call AWS validation using profile-based auth
            verify(mockAwsClientFactory).createAWSClientWithProfile(any(), any())
            verify(mockAws).checkPermissions()

            // Should setup IAM resources
            verify(mockAwsResourceSetup).ensureAWSResources(any())
        }

        @Test
        fun `execute retries with new credentials when validation fails then succeeds`() {
            // Given - first attempt fails, second succeeds
            var callCount = 0
            whenever(mockAws.checkPermissions()).thenAnswer {
                callCount++
                if (callCount == 1) {
                    throw RuntimeException("Invalid credentials")
                }
                // Second call succeeds (no exception)
            }

            testPrompter =
                TestPrompter(
                    mapOf(
                        "email" to "user@test.com",
                        "region" to "us-west-2",
                        "IAM policies" to "N",
                    ),
                )
            // First attempt: empty profile, then on retry use a valid profile
            testPrompter.addSequentialResponses("AWS Profile", "", "my-valid-profile")
            setupTestModule()

            // When
            val command = SetupProfile()
            command.execute()

            // Then - should have retried and succeeded
            val output = bufferedOutput.messages.joinToString("\n")
            assertThat(output).contains("Credential validation failed")
            assertThat(output).contains("AWS credentials validated successfully")
            assertThat(output).contains("Account setup complete!")

            // Should have called checkPermissions twice
            verify(mockAws, org.mockito.kotlin.times(2)).checkPermissions()
        }

        @Test
        fun `execute displays IAM policies when user requests them`() {
            // Given - user says yes to seeing IAM policies
            testPrompter =
                TestPrompter(
                    mapOf(
                        "email" to "user@test.com",
                        "region" to "us-west-2",
                        "AWS Profile" to "",
                        "AWS Access Key" to "AKIATEST123",
                        "AWS Secret" to "secretkey123",
                        "IAM policies" to "y",
                    ),
                )
            setupTestModule()

            // When
            val command = SetupProfile()
            command.execute()

            // Then - should display IAM policies
            val output = bufferedOutput.messages.joinToString("\n")
            assertThat(output).contains("AWS IAM PERMISSIONS REQUIRED")
            assertThat(output).contains("Managed Policies")
        }

        @Test
        fun `execute throws SetupProfileException after max retries when credentials are invalid`() {
            // Given - AWS checkPermissions always throws exception
            doThrow(RuntimeException("Invalid credentials")).whenever(mockAws).checkPermissions()

            testPrompter =
                TestPrompter(
                    mapOf(
                        "email" to "user@test.com",
                        "region" to "us-west-2",
                        "AWS Profile" to "", // Empty = use manual credentials
                        "AWS Access Key" to "AKIAINVALID",
                        "AWS Secret" to "invalidsecret",
                    ),
                )
            setupTestModule()

            // When/Then - should throw after MAX_CREDENTIAL_RETRIES attempts
            val command = SetupProfile()
            assertThatThrownBy { command.execute() }
                .isInstanceOf(SetupProfileException::class.java)
                .hasMessageContaining("AWS credentials are invalid")

            // Should display error message
            assertThat(bufferedOutput.messages.joinToString("\n"))
                .contains("AWS credentials are invalid")

            // Should show retry messages (MAX_CREDENTIAL_RETRIES - 1 retries)
            val output = bufferedOutput.messages.joinToString("\n")
            assertThat(output).contains("Credential validation failed")
        }

        @Test
        fun `execute creates S3 bucket when not already present`() {
            // Given - only keyName is set, s3Bucket is not
            whenever(mockUserConfigProvider.loadExistingConfig()).thenReturn(
                mapOf("keyName" to "test-key"),
            )

            testPrompter =
                TestPrompter(
                    mapOf(
                        "email" to "user@test.com",
                        "region" to "us-west-2",
                        "AWS Profile" to "",
                        "AWS Access Key" to "AKIATEST123",
                        "AWS Secret" to "secretkey123",
                        "IAM policies" to "N",
                    ),
                )
            setupTestModule()

            // When
            val command = SetupProfile()
            command.execute()

            // Then - S3 bucket should be created
            verify(mockAws).createS3Bucket(any())
            verify(mockAws).putS3BucketPolicy(any())
            verify(mockAws).tagS3Bucket(any(), any())

            assertThat(bufferedOutput.messages.joinToString("\n"))
                .contains("Creating S3 bucket")
                .contains("S3 bucket created")
        }

        @Test
        fun `execute skips S3 bucket creation when already present in config`() {
            // Given - existing config has both keyName and s3Bucket set
            whenever(mockUserConfigProvider.loadExistingConfig()).thenReturn(
                mapOf(
                    "keyName" to "test-key",
                    "s3Bucket" to "existing-bucket",
                ),
            )

            testPrompter =
                TestPrompter(
                    mapOf(
                        "email" to "user@test.com",
                        "region" to "us-west-2",
                        "AWS Profile" to "",
                        "AWS Access Key" to "AKIATEST123",
                        "AWS Secret" to "secretkey123",
                        "IAM policies" to "N",
                    ),
                )
            setupTestModule()

            // When
            val command = SetupProfile()
            command.execute()

            // Then - should not create bucket
            verify(mockAws, never()).createS3Bucket(any())
        }
    }

    @Nested
    inner class AMIValidation {
        @BeforeEach
        fun setupConfigAndAws() {
            // Include keyName and s3Bucket to skip AWS resource creation that uses static methods
            whenever(mockUserConfigProvider.loadExistingConfig()).thenReturn(
                mapOf(
                    "keyName" to "test-key",
                    "s3Bucket" to "test-bucket",
                ),
            )
        }

        @Test
        fun `execute succeeds when AMI is found`() {
            // Given
            testPrompter =
                TestPrompter(
                    mapOf(
                        "email" to "user@test.com",
                        "region" to "us-west-2",
                        "AWS Profile" to "",
                        "AWS Access Key" to "AKIATEST123",
                        "AWS Secret" to "secretkey123",
                        "IAM policies" to "N",
                    ),
                )
            setupTestModule()

            // AMI validation succeeds (no exception thrown)

            // When
            val command = SetupProfile()
            command.execute()

            // Then
            val output = bufferedOutput.messages.joinToString("\n")
            assertThat(output).contains("Checking for required AMI")
            assertThat(output).contains("AMI found for")
            assertThat(output).contains("Account setup complete!")
        }

        @Test
        fun `execute skips AMI build when user types skip`() {
            // Given - AMI not found
            whenever(mockAmiValidator.validateAMI(any(), any(), anyOrNull()))
                .thenThrow(AMIValidationException.NoAMIFound("easy-db-lab*", Arch.AMD64))

            testPrompter =
                TestPrompter(
                    mapOf(
                        "email" to "user@test.com",
                        "region" to "us-west-2",
                        "AWS Profile" to "",
                        "AWS Access Key" to "AKIATEST123",
                        "AWS Secret" to "secretkey123",
                        "IAM policies" to "N",
                        "skip" to "skip",
                    ),
                )
            setupTestModule()

            // When
            val command = SetupProfile()
            command.execute()

            // Then - should display skip message
            val output = bufferedOutput.messages.joinToString("\n")
            assertThat(output).contains("AMI not found")
            assertThat(output).contains("Setup cancelled")
            assertThat(output).contains("Run 'easy-db-lab build-image'")

            // Should NOT build image
            verify(mockCommandExecutor, never()).execute<BuildImage>(any())
        }

        @Test
        fun `execute builds AMI when user presses Enter`() {
            // Given - AMI not found
            whenever(mockAmiValidator.validateAMI(any(), any(), anyOrNull()))
                .thenThrow(AMIValidationException.NoAMIFound("easy-db-lab*", Arch.AMD64))

            // Command executor returns 0 (success)
            whenever(mockCommandExecutor.execute<BuildImage>(any())).thenReturn(0)

            testPrompter =
                TestPrompter(
                    mapOf(
                        "email" to "user@test.com",
                        "region" to "us-west-2",
                        "AWS Profile" to "",
                        "AWS Access Key" to "AKIATEST123",
                        "AWS Secret" to "secretkey123",
                        "IAM policies" to "N",
                        "Press Enter" to "",
                    ),
                )
            setupTestModule()

            // When
            val command = SetupProfile()
            command.execute()

            // Then - should build image
            verify(mockCommandExecutor).execute<BuildImage>(any())

            val output = bufferedOutput.messages.joinToString("\n")
            assertThat(output).contains("Building AMI")
            assertThat(output).contains("AMI build completed successfully")
        }

        @Test
        fun `execute handles AMI build failure gracefully`() {
            // Given - AMI not found
            whenever(mockAmiValidator.validateAMI(any(), any(), anyOrNull()))
                .thenThrow(AMIValidationException.NoAMIFound("easy-db-lab*", Arch.AMD64))

            // Command executor throws exception
            whenever(mockCommandExecutor.execute<BuildImage>(any()))
                .thenThrow(RuntimeException("Build failed"))

            testPrompter =
                TestPrompter(
                    mapOf(
                        "email" to "user@test.com",
                        "region" to "us-west-2",
                        "AWS Profile" to "",
                        "AWS Access Key" to "AKIATEST123",
                        "AWS Secret" to "secretkey123",
                        "IAM policies" to "N",
                        "Press Enter" to "",
                    ),
                )
            setupTestModule()

            // When
            val command = SetupProfile()
            command.execute()

            // Then - should display error but not throw
            val output = bufferedOutput.messages.joinToString("\n")
            assertThat(output).contains("Failed to build AMI")
            assertThat(output).contains("You can manually build the AMI")
        }
    }

    @Nested
    inner class SkippableFields {
        @Test
        fun `skippable fields with empty default are not prompted during initial setup`() {
            // Given - config is missing required fields (not fully set up yet)
            // Include keyName and s3Bucket to skip AWS resource creation
            whenever(mockUserConfigProvider.loadExistingConfig()).thenReturn(
                mapOf(
                    "keyName" to "test-key",
                    "s3Bucket" to "test-bucket",
                ),
            )

            testPrompter =
                TestPrompter(
                    mapOf(
                        "email" to "user@test.com",
                        "region" to "us-west-2",
                        "AWS Profile" to "",
                        "AWS Access Key" to "AKIATEST123",
                        "AWS Secret" to "secretkey123",
                        "IAM policies" to "N",
                    ),
                )
            setupTestModule()

            // When
            val command = SetupProfile()
            command.execute()

            // Then - Fields with empty defaults should NOT be prompted (they're skippable)
            // Tailscale device tag is hardcoded to "tag:easy-db-lab" and never prompted
            assertThat(testPrompter.wasPromptedFor("AxonOps Org")).isFalse()
            assertThat(testPrompter.wasPromptedFor("AxonOps Key")).isFalse()
            assertThat(testPrompter.wasPromptedFor("Tailscale OAuth Client ID")).isFalse()
            assertThat(testPrompter.wasPromptedFor("Tailscale OAuth Client Secret")).isFalse()
            assertThat(testPrompter.wasPromptedFor("Tailscale device tag")).isFalse()
        }

        @Test
        fun `optional fields are always prompted when existing profile is detected`() {
            // Given - existing config has required fields
            val existingConfig =
                mapOf(
                    "email" to "test@example.com",
                    "region" to "us-west-2",
                    "awsAccessKey" to "AKIATEST",
                    "awsSecret" to "secret123",
                )
            whenever(mockUserConfigProvider.loadExistingConfig()).thenReturn(existingConfig)

            val userConfig =
                com.rustyrazorblade.easydblab.configuration.User(
                    email = "test@example.com",
                    region = "us-west-2",
                    keyName = "test-key",
                    awsProfile = "",
                    awsAccessKey = "AKIATEST",
                    awsSecret = "secret123",
                )
            whenever(mockUserConfigProvider.getUserConfig()).thenReturn(userConfig)

            testPrompter = TestPrompter()
            setupTestModule()

            // When
            val command = SetupProfile()
            command.execute()

            // Then - All optional fields are prompted (user can press Enter to skip)
            // Note: Tailscale device tag is hardcoded and not prompted
            assertThat(testPrompter.wasPromptedFor("AxonOps Org?")).isTrue()
            assertThat(testPrompter.wasPromptedFor("AxonOps Key?")).isTrue()
            assertThat(testPrompter.wasPromptedFor("Tailscale OAuth Client ID?")).isTrue()
            assertThat(testPrompter.wasPromptedFor("Tailscale OAuth Client Secret?")).isTrue()
        }
    }

    @Nested
    inner class ConfigPersistence {
        @Test
        fun `config is saved after credential validation`() {
            // Given - include keyName and s3Bucket to skip static method calls
            whenever(mockUserConfigProvider.loadExistingConfig()).thenReturn(
                mapOf(
                    "keyName" to "test-key",
                    "s3Bucket" to "test-bucket",
                ),
            )

            testPrompter =
                TestPrompter(
                    mapOf(
                        "email" to "user@test.com",
                        "region" to "us-west-2",
                        "AWS Profile" to "",
                        "AWS Access Key" to "AKIATEST123",
                        "AWS Secret" to "secretkey123",
                        "IAM policies" to "N",
                    ),
                )
            setupTestModule()

            // When
            val command = SetupProfile()
            command.execute()

            // Then - config should be saved multiple times during setup
            verify(mockUserConfigProvider, org.mockito.kotlin.atLeast(1)).saveUserConfig(any())
        }

        @Test
        fun `existing config values are preserved`() {
            // Given - existing config has keyName and s3Bucket
            val existingConfig =
                mapOf(
                    "keyName" to "existing-key",
                    "s3Bucket" to "existing-bucket",
                )
            whenever(mockUserConfigProvider.loadExistingConfig()).thenReturn(existingConfig)

            testPrompter =
                TestPrompter(
                    mapOf(
                        "email" to "user@test.com",
                        "region" to "us-west-2",
                        "AWS Profile" to "",
                        "AWS Access Key" to "AKIATEST123",
                        "AWS Secret" to "secretkey123",
                        "IAM policies" to "N",
                    ),
                )
            setupTestModule()

            // When
            val command = SetupProfile()
            command.execute()

            // Then - should not create bucket (already exists)
            verify(mockAws, never()).createS3Bucket(any())

            // Output should mention completion
            assertThat(bufferedOutput.messages.joinToString("\n"))
                .contains("Account setup complete!")
        }
    }

    @Nested
    inner class PrompterIntegration {
        @Test
        fun `TestPrompter returns configured responses`() {
            val responses =
                mapOf(
                    "email" to "configured@example.com",
                    "region" to "eu-west-1",
                )
            val prompter = TestPrompter(responses)

            val emailResponse = prompter.prompt("What's your email?", "default@example.com")
            val regionResponse = prompter.prompt("What AWS region?", "us-west-2")
            val unknownResponse = prompter.prompt("Unknown question?", "fallback")

            assertThat(emailResponse).isEqualTo("configured@example.com")
            assertThat(regionResponse).isEqualTo("eu-west-1")
            assertThat(unknownResponse).isEqualTo("fallback")
        }

        @Test
        fun `TestPrompter tracks call log`() {
            val prompter = TestPrompter()

            prompter.prompt("Question 1?", "default1")
            prompter.prompt("Question 2?", "default2", secret = true)

            val log = prompter.getCallLog()
            assertThat(log).hasSize(2)
            assertThat(log[0].question).isEqualTo("Question 1?")
            assertThat(log[0].secret).isFalse()
            assertThat(log[1].question).isEqualTo("Question 2?")
            assertThat(log[1].secret).isTrue()
        }

        @Test
        fun `wasPromptedFor checks if question was asked`() {
            val prompter = TestPrompter()
            prompter.prompt("What's your email?", "")
            prompter.prompt("AWS region", "us-west-2")

            assertThat(prompter.wasPromptedFor("email")).isTrue()
            assertThat(prompter.wasPromptedFor("region")).isTrue()
            assertThat(prompter.wasPromptedFor("password")).isFalse()
        }
    }

    @Nested
    inner class WelcomeMessage {
        @Test
        fun `execute displays welcome message when prompting is needed`() {
            // Given - include keyName and s3Bucket to skip static method calls
            whenever(mockUserConfigProvider.loadExistingConfig()).thenReturn(
                mapOf(
                    "keyName" to "test-key",
                    "s3Bucket" to "test-bucket",
                ),
            )

            testPrompter =
                TestPrompter(
                    mapOf(
                        "email" to "user@test.com",
                        "region" to "us-west-2",
                        "AWS Profile" to "",
                        "AWS Access Key" to "AKIATEST123",
                        "AWS Secret" to "secretkey123",
                        "IAM policies" to "N",
                    ),
                )
            setupTestModule()

            // When
            val command = SetupProfile()
            command.execute()

            // Then - welcome message should be displayed
            val output = bufferedOutput.messages.joinToString("\n")
            assertThat(output).contains("Welcome to the easy-db-lab")
            assertThat(output).contains("IMPORTANT")
            assertThat(output).contains("provisions and destroys AWS infrastructure")
        }
    }

    /**
     * Helper to re-register the test module with updated TestPrompter.
     */
    private fun setupTestModule() {
        // Re-register prompter and output handler with the updated instances
        getKoin().declare<Prompter>(testPrompter)
        getKoin().declare<OutputHandler>(bufferedOutput)
    }
}
