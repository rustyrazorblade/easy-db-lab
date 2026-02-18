package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Prompter
import com.rustyrazorblade.easydblab.TestPrompter
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.AWS
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.aws.AMIValidator
import com.rustyrazorblade.easydblab.services.aws.AWSResourceSetupService
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.ListBucketsRequest
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest

/**
 * Integration tests for SetupProfile using LocalStack.
 *
 * These tests verify actual AWS operations (S3 bucket creation, etc.)
 * against a real S3-compatible storage backend.
 *
 * Note: LocalStack IAM support is limited, so IAM operations are mocked.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SetupProfileIntegrationTest : BaseKoinTest() {
    companion object {
        @Container
        @JvmStatic
        val localStack: LocalStackContainer =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                .withServices(Service.S3, Service.STS)
    }

    private lateinit var s3Client: S3Client
    private lateinit var stsClient: StsClient
    private lateinit var iamClient: IamClient
    private lateinit var awsService: AWS

    private lateinit var mockUserConfigProvider: UserConfigProvider
    private lateinit var mockAwsResourceSetup: AWSResourceSetupService
    private lateinit var mockAwsInfra: AwsInfrastructureService
    private lateinit var mockAmiValidator: AMIValidator
    private lateinit var mockCommandExecutor: CommandExecutor
    private lateinit var testPrompter: TestPrompter
    private lateinit var bufferedOutput: BufferedOutputHandler

    @BeforeAll
    fun setupLocalStackClients() {
        val credentials =
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                    localStack.accessKey,
                    localStack.secretKey,
                ),
            )

        s3Client =
            S3Client
                .builder()
                .endpointOverride(localStack.getEndpointOverride(Service.S3))
                .region(Region.of(localStack.region))
                .credentialsProvider(credentials)
                .forcePathStyle(true)
                .build()

        stsClient =
            StsClient
                .builder()
                .endpointOverride(localStack.getEndpointOverride(Service.STS))
                .region(Region.of(localStack.region))
                .credentialsProvider(credentials)
                .build()

        // IAM client - LocalStack IAM is limited, so we mock it
        iamClient = mock()
    }

    @BeforeEach
    fun setupMocks() {
        mockUserConfigProvider = mock()
        mockAwsResourceSetup = mock()
        mockAwsInfra = mock()
        mockAmiValidator = mock()
        mockCommandExecutor = mock()
        testPrompter = TestPrompter()
        bufferedOutput = BufferedOutputHandler()

        // Create AWS service with real S3/STS and mocked IAM
        awsService = AWS(iamClient, s3Client, stsClient)
    }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<UserConfigProvider> { mockUserConfigProvider }
                single<AWSResourceSetupService> { mockAwsResourceSetup }
                single<AwsInfrastructureService> { mockAwsInfra }
                single<AMIValidator> { mockAmiValidator }
                single<CommandExecutor> { mockCommandExecutor }
                single<AWS> { awsService }
                single<Prompter> { testPrompter }
                single<OutputHandler> { bufferedOutput }
                single<S3Client> { s3Client }
                single<StsClient> { stsClient }
                single<IamClient> { iamClient }
            },
        )

    @Test
    fun `AWS service can create S3 bucket via LocalStack`() {
        // Given
        val bucketName = "test-integration-bucket-${System.currentTimeMillis()}"

        // When
        val createdBucket = awsService.createS3Bucket(bucketName)

        // Then
        assertThat(createdBucket).isEqualTo(bucketName)

        // Verify bucket exists
        val headRequest = HeadBucketRequest.builder().bucket(bucketName).build()
        s3Client.headBucket(headRequest) // Throws if bucket doesn't exist
    }

    @Test
    fun `AWS service can tag S3 bucket via LocalStack`() {
        // Given
        val bucketName = "test-tagged-bucket-${System.currentTimeMillis()}"
        awsService.createS3Bucket(bucketName)

        // When
        awsService.tagS3Bucket(
            bucketName,
            mapOf(
                "Profile" to "test",
                "Owner" to "test@example.com",
                "easy_cass_lab" to "1",
            ),
        )

        // Then - verify bucket still exists (tagging didn't break it)
        val headRequest = HeadBucketRequest.builder().bucket(bucketName).build()
        s3Client.headBucket(headRequest)
    }

    @Test
    fun `STS getCallerIdentity works via LocalStack`() {
        // Given
        val request = GetCallerIdentityRequest.builder().build()

        // When
        val response = stsClient.getCallerIdentity(request)

        // Then
        assertThat(response.account()).isNotEmpty()
        assertThat(response.arn()).isNotEmpty()
    }

    @Test
    fun `AWS checkPermissions succeeds with LocalStack`() {
        // Given - real S3/STS clients

        // When/Then - should not throw
        awsService.checkPermissions()
    }

    @Test
    fun `S3 bucket is created idempotently`() {
        // Given
        val bucketName = "test-idempotent-bucket-${System.currentTimeMillis()}"

        // When - create twice
        awsService.createS3Bucket(bucketName)
        val secondCreate = awsService.createS3Bucket(bucketName)

        // Then - should succeed without error
        assertThat(secondCreate).isEqualTo(bucketName)
    }

    @Test
    fun `list buckets returns created buckets`() {
        // Given
        val bucketName = "test-list-bucket-${System.currentTimeMillis()}"
        awsService.createS3Bucket(bucketName)

        // When
        val listRequest = ListBucketsRequest.builder().build()
        val buckets = s3Client.listBuckets(listRequest).buckets()

        // Then
        assertThat(buckets.map { it.name() }).contains(bucketName)
    }
}
