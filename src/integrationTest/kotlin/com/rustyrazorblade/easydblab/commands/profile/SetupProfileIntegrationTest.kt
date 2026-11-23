package com.rustyrazorblade.easydblab.commands.profile

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Prompter
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.TestPrompter
import com.rustyrazorblade.easydblab.commands.BuildImage
import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.profile.ProfileStore
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsDestination
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsDestinationAccess
import com.rustyrazorblade.easydblab.configuration.profile.aws.aws
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.AWSClientFactory
import com.rustyrazorblade.easydblab.providers.aws.AccountIdentity
import com.rustyrazorblade.easydblab.providers.aws.StsAccountIdentity
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.aws.AMIValidationException
import com.rustyrazorblade.easydblab.services.aws.AMIValidator
import com.rustyrazorblade.easydblab.services.aws.AWSResourceSetupService
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import com.rustyrazorblade.easydblab.services.aws.AwsS3BucketService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeKeyPairsRequest
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.ListBucketsRequest
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest

/**
 * Integration tests for SetupProfile using LocalStack.
 *
 * These tests verify actual AWS operations (S3 bucket creation, key pair creation) against
 * LocalStack, with the real [ProfileStore] over the test profile directory, so what setup created
 * is asserted on the decoded `profile.yaml`.
 *
 * Uses the JVM-wide [SharedLocalStack] container. Bucket names are made unique per test so
 * co-tenant S3 tests in the same container cannot interfere.
 *
 * Note: LocalStack IAM support is limited, so IAM operations are mocked.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SetupProfileIntegrationTest : BaseKoinTest() {
    private lateinit var s3Client: S3Client
    private lateinit var stsClient: StsClient
    private lateinit var iamClient: IamClient
    private lateinit var accountIdentity: AccountIdentity
    private lateinit var bucketService: AwsS3BucketService

    private lateinit var mockAwsResourceSetup: AWSResourceSetupService
    private lateinit var mockAwsInfra: AwsInfrastructureService
    private lateinit var mockAmiValidator: AMIValidator
    private lateinit var mockCommandExecutor: CommandExecutor
    private lateinit var testPrompter: TestPrompter
    private lateinit var bufferedOutput: BufferedOutputHandler

    @BeforeAll
    fun setupLocalStackClients() {
        s3Client = SharedLocalStack.s3Client()
        stsClient = SharedLocalStack.stsClient()

        // IAM client - LocalStack IAM is limited, so we mock it
        iamClient = mock()
    }

    @BeforeEach
    fun setupMocks() {
        mockAwsResourceSetup = mock()
        mockAwsInfra = mock()
        mockAmiValidator = mock()
        mockCommandExecutor = mock()
        bufferedOutput = BufferedOutputHandler()
        // A first run with a named AWS profile; LocalStack accepts any credentials.
        testPrompter =
            TestPrompter(
                mapOf(
                    "email" to "user@example.com",
                    "region" to SharedLocalStack.region(),
                    "AWS Profile" to "localstack",
                    "IAM policies" to "N",
                    "Press Enter" to "",
                ),
            )

        // Real S3 and STS adapters on LocalStack
        accountIdentity = StsAccountIdentity(stsClient)
        bucketService = AwsS3BucketService(s3Client, accountIdentity)
    }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<AWSResourceSetupService> { mockAwsResourceSetup }
                single<AwsInfrastructureService> { mockAwsInfra }
                single<AMIValidator> { mockAmiValidator }
                single<CommandExecutor> { mockCommandExecutor }
                single<AccountIdentity> { accountIdentity }
                single<AWSClientFactory> { localStackClients() }
                single<AwsS3BucketService> { bucketService }
                single<Prompter> { testPrompter }
                single<OutputHandler> { bufferedOutput }
                single<S3Client> { s3Client }
                single<StsClient> { stsClient }
                single<IamClient> { iamClient }
            },
        )

    /** Clients on LocalStack for either credential form; each EC2 client is new, since its user closes it. */
    private fun localStackClients(): AWSClientFactory =
        object : AWSClientFactory {
            override fun createAccountIdentity(
                accessKey: String,
                secret: String,
                region: String,
            ): AccountIdentity = accountIdentity

            override fun createAccountIdentityWithProfile(
                profileName: String,
                region: String,
            ): AccountIdentity = accountIdentity

            override fun createEc2Client(
                accessKey: String,
                secret: String,
                region: String,
            ): Ec2Client = SharedLocalStack.ec2Client(region)

            override fun createEc2ClientWithProfile(
                profileName: String,
                region: String,
            ): Ec2Client = SharedLocalStack.ec2Client(region)
        }

    private val store: ProfileStore get() = getKoin().get()

    /** No profile file of either kind: a first run of setup. */
    private fun noProfile() {
        store.file.delete()
        store.legacyFile.delete()
    }

    /**
     * The bucket setup creates is saved, through the bucket service, into `profile.yaml`.
     *
     * Control: skip the profile update in the bucket service's create path; the decoded bucket is blank.
     */
    @Test
    fun `setup writes the account bucket it created to profile yaml`() {
        noProfile()

        SetupProfile().execute()

        val bucket = store.load().aws.accountBucket
        assertThat(bucket).startsWith("easy-db-lab-")
        s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build()) // Throws if missing
    }

    /**
     * The key pair setup creates is saved, through the key pair service, into `profile.yaml`.
     *
     * Control: skip the profile update in the key pair service's generate; the decoded key name is blank.
     */
    @Test
    fun `setup writes the key pair it created to profile yaml`() {
        noProfile()

        SetupProfile().execute()

        val keyName = store.load().aws.keyName
        assertThat(keyName).startsWith("easy-db-lab-")
        val found =
            SharedLocalStack.ec2Client(SharedLocalStack.region()).use { ec2 ->
                ec2.describeKeyPairs(DescribeKeyPairsRequest.builder().keyNames(keyName).build()).keyPairs()
            }
        assertThat(found.map { it.keyName() }).containsExactly(keyName)
        assertThat(store.sshKeyPath).isNotBlank()
    }

    /**
     * The image build at the end of a first setup reads the bucket and key setup created earlier in
     * the same run, through an accessor resolved before either existed.
     *
     * Control: bind AwsDestinationAccess as a snapshot of the profile taken when it is resolved; the
     * recorded build sees a blank bucket and key.
     */
    @Test
    fun `the image build of a new profile sees the bucket and key setup created in the same run`() {
        noProfile()
        // Resolved before setup runs, as the build's own collaborators are in the tool.
        val destination = getKoin().get<AwsDestinationAccess>()
        val seen = mutableListOf<AwsDestination>()
        whenever(mockAmiValidator.validateAMI(any(), any(), anyOrNull()))
            .thenThrow(AMIValidationException.NoAMIFound("easy-db-lab*", Arch.AMD64))
        whenever(mockCommandExecutor.execute<BuildImage>(any())).thenAnswer {
            seen.add(destination.current())
            0
        }

        SetupProfile().execute()

        val saved = store.load().aws
        assertThat(saved.accountBucket).isNotBlank()
        assertThat(saved.keyName).isNotBlank()
        assertThat(seen).hasSize(1)
        assertThat(seen.single().accountBucket).isEqualTo(saved.accountBucket)
        assertThat(seen.single().keyName).isEqualTo(saved.keyName)
    }

    @Test
    fun `bucket service can create S3 bucket via LocalStack`() {
        val bucketName = "test-integration-bucket-${System.currentTimeMillis()}"

        val createdBucket = bucketService.createBucket(bucketName)

        assertThat(createdBucket).isEqualTo(bucketName)
        s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build()) // Throws if missing
    }

    @Test
    fun `bucket service can tag S3 bucket via LocalStack`() {
        val bucketName = "test-tagged-bucket-${System.currentTimeMillis()}"
        bucketService.createBucket(bucketName)

        bucketService.tagBucket(
            bucketName,
            mapOf(
                "Profile" to "test",
                "Owner" to "test@example.com",
                "easy_cass_lab" to "1",
            ),
        )

        // Tagging didn't break the bucket
        s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build())
    }

    @Test
    fun `STS getCallerIdentity works via LocalStack`() {
        val response = stsClient.getCallerIdentity(GetCallerIdentityRequest.builder().build())

        assertThat(response.account()).isNotEmpty()
        assertThat(response.arn()).isNotEmpty()
    }

    @Test
    fun `account identity checkPermissions succeeds with LocalStack`() {
        // Should not throw
        accountIdentity.checkPermissions()
    }

    @Test
    fun `S3 bucket is created idempotently`() {
        val bucketName = "test-idempotent-bucket-${System.currentTimeMillis()}"

        bucketService.createBucket(bucketName)
        val secondCreate = bucketService.createBucket(bucketName)

        assertThat(secondCreate).isEqualTo(bucketName)
    }

    @Test
    fun `list buckets returns created buckets`() {
        val bucketName = "test-list-bucket-${System.currentTimeMillis()}"
        bucketService.createBucket(bucketName)

        val buckets = s3Client.listBuckets(ListBucketsRequest.builder().build()).buckets()

        assertThat(buckets.map { it.name() }).contains(bucketName)
    }
}
