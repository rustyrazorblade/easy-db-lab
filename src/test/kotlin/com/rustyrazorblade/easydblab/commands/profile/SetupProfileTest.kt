package com.rustyrazorblade.easydblab.commands.profile

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Prompter
import com.rustyrazorblade.easydblab.TestProfiles
import com.rustyrazorblade.easydblab.TestPrompter
import com.rustyrazorblade.easydblab.commands.BuildImage
import com.rustyrazorblade.easydblab.commands.SetupProfileException
import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.SshTransport
import com.rustyrazorblade.easydblab.configuration.profile.AxonOps
import com.rustyrazorblade.easydblab.configuration.profile.Profile
import com.rustyrazorblade.easydblab.configuration.profile.ProfileFileException
import com.rustyrazorblade.easydblab.configuration.profile.ProfileStore
import com.rustyrazorblade.easydblab.configuration.profile.Tailscale
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsCredentials
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsDestination
import com.rustyrazorblade.easydblab.configuration.profile.aws.aws
import com.rustyrazorblade.easydblab.configuration.profile.aws.withAws
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.exceptions.ConfigurationException
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.AWSClientFactory
import com.rustyrazorblade.easydblab.providers.aws.AccountIdentity
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.CreateKeyPairRequest
import software.amazon.awssdk.services.ec2.model.CreateKeyPairResponse
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest
import software.amazon.awssdk.services.ec2.model.DescribeKeyPairsRequest
import software.amazon.awssdk.services.ec2.model.DescribeKeyPairsResponse
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.ec2.model.KeyPairInfo
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.GetBucketTaggingRequest
import software.amazon.awssdk.services.s3.model.GetBucketTaggingResponse
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest
import software.amazon.awssdk.services.s3.model.PutBucketTaggingRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.ec2.model.Tag as Ec2Tag
import software.amazon.awssdk.services.s3.model.Tag as S3Tag

/**
 * Profile setup over the real [ProfileStore] in the test profile directory. Each test seeds the
 * store (a current profile through [ProfileStore.replace], an earlier release's file as raw
 * `settings.yaml` text, or nothing) and asserts on what the store decodes after the command ran.
 */
class SetupProfileTest : BaseKoinTest() {
    private lateinit var mockAwsResourceSetup: AWSResourceSetupService
    private lateinit var mockAwsInfra: AwsInfrastructureService
    private lateinit var mockAmiValidator: AMIValidator
    private lateinit var mockCommandExecutor: CommandExecutor
    private lateinit var mockAws: AccountIdentity
    private lateinit var mockAwsClientFactory: AWSClientFactory
    private lateinit var mockEc2: Ec2Client
    private lateinit var testPrompter: TestPrompter
    private lateinit var bufferedOutput: BufferedOutputHandler

    @BeforeEach
    fun setupMocks() {
        mockAwsResourceSetup = mock()
        mockAwsInfra = mock()
        mockAmiValidator = mock()
        mockCommandExecutor = mock()
        mockAws = mock()
        mockAwsClientFactory = mock()
        testPrompter = TestPrompter()
        bufferedOutput = BufferedOutputHandler()

        // Default: factory returns our mock AWS (both credential types)
        whenever(mockAwsClientFactory.createAccountIdentity(any(), any(), any())).thenReturn(mockAws)
        whenever(mockAwsClientFactory.createAccountIdentityWithProfile(any(), any())).thenReturn(mockAws)

        // Default: the profile's existing key pair carries only the legacy marker, and a new one can be made
        mockEc2 = mock()
        whenever(mockAwsClientFactory.createEc2Client(any(), any(), any())).thenReturn(mockEc2)
        whenever(mockAwsClientFactory.createEc2ClientWithProfile(any(), any())).thenReturn(mockEc2)
        whenever(mockEc2.describeKeyPairs(any<DescribeKeyPairsRequest>())).thenReturn(
            DescribeKeyPairsResponse
                .builder()
                .keyPairs(
                    KeyPairInfo
                        .builder()
                        .keyName("test-key")
                        .keyPairId("key-0abc")
                        .tags(
                            Ec2Tag
                                .builder()
                                .key("easy_cass_lab")
                                .value("1")
                                .build(),
                        ).build(),
                ).build(),
        )
        whenever(mockEc2.createKeyPair(any<CreateKeyPairRequest>()))
            .thenReturn(CreateKeyPairResponse.builder().keyMaterial("PRIVATE-KEY").build())

        // Default: AWS operations succeed
        doNothing().whenever(mockAws).checkPermissions()
        whenever(mockAws.getAccountId()).thenReturn("123456789012")
    }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<AWSResourceSetupService> { mockAwsResourceSetup }
                single<AwsInfrastructureService> { mockAwsInfra }
                single<AMIValidator> { mockAmiValidator }
                single<CommandExecutor> { mockCommandExecutor }
                single<AccountIdentity> { mockAws }
                single<AWSClientFactory> { mockAwsClientFactory }
                single<Prompter> { testPrompter }
                single<OutputHandler> { bufferedOutput }
            },
        )

    private val store: ProfileStore get() = getKoin().get()

    /** Replaces the seeded profile with [profile]. */
    private fun seed(profile: Profile) = store.replace(profile)

    /** Leaves only an earlier release's `settings.yaml` holding [lines], one `key: value` each. */
    private fun earlierFile(vararg lines: Pair<String, String>) {
        store.file.delete()
        store.legacyFile.writeText(lines.joinToString("") { (key, value) -> "$key: \"$value\"\n" })
    }

    /** No profile file of either kind: a first run of setup. */
    private fun noProfile() {
        store.file.delete()
        store.legacyFile.delete()
    }

    /** A complete static-key profile, with [axonOps] if given. */
    private fun completeStaticProfile(axonOps: AxonOps? = null): Profile =
        TestProfiles.profile(
            credentials = AwsCredentials.AccessKey("AKIATEST", "secret123"),
            accountBucket = "test-bucket",
            axonOps = axonOps,
        )

    /** The answers a first run with an access key gives to every required prompt. */
    private fun staticKeyAnswers(vararg extra: Pair<String, String>): TestPrompter =
        TestPrompter(
            mapOf(
                "email" to "user@test.com",
                "region" to "us-west-2",
                "AWS Profile" to "", // Empty = use manual credentials
                "AWS Access Key" to "AKIATEST123",
                "AWS Secret" to "secretkey123",
                "IAM policies" to "N",
            ) + extra,
        )

    private val output: String get() = bufferedOutput.messages.joinToString("\n")

    private val destinationLine = Event.Setup.DestinationSelected(AwsDestination.DISPLAY_NAME).toDisplayString()

    @Nested
    inner class WhenProfileAlreadySetUp {
        private val updatePromptText =
            Event.Setup.UpdatePrompt
                .toDisplayString()
                .trim()

        private lateinit var savedBytes: ByteArray

        /** A complete profile from before the new marker, naming bucket legacy-bucket and key pair test-key. */
        private fun completeLegacyProfile() {
            seed(
                TestProfiles.profile(
                    credentials = AwsCredentials.AwsProfile("my-profile"),
                    accountBucket = "legacy-bucket",
                ),
            )
            savedBytes = store.file.readBytes()
            testPrompter = TestPrompter()
            setupTestModule()
        }

        /** Nothing was asked, printed as an update prompt, or saved. */
        private fun assertStoppedBeforeAnyChange() {
            assertThat(testPrompter.getCallLog()).isEmpty()
            assertThat(output).doesNotContain(updatePromptText)
            assertThat(store.file.readBytes()).isEqualTo(savedBytes)
        }

        @Test
        fun `a key pair the complete profile names that is gone stops setup before any prompt`() {
            completeLegacyProfile()
            whenever(mockEc2.describeKeyPairs(any<DescribeKeyPairsRequest>())).thenThrow(
                Ec2Exception
                    .builder()
                    .statusCode(400)
                    .awsErrorDetails(AwsErrorDetails.builder().errorCode("InvalidKeyPair.NotFound").build())
                    .build(),
            )

            assertThatThrownBy { SetupProfile().execute() }
                .isInstanceOf(ConfigurationException::class.java)
                .hasMessageContaining("test-key")

            assertStoppedBeforeAnyChange()
        }

        @Test
        fun `a credential failure reading the bucket stops setup before any prompt`() {
            completeLegacyProfile()
            val expired =
                S3Exception
                    .builder()
                    .statusCode(400)
                    .awsErrorDetails(AwsErrorDetails.builder().errorCode("ExpiredToken").build())
                    .build()
            whenever(get<S3Client>().getBucketTagging(any<GetBucketTaggingRequest>())).thenThrow(expired)

            assertThatThrownBy { SetupProfile().execute() }.isSameAs(expired)

            assertStoppedBeforeAnyChange()
        }

        /** The profiles that need the backfill are complete ones from before the new marker. */
        @Test
        fun `a complete profile's legacy-only bucket and key pair gain the new marker`() {
            completeLegacyProfile()
            whenever(get<S3Client>().getBucketTagging(any<GetBucketTaggingRequest>())).thenReturn(
                GetBucketTaggingResponse
                    .builder()
                    .tagSet(
                        S3Tag
                            .builder()
                            .key("easy_cass_lab")
                            .value("1")
                            .build(),
                    ).build(),
            )

            SetupProfile().execute()

            assertThat(output).contains(updatePromptText)
            verify(get<S3Client>(), never()).createBucket(any<CreateBucketRequest>())
            val bucketTags = argumentCaptor<PutBucketTaggingRequest>()
            verify(get<S3Client>()).putBucketTagging(bucketTags.capture())
            assertThat(bucketTags.firstValue.bucket()).isEqualTo("legacy-bucket")
            assertThat(
                bucketTags.firstValue
                    .tagging()
                    .tagSet()
                    .associate { it.key() to it.value() },
            ).containsExactlyInAnyOrderEntriesOf(mapOf("easy_cass_lab" to "1", "easy_db_lab" to "1"))
            val keyTags = argumentCaptor<CreateTagsRequest>()
            verify(mockEc2).createTags(keyTags.capture())
            assertThat(keyTags.firstValue.tags().associate { it.key() to it.value() })
                .containsExactlyEntriesOf(mapOf("easy_db_lab" to "1"))
        }

        /**
         * A profile has one destination, so reviewing it never offers another.
         *
         * Control: prompt for the destination on the review path; the prompt is logged and the test fails.
         */
        @Test
        fun `existing profile is reviewed without a destination prompt`() {
            seed(completeStaticProfile())
            setupTestModule()

            SetupProfile().execute()

            assertThat(testPrompter.wasPromptedFor("destination")).isFalse()
            assertThat(output).doesNotContain(destinationLine)
            assertThat(output).contains("Profile 'default' is already configured")
        }

        @Test
        fun `execute prompts for each optional field when profile exists`() {
            seed(completeStaticProfile(axonOps = AxonOps("myorg", "")))
            setupTestModule()

            SetupProfile().execute()

            // Note: Tailscale device tag is hardcoded to "tag:easy-db-lab" and not prompted
            assertThat(testPrompter.wasPromptedFor("AxonOps Org?")).isTrue()
            assertThat(testPrompter.wasPromptedFor("AxonOps Key?")).isTrue()
            assertThat(testPrompter.wasPromptedFor("Tailscale OAuth Client ID?")).isTrue()
            assertThat(testPrompter.wasPromptedFor("Tailscale OAuth Client Secret?")).isTrue()

            // The half-set AxonOps block was kept, so its org is shown masked
            assertThat(testPrompter.getCallLog().any { it.question.contains("[m****]") }).isTrue()

            assertThat(output).contains("Profile 'default' is already configured")
            assertThat(output).contains("Configuration updated!")

            // Creates no AWS resources (the marker backfill is checked in its own test)
            verify(mockAwsResourceSetup, never()).ensureAWSResources()
        }

        @Test
        fun `execute shows masked values in prompts`() {
            seed(
                TestProfiles.profile(
                    credentials = AwsCredentials.AwsProfile("my-profile"),
                    accountBucket = "test-bucket",
                    axonOps = AxonOps("acme-corp", "secret-key-123"),
                    tailscale = Tailscale("tskey-client-abc", "tskey-secret-xyz", "tag:my-lab"),
                ),
            )
            setupTestModule()

            SetupProfile().execute()

            val callLog = testPrompter.getCallLog()
            assertThat(callLog.any { it.question.contains("AxonOps Org?") && it.question.contains("[a****]") }).isTrue()
            assertThat(callLog.any { it.question.contains("AxonOps Key?") && it.question.contains("[s****]") }).isTrue()
            assertThat(callLog.any { it.question.contains("Tailscale OAuth Client ID?") && it.question.contains("[t****]") }).isTrue()
            // Kept: setup never asks for the tag, so the saved one stays
            assertThat(store.load().tailscale?.tag).isEqualTo("tag:my-lab")
        }

        @Test
        fun `execute shows not set for empty values`() {
            seed(completeStaticProfile())
            setupTestModule()

            SetupProfile().execute()

            val callLog = testPrompter.getCallLog()
            assertThat(callLog.any { it.question.contains("AxonOps Org?") && it.question.contains("(not set)") }).isTrue()
        }

        @Test
        fun `execute saves a new value the user enters and keeps the rest`() {
            seed(completeStaticProfile(axonOps = AxonOps("old-org", "")))
            testPrompter = TestPrompter(mapOf("AxonOps Org?" to "new-org"))
            setupTestModule()

            SetupProfile().execute()

            val saved = store.load()
            assertThat(saved.axonOps).isEqualTo(AxonOps("new-org", ""))
            assertThat(saved.aws).isEqualTo(completeStaticProfile().aws)
        }

        @Test
        fun `execute saves the ssm transport when the user selects it`() {
            seed(completeStaticProfile())
            testPrompter = TestPrompter(mapOf("SSH transport" to "ssm"))
            setupTestModule()

            SetupProfile().execute()

            assertThat(store.load().aws.sshTransport).isEqualTo(SshTransport.Ssm)
        }

        @Test
        fun `execute asks again until the transport answer is recognized`() {
            seed(completeStaticProfile())
            testPrompter = TestPrompter().apply { addSequentialResponses("SSH transport", "tunnel", " SSM ") }
            setupTestModule()

            SetupProfile().execute()

            assertThat(store.load().aws.sshTransport).isEqualTo(SshTransport.Ssm)
            assertThat(testPrompter.getCallLog().count { it.question.contains("SSH transport") }).isEqualTo(2)
        }

        @Test
        fun `execute keeps the current transport when the answer is blank`() {
            seed(completeStaticProfile().withAws { it.copy(sshTransport = SshTransport.Ssm) })
            testPrompter = TestPrompter(mapOf("SSH transport" to ""))
            setupTestModule()

            SetupProfile().execute()

            assertThat(store.load().aws.sshTransport).isEqualTo(SshTransport.Ssm)
        }

        /**
         * A saved transport this version cannot read makes the profile fail to load, which would
         * leave setup, the command that fixes a profile, unable to run. Setup reads the flat setup
         * values instead, keeps every other value, and asks for the transport once. This holds for
         * `profile.yaml` and for an earlier release's `settings.yaml`, which is left as it was.
         *
         * Control: prefill from the decoded profile instead of loadForSetup; the load fails and the test fails.
         */
        @ParameterizedTest(name = "earlier file: {0}")
        @ValueSource(booleans = [false, true])
        fun `an unreadable saved transport is reported and asked for again`(earlier: Boolean) {
            if (earlier) {
                earlierFile(
                    "email" to "test@example.com",
                    "region" to "us-west-2",
                    "awsProfile" to "my-profile",
                    "keyName" to "test-key",
                    "s3Bucket" to "test-bucket",
                    "sshTransport" to "tunnel",
                )
            } else {
                store.file.writeText(CURRENT_FILE_WITH_BAD_TRANSPORT)
            }
            val earlierBytes = store.legacyFile.takeIf { it.exists() }?.readBytes()
            testPrompter = TestPrompter(mapOf("SSH transport" to "ssm"))
            setupTestModule()

            SetupProfile().execute()

            assertThat(bufferedOutput.errors.joinToString("\n") { it.first }).contains("'tunnel' is not an SSH transport")
            assertThat(testPrompter.getCallLog().count { it.question.contains("SSH transport") }).isEqualTo(1)
            val saved = store.load().aws
            assertThat(saved.sshTransport).isEqualTo(SshTransport.Ssm)
            assertThat(saved.credentials).isEqualTo(AwsCredentials.AwsProfile("my-profile"))
            assertThat(saved.keyName).isEqualTo("test-key")
            assertThat(saved.accountBucket).isEqualTo("test-bucket")
            assertThat(store.legacyFile.takeIf { it.exists() }?.readBytes()).isEqualTo(earlierBytes)
        }
    }

    @Nested
    inner class WhenMissingRequiredFields {
        @BeforeEach
        fun setupEmptyConfig() {
            // An interrupted setup: an earlier file with a key pair and bucket but no credentials,
            // so the AWS resource steps that would need static clients are skipped.
            earlierFile("keyName" to "test-key", "s3Bucket" to "test-bucket")
        }

        @Test
        fun `execute prompts for all required fields and sets up AWS resources with static credentials`() {
            testPrompter = staticKeyAnswers()
            setupTestModule()

            SetupProfile().execute()

            assertThat(testPrompter.wasPromptedFor("email")).isTrue()
            assertThat(testPrompter.wasPromptedFor("region")).isTrue()
            assertThat(testPrompter.wasPromptedFor("AWS Profile")).isTrue()
            assertThat(testPrompter.wasPromptedFor("AWS Access Key")).isTrue()
            assertThat(testPrompter.wasPromptedFor("AWS Secret")).isTrue()

            // Should call AWS validation using static credentials
            verify(mockAwsClientFactory).createAccountIdentity(any(), any(), any())
            verify(mockAws).checkPermissions()

            verify(mockAwsResourceSetup).ensureAWSResources()

            // Should create Packer infrastructure, scoping SSH to the developer's IP
            verify(mockAwsInfra).ensurePackerInfrastructure(any(), any())
        }

        /**
         * The only destination is AWS, so a first run states it instead of asking.
         *
         * Control: prompt for the destination before the credentials; the prompt is logged and the test fails.
         */
        @Test
        fun `new profile with one destination does not ask for it and emits DestinationSelected`() {
            noProfile()
            testPrompter = staticKeyAnswers()
            setupTestModule()

            SetupProfile().execute()

            assertThat(testPrompter.wasPromptedFor("destination")).isFalse()
            assertThat(output).contains(destinationLine)
            assertThat(store.load().destination).isInstanceOf(AwsDestination::class.java)
        }

        /**
         * An interrupted setup left an earlier file without credentials. Setup asks only for what is
         * missing, keeps every value the file held, and leaves the file as it was.
         *
         * Control: prefill from an empty map; email and region are asked for and the saved values are lost.
         */
        @Test
        fun `interrupted earlier profile prefills setup`() {
            earlierFile(
                "email" to "earlier@example.com",
                "region" to "eu-west-1",
                "awsProfile" to "",
                "keyName" to "earlier-key",
                "s3Bucket" to "earlier-bucket",
                "axonOpsOrg" to "earlier-org",
            )
            val earlierBytes = store.legacyFile.readBytes()
            testPrompter = TestPrompter(mapOf("AWS Profile" to "my-profile", "IAM policies" to "N"))
            setupTestModule()

            SetupProfile().execute()

            assertThat(testPrompter.wasPromptedFor("email")).isFalse()
            assertThat(testPrompter.wasPromptedFor("region")).isFalse()
            val saved = store.load()
            assertThat(saved.email).isEqualTo("earlier@example.com")
            assertThat(saved.aws.location.region).isEqualTo("eu-west-1")
            assertThat(saved.aws.credentials).isEqualTo(AwsCredentials.AwsProfile("my-profile"))
            assertThat(saved.aws.keyName).isEqualTo("earlier-key")
            assertThat(saved.aws.accountBucket).isEqualTo("earlier-bucket")
            assertThat(saved.axonOps).isEqualTo(AxonOps("earlier-org", ""))
            assertThat(store.legacyFile.readBytes()).isEqualTo(earlierBytes)
        }

        @Test
        fun `execute records the transport chosen during first-time setup`() {
            testPrompter =
                TestPrompter(
                    mapOf(
                        "email" to "user@test.com",
                        "region" to "us-west-2",
                        "AWS Profile" to "my-profile",
                        "IAM policies" to "N",
                        "SSH transport" to "ssm",
                    ),
                )
            setupTestModule()

            SetupProfile().execute()

            assertThat(store.load().aws.sshTransport).isEqualTo(SshTransport.Ssm)
        }

        @Test
        fun `execute uses profile-based auth when profile is provided`() {
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

            SetupProfile().execute()

            // Should NOT prompt for access key/secret
            assertThat(testPrompter.wasPromptedFor("AWS Access Key")).isFalse()
            assertThat(testPrompter.wasPromptedFor("AWS Secret")).isFalse()

            // Should call AWS validation using profile-based auth
            verify(mockAwsClientFactory).createAccountIdentityWithProfile(any(), any())
            verify(mockAws).checkPermissions()

            verify(mockAwsResourceSetup).ensureAWSResources()
            assertThat(store.load().aws.credentials).isEqualTo(AwsCredentials.AwsProfile("my-profile"))
        }

        @Test
        fun `execute retries with new credentials when validation fails then succeeds`() {
            var callCount = 0
            whenever(mockAws.checkPermissions()).thenAnswer {
                callCount++
                if (callCount == 1) {
                    throw RuntimeException("Invalid credentials")
                }
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

            SetupProfile().execute()

            assertThat(output).contains("Credential validation failed")
            assertThat(output).contains("AWS credentials validated successfully")
            assertThat(output).contains("Account setup complete!")

            verify(mockAws, times(2)).checkPermissions()
        }

        @Test
        fun `execute displays IAM policies when user requests them`() {
            testPrompter = staticKeyAnswers("IAM policies" to "y")
            setupTestModule()

            SetupProfile().execute()

            assertThat(output).contains("AWS IAM PERMISSIONS REQUIRED")
            assertThat(output).contains("Managed Policies")
        }

        @Test
        fun `a misspelt region is reported and asked for again before any AWS call`() {
            testPrompter =
                TestPrompter(
                    mapOf(
                        "email" to "user@test.com",
                        "AWS Profile" to "my-profile",
                        "IAM policies" to "N",
                    ),
                )
            testPrompter.addSequentialResponses("AWS region", "us-wset-2", "us-west-2")
            setupTestModule()

            SetupProfile().execute()

            assertThat(bufferedOutput.errors.joinToString("\n") { it.first }).contains("'us-wset-2' is not an AWS region")
            assertThat(output).contains("Account setup complete!")
            assertThat(testPrompter.getCallLog().count { it.question.contains("AWS region") }).isEqualTo(2)
            val region = argumentCaptor<String>()
            verify(mockAwsClientFactory).createAccountIdentityWithProfile(any(), region.capture())
            assertThat(region.allValues).containsExactly("us-west-2")
        }

        @Test
        fun `a saved unknown region is asked for again, not reused, and the saved profile is kept`() {
            earlierFile(
                "email" to "user@test.com",
                "region" to "mars-north-1",
                "awsProfile" to "saved-profile",
                "keyName" to "test-key",
                // Empty, so the profile is not fully set up and setup runs the credential loop.
                "s3Bucket" to "",
            )
            testPrompter = TestPrompter(mapOf("IAM policies" to "N"))
            testPrompter.addSequentialResponses("AWS region", "eu-west-1")
            setupTestModule()

            SetupProfile().execute()

            val profile = argumentCaptor<String>()
            val region = argumentCaptor<String>()
            verify(mockAwsClientFactory).createAccountIdentityWithProfile(profile.capture(), region.capture())
            assertThat(region.allValues).containsExactly("eu-west-1")
            // Only the region failed, so the saved profile is still the one used and is not asked for.
            assertThat(profile.allValues).containsExactly("saved-profile")
            assertThat(testPrompter.wasPromptedFor("AWS Profile")).isFalse()
            assertThat(
                store
                    .load()
                    .aws.location.region,
            ).isEqualTo("eu-west-1")
        }

        @Test
        fun `setup fails after the last attempt gives an unknown region`() {
            testPrompter = TestPrompter(mapOf("email" to "user@test.com", "AWS Profile" to "my-profile"))
            testPrompter.addSequentialResponses("AWS region", "nowhere-1")
            setupTestModule()

            assertThatThrownBy { SetupProfile().execute() }
                .isInstanceOf(SetupProfileException::class.java)
                .hasMessageContaining("nowhere-1")
            assertThat(testPrompter.getCallLog().count { it.question.contains("AWS region") })
                .isEqualTo(SetupProfile.MAX_CREDENTIAL_RETRIES)
            verify(mockAwsClientFactory, never()).createAccountIdentityWithProfile(any(), any())
            assertThat(store.file).doesNotExist()
        }

        @Test
        fun `execute throws SetupProfileException after max retries when credentials are invalid`() {
            doThrow(RuntimeException("Invalid credentials")).whenever(mockAws).checkPermissions()
            testPrompter = staticKeyAnswers("AWS Access Key" to "AKIAINVALID", "AWS Secret" to "invalidsecret")
            setupTestModule()

            assertThatThrownBy { SetupProfile().execute() }
                .isInstanceOf(SetupProfileException::class.java)
                .hasMessageContaining("AWS credentials are invalid")

            assertThat(output).contains("AWS credentials are invalid")
            assertThat(output).contains("Credential validation failed")
            assertThat(store.file).doesNotExist()
        }

        @Test
        fun `execute creates S3 bucket when not already present and saves its name`() {
            earlierFile("keyName" to "test-key")
            testPrompter = staticKeyAnswers()
            setupTestModule()

            SetupProfile().execute()

            val s3Client = get<S3Client>()
            val created = argumentCaptor<CreateBucketRequest>()
            verify(s3Client).createBucket(created.capture())
            verify(s3Client).putBucketPolicy(any<PutBucketPolicyRequest>())
            verify(s3Client).putBucketTagging(any<PutBucketTaggingRequest>())
            assertThat(store.load().aws.accountBucket).isEqualTo(created.firstValue.bucket())

            assertThat(output)
                .contains("Creating S3 bucket")
                .contains("S3 bucket created")
        }

        /**
         * A misspelled `accountBucket` would read as "no bucket yet": setup would run in full, create a
         * second bucket and overwrite the file. It stops with the file's failure and changes nothing.
         *
         * Control: make loadForSetup lenient about unknown keys; a bucket is created and the file is rewritten.
         */
        @Test
        fun `a misspelled key in profile yaml stops setup before it creates a bucket or changes the file`() {
            seed(completeStaticProfile())
            store.file.writeText(store.file.readText().replace("accountBucket:", "accountBuket:"))
            val before = store.file.readBytes()
            testPrompter = staticKeyAnswers()
            setupTestModule()

            assertThatThrownBy { SetupProfile().execute() }
                .isInstanceOfSatisfying(ProfileFileException::class.java) { e ->
                    assertThat(e.reason).contains("accountBuket")
                    assertThat(e.message).doesNotContain("run 'easy-db-lab profile setup'")
                }

            verify(get<S3Client>(), never()).createBucket(any<CreateBucketRequest>())
            assertThat(store.file.readBytes()).isEqualTo(before)
            assertThat(testPrompter.getCallLog()).isEmpty()
        }

        @Test
        fun `execute skips S3 bucket creation when already present in config`() {
            earlierFile("keyName" to "test-key", "s3Bucket" to "existing-bucket")
            testPrompter = staticKeyAnswers()
            setupTestModule()

            SetupProfile().execute()

            verify(get<S3Client>(), never()).createBucket(any<CreateBucketRequest>())
            assertThat(store.load().aws.accountBucket).isEqualTo("existing-bucket")
        }

        @Test
        fun `execute gives an existing bucket and key pair the new marker tag`() {
            earlierFile("keyName" to "test-key", "s3Bucket" to "existing-bucket")
            testPrompter = staticKeyAnswers()
            setupTestModule()

            SetupProfile().execute()

            val bucketTags = argumentCaptor<PutBucketTaggingRequest>()
            verify(get<S3Client>()).putBucketTagging(bucketTags.capture())
            assertThat(bucketTags.firstValue.bucket()).isEqualTo("existing-bucket")
            assertThat(
                bucketTags.firstValue
                    .tagging()
                    .tagSet()
                    .associate { it.key() to it.value() },
            ).containsEntry("easy_cass_lab", "1")
                .containsEntry("easy_db_lab", "1")
            val keyTags = argumentCaptor<CreateTagsRequest>()
            verify(mockEc2).createTags(keyTags.capture())
            assertThat(keyTags.firstValue.resources()).containsExactly("key-0abc")
            assertThat(keyTags.firstValue.tags().associate { it.key() to it.value() })
                .containsExactlyEntriesOf(mapOf("easy_db_lab" to "1"))
        }
    }

    @Nested
    inner class WhenProfileLacksTheBucket {
        @BeforeEach
        fun seedHalfSetUpProfile() {
            // A profile that can authenticate but never got its bucket: the review path would
            // leave it that way forever, because it only re-prompts the optional fields.
            seed(TestProfiles.profile(email = "user@test.com", credentials = AwsCredentials.AwsProfile("my-profile")))
            testPrompter = TestPrompter(mapOf("IAM policies" to "N"))
            setupTestModule()
        }

        @Test
        fun `takes the full setup path, keeps what it has and creates the missing bucket`() {
            SetupProfile().execute()

            verify(mockAwsResourceSetup).ensureAWSResources()
            verify(get<S3Client>()).createBucket(any<CreateBucketRequest>())
            val saved = store.load().aws
            assertThat(saved.accountBucket).startsWith("easy-db-lab-")
            assertThat(saved.keyName).isEqualTo("test-key")
            assertThat(saved.credentials).isEqualTo(AwsCredentials.AwsProfile("my-profile"))
        }

        @Test
        fun `asks for the AWS profile again when the saved one fails validation`() {
            doThrow(RuntimeException("expired")).doNothing().whenever(mockAws).checkPermissions()
            testPrompter = TestPrompter(mapOf("IAM policies" to "N", "AWS Profile" to "fixed-profile"))
            setupTestModule()

            SetupProfile().execute()

            assertThat(testPrompter.wasPromptedFor("AWS Profile")).isTrue()
            verify(mockAwsClientFactory, atLeastOnce()).createAccountIdentityWithProfile(eq("fixed-profile"), any())
            assertThat(store.load().aws.credentials).isEqualTo(AwsCredentials.AwsProfile("fixed-profile"))
        }

        @Test
        fun `does not ask again for the details it already holds`() {
            SetupProfile().execute()

            assertThat(testPrompter.wasPromptedFor("email")).isFalse()
            assertThat(testPrompter.wasPromptedFor("region")).isFalse()
            assertThat(testPrompter.wasPromptedFor("AWS Profile")).isFalse()
            verify(mockAwsClientFactory, atLeastOnce()).createAccountIdentityWithProfile(eq("my-profile"), any())
        }
    }

    @Nested
    inner class AMIValidation {
        @BeforeEach
        fun setupConfigAndAws() {
            earlierFile("keyName" to "test-key", "s3Bucket" to "test-bucket")
        }

        @Test
        fun `execute succeeds when AMI is found`() {
            testPrompter = staticKeyAnswers()
            setupTestModule()

            SetupProfile().execute()

            assertThat(output).contains("Checking for required AMI")
            assertThat(output).contains("AMI found for")
            assertThat(output).contains("Account setup complete!")
        }

        @Test
        fun `execute skips AMI build when user types skip`() {
            whenever(mockAmiValidator.validateAMI(any(), any(), anyOrNull()))
                .thenThrow(AMIValidationException.NoAMIFound("easy-db-lab*", Arch.AMD64))
            testPrompter = staticKeyAnswers("skip" to "skip")
            setupTestModule()

            SetupProfile().execute()

            assertThat(output).contains("AMI not found")
            assertThat(output).contains("Setup cancelled")
            assertThat(output).contains("Run 'easy-db-lab build-image'")
            verify(mockCommandExecutor, never()).execute<BuildImage>(any())
        }

        @Test
        fun `execute builds AMI when user presses Enter`() {
            whenever(mockAmiValidator.validateAMI(any(), any(), anyOrNull()))
                .thenThrow(AMIValidationException.NoAMIFound("easy-db-lab*", Arch.AMD64))
            whenever(mockCommandExecutor.execute<BuildImage>(any())).thenReturn(0)
            testPrompter = staticKeyAnswers("Press Enter" to "")
            setupTestModule()

            SetupProfile().execute()

            verify(mockCommandExecutor).execute<BuildImage>(any())
            assertThat(output).contains("Building AMI")
            assertThat(output).contains("AMI build completed successfully")
        }

        @Test
        fun `execute handles AMI build failure gracefully`() {
            whenever(mockAmiValidator.validateAMI(any(), any(), anyOrNull()))
                .thenThrow(AMIValidationException.NoAMIFound("easy-db-lab*", Arch.AMD64))
            whenever(mockCommandExecutor.execute<BuildImage>(any()))
                .thenThrow(RuntimeException("Build failed"))
            testPrompter = staticKeyAnswers("Press Enter" to "")
            setupTestModule()

            SetupProfile().execute()

            assertThat(output).contains("Failed to build AMI")
            assertThat(output).contains("You can manually build the AMI")
        }

        /** The executor reports a failed build by its exit code; it does not throw. */
        @Test
        fun `a build-image that exits non-zero is reported as a failed build, not a success`() {
            whenever(mockAmiValidator.validateAMI(any(), any(), anyOrNull()))
                .thenThrow(AMIValidationException.NoAMIFound("easy-db-lab*", Arch.AMD64))
            whenever(mockCommandExecutor.execute<BuildImage>(any())).thenReturn(1)
            testPrompter = staticKeyAnswers("Press Enter" to "")
            setupTestModule()

            SetupProfile().execute()

            assertThat(output).contains("Failed to build AMI: build-image exited with code 1")
            assertThat(output).doesNotContain("AMI build completed successfully")
        }
    }

    @Nested
    inner class SkippableFields {
        @Test
        fun `skippable fields with empty default are not prompted during initial setup`() {
            earlierFile("keyName" to "test-key", "s3Bucket" to "test-bucket")
            testPrompter = staticKeyAnswers()
            setupTestModule()

            SetupProfile().execute()

            // Tailscale device tag is hardcoded to "tag:easy-db-lab" and never prompted
            assertThat(testPrompter.wasPromptedFor("AxonOps Org")).isFalse()
            assertThat(testPrompter.wasPromptedFor("AxonOps Key")).isFalse()
            assertThat(testPrompter.wasPromptedFor("Tailscale OAuth Client ID")).isFalse()
            assertThat(testPrompter.wasPromptedFor("Tailscale OAuth Client Secret")).isFalse()
            assertThat(testPrompter.wasPromptedFor("Tailscale device tag")).isFalse()
            val saved = store.load()
            assertThat(saved.axonOps).isNull()
            assertThat(saved.tailscale).isNull()
        }

        @Test
        fun `optional fields are always prompted when existing profile is detected`() {
            seed(completeStaticProfile())
            setupTestModule()

            SetupProfile().execute()

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
        fun `the validated credentials are saved with the values the earlier file held`() {
            earlierFile("keyName" to "test-key", "s3Bucket" to "test-bucket")
            testPrompter = staticKeyAnswers()
            setupTestModule()

            SetupProfile().execute()

            val saved = store.load()
            assertThat(saved.email).isEqualTo("user@test.com")
            assertThat(saved.aws.location.region).isEqualTo("us-west-2")
            assertThat(saved.aws.credentials).isEqualTo(AwsCredentials.AccessKey("AKIATEST123", "secretkey123"))
            assertThat(saved.aws.keyName).isEqualTo("test-key")
            assertThat(saved.aws.accountBucket).isEqualTo("test-bucket")
        }

        @Test
        fun `existing config values are preserved`() {
            earlierFile("keyName" to "existing-key", "s3Bucket" to "existing-bucket")
            testPrompter = staticKeyAnswers()
            setupTestModule()

            SetupProfile().execute()

            verify(get<S3Client>(), never()).createBucket(any<CreateBucketRequest>())
            val saved = store.load().aws
            assertThat(saved.keyName).isEqualTo("existing-key")
            assertThat(saved.accountBucket).isEqualTo("existing-bucket")
            assertThat(output).contains("Account setup complete!")
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
            earlierFile("keyName" to "test-key", "s3Bucket" to "test-bucket")
            testPrompter = staticKeyAnswers()
            setupTestModule()

            SetupProfile().execute()

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

    private companion object {
        /** A current profile whose SSH transport this version does not know; the rest is readable. */
        val CURRENT_FILE_WITH_BAD_TRANSPORT =
            """
            email: "test@example.com"
            destination:
              type: "aws"
              location:
                region: "us-west-2"
              credentials:
                type: "aws-profile"
                name: "my-profile"
              keyName: "test-key"
              accountBucket: "test-bucket"
              sshTransport: "tunnel"
            """.trimIndent() + "\n"
    }
}
