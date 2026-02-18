package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.services.ObjectStore
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
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path

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
                single<ObjectStore> { S3ObjectStore(get(), get()) }
            },
        )

    @Test
    fun `uploadFile throws exception on S3 error`(
        @TempDir tempDir: Path,
    ) {
        val testFile = tempDir.resolve("test.jar").toFile()
        testFile.writeText("test content")
        val s3Path = ClusterS3Path.root("test-bucket").spark().resolve("test.jar")

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
        val s3Path = ClusterS3Path.root("test-bucket").spark().resolve("test.jar")

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
        val s3Path = ClusterS3Path.root("test-bucket").spark().resolve("test.jar")

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
    fun `downloadFile returns result when file already exists`(
        @TempDir tempDir: Path,
    ) {
        val s3Path = ClusterS3Path.root("test-bucket").spark().resolve("test.jar")
        val localPath = tempDir.resolve("existing.jar")
        val existingContent = "existing content"
        localPath.toFile().writeText(existingContent)

        objectStore = get()

        val fileAlreadyExists = FileAlreadyExistsException(localPath.toString())
        val ioException = java.io.IOException("Failed to write", fileAlreadyExists)
        val sdkException = SdkClientException.builder().cause(ioException).build()

        whenever(mockS3Client.getObject(any<GetObjectRequest>(), any<Path>()))
            .thenThrow(sdkException)

        val result = objectStore.downloadFile(s3Path, localPath, showProgress = false)

        assertThat(result.localPath).isEqualTo(localPath)
        assertThat(result.fileSize).isEqualTo(existingContent.length.toLong())
    }

    @Test
    fun `downloadFile rethrows SdkClientException without FileAlreadyExistsException`(
        @TempDir tempDir: Path,
    ) {
        val s3Path = ClusterS3Path.root("test-bucket").spark().resolve("test.jar")
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
        val s3Path = ClusterS3Path.root("test-bucket").spark().resolve("test.jar")
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
}
