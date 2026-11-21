package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStoragePath
import com.rustyrazorblade.easydblab.providers.aws.RegionalS3Presigners
import com.rustyrazorblade.easydblab.services.ObjectStore
import com.rustyrazorblade.easydblab.services.aws.S3ObjectStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest
import software.amazon.awssdk.services.s3.model.GetBucketLocationResponse
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI
import java.nio.file.Path
import java.time.Duration

/**
 * Unit tests for S3ObjectStore error handling behavior that can't be tested with LocalStack.
 *
 * For core CRUD operations, see S3ObjectStoreIntegrationTest which uses LocalStack.
 */
class S3ObjectStoreTest : BaseKoinTest() {
    private lateinit var mockS3Client: S3Client
    private lateinit var objectStore: ObjectStore

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<S3Client>().also {
                        mockS3Client = it
                    }
                }
                single<ObjectStore> {
                    S3ObjectStore(
                        get(),
                        get(),
                        RegionalS3Presigners(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "secret"))),
                    )
                }
            },
        )

    @Test
    fun `uploadFile throws exception on S3 error`(
        @TempDir tempDir: Path,
    ) {
        val testFile = tempDir.resolve("test.jar").toFile()
        testFile.writeText("test content")
        val s3Path = ClusterStoragePath.root("test-bucket").spark().resolve("test.jar")

        objectStore = get()

        whenever(mockS3Client.putObject(any<PutObjectRequest>(), any<Path>()))
            .thenThrow(
                S3Exception
                    .builder()
                    .message("Access Denied")
                    .statusCode(403)
                    .build(),
            )

        assertThatThrownBy {
            objectStore.uploadFile(testFile, s3Path)
        }.isInstanceOf(S3Exception::class.java)
    }

    @Test
    fun `fileExists throws exception on S3 server error`() {
        val s3Path = ClusterStoragePath.root("test-bucket").spark().resolve("test.jar")

        objectStore = get()

        whenever(mockS3Client.headObject(any<HeadObjectRequest>()))
            .thenThrow(
                S3Exception
                    .builder()
                    .message("Service Unavailable")
                    .statusCode(503)
                    .build(),
            )

        assertThatThrownBy {
            objectStore.fileExists(s3Path)
        }.isInstanceOf(S3Exception::class.java)
    }

    @Test
    fun `getFileInfo throws exception on S3 server error`() {
        val s3Path = ClusterStoragePath.root("test-bucket").spark().resolve("test.jar")

        objectStore = get()

        whenever(mockS3Client.headObject(any<HeadObjectRequest>()))
            .thenThrow(
                S3Exception
                    .builder()
                    .message("Internal Server Error")
                    .statusCode(500)
                    .build(),
            )

        assertThatThrownBy {
            objectStore.getFileInfo(s3Path)
        }.isInstanceOf(S3Exception::class.java)
    }

    @Test
    fun `downloadFile rethrows SdkClientException`(
        @TempDir tempDir: Path,
    ) {
        val s3Path = ClusterStoragePath.root("test-bucket").spark().resolve("test.jar")
        val localPath = tempDir.resolve("downloaded.jar")

        objectStore = get()

        val sdkException =
            SdkClientException
                .builder()
                .message("Some other error")
                .build()

        whenever(mockS3Client.getObject(any<GetObjectRequest>(), any<Path>()))
            .thenThrow(sdkException)

        assertThatThrownBy {
            objectStore.downloadFile(s3Path, localPath)
        }.isInstanceOf(SdkClientException::class.java)
    }

    @Test
    fun `downloadFile throws exception on S3 error`(
        @TempDir tempDir: Path,
    ) {
        val s3Path = ClusterStoragePath.root("test-bucket").spark().resolve("test.jar")
        val localPath = tempDir.resolve("downloaded.jar")

        objectStore = get()

        whenever(mockS3Client.getObject(any<GetObjectRequest>(), any<Path>()))
            .thenThrow(
                S3Exception
                    .builder()
                    .message("Internal Server Error")
                    .statusCode(500)
                    .build(),
            )

        assertThatThrownBy {
            objectStore.downloadFile(s3Path, localPath)
        }.isInstanceOf(S3Exception::class.java)
    }

    @Test
    fun `presignedGetUrl signs a GET for the object in the bucket's own region, valid for the TTL`() {
        objectStore = get()
        whenever(mockS3Client.getBucketLocation(any<GetBucketLocationRequest>()))
            .thenReturn(GetBucketLocationResponse.builder().locationConstraint("eu-west-1").build())
        val path = ClusterStoragePath.cassandraBuildsRoot("acct-bucket").resolve("my-build").resolve("apache-cassandra-my-build-bin.tar.gz")

        val url = URI(objectStore.presignedGetUrl(path, Duration.ofHours(2)))

        assertThat(url.scheme).isEqualTo("https")
        assertThat(url.host).contains("acct-bucket").contains("eu-west-1")
        assertThat(url.path).endsWith("/${path.getKey()}")
        assertThat(url.query).contains("X-Amz-Expires=7200").contains("X-Amz-Signature=").contains("/eu-west-1/s3/")
    }

    @Test
    fun `presignedGetUrl refuses a TTL longer than S3 allows`() {
        objectStore = get()
        val path = ClusterStoragePath.root("acct-bucket").resolve("x.tgz")

        assertThatThrownBy { objectStore.presignedGetUrl(path, Duration.ofDays(8)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("7 days")
    }
}
