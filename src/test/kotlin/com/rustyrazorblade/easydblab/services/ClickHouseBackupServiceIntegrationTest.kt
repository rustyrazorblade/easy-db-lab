package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.clickhouseBackupsRoot
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.aws.S3ObjectStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.mock
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClickHouseBackupServiceIntegrationTest {
    companion object {
        private const val TEST_BUCKET = "test-clickhouse-backups"
        private const val EMPTY_BUCKET = "test-clickhouse-backups-empty"

        @Container
        @JvmStatic
        val localStack: LocalStackContainer =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                .withServices(Service.S3)
    }

    private lateinit var objectStore: S3ObjectStore
    private lateinit var service: ClickHouseBackupService

    @BeforeAll
    fun setupClients() {
        val credentials =
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(localStack.accessKey, localStack.secretKey),
            )

        val s3Client =
            S3Client
                .builder()
                .endpointOverride(localStack.getEndpointOverride(Service.S3))
                .region(Region.of(localStack.region))
                .credentialsProvider(credentials)
                .forcePathStyle(true)
                .build()

        s3Client.createBucket(CreateBucketRequest.builder().bucket(TEST_BUCKET).build())
        s3Client.createBucket(CreateBucketRequest.builder().bucket(EMPTY_BUCKET).build())
        objectStore = S3ObjectStore(s3Client, EventBus())
    }

    @BeforeEach
    fun setup() {
        service = DefaultClickHouseBackupService(mock(), objectStore, EventBus())
    }

    private fun uploadMetadata(
        backupName: String,
        timestamp: String,
        sourceCluster: String,
        sizeBytes: Long,
    ) {
        val metadata = BackupMetadata(backupName, timestamp, sourceCluster, sizeBytes)
        val path =
            clickhouseBackupsRoot(TEST_BUCKET)
                .resolve(backupName)
                .resolve("backup-metadata.json")
        objectStore.uploadContent(Json.encodeToString(metadata), path)
    }

    @Test
    fun `listBackups returns empty when no backups exist`() {
        val result = service.listBackups(EMPTY_BUCKET)
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow()).isEmpty()
    }

    @Test
    fun `listBackups returns backups sorted newest first`() {
        uploadMetadata("backup-old", "2024-01-01T10:00:00Z", "cluster-a", 1000L)
        uploadMetadata("backup-new", "2024-06-01T10:00:00Z", "cluster-a", 2000L)

        val result = service.listBackups(TEST_BUCKET)

        assertThat(result.isSuccess).isTrue()
        val backups = result.getOrThrow()
        assertThat(backups.map { it.backupName }).startsWith("backup-new")
        assertThat(backups.map { it.backupName }).contains("backup-old")
        assertThat(backups[0].timestamp).isEqualTo("2024-06-01T10:00:00Z")
    }

    @Test
    fun `listBackups returns correct metadata fields`() {
        uploadMetadata("my-backup", "2024-03-15T12:00:00Z", "prod-cluster", 5000L)

        val result = service.listBackups(TEST_BUCKET)

        assertThat(result.isSuccess).isTrue()
        val backup = result.getOrThrow().first { it.backupName == "my-backup" }
        assertThat(backup.sourceCluster).isEqualTo("prod-cluster")
        assertThat(backup.totalSizeBytes).isEqualTo(5000L)
        assertThat(backup.timestamp).isEqualTo("2024-03-15T12:00:00Z")
    }
}
