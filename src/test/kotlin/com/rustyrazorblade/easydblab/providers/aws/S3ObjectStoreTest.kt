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
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.S3Object
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.time.Instant

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
    fun `uploadFile uploads local file to S3`(
        @TempDir tempDir: Path,
    ) {
        // Given
        val testFile = tempDir.resolve("test.jar").toFile()
        testFile.writeText("test content")
        val s3Path = ClusterS3Path.root("test-bucket").spark().resolve("test.jar")

        // Initialize ObjectStore (triggers mock creation)
        objectStore = get()

        whenever(mockS3Client.putObject(any<PutObjectRequest>(), any<Path>()))
            .thenReturn(PutObjectResponse.builder().build())

        // When
        val result = objectStore.uploadFile(testFile, s3Path, showProgress = false)

        // Then
        assertThat(result.remotePath).isEqualTo(s3Path)
        assertThat(result.fileSize).isEqualTo(testFile.length())
        verify(mockS3Client).putObject(any<PutObjectRequest>(), any<Path>())
    }

    @Test
    fun `uploadFile throws exception when file does not exist`() {
        // Given
        val nonexistentFile = File("/nonexistent/file.jar")
        val s3Path = ClusterS3Path.root("test-bucket").spark().resolve("test.jar")

        // When/Then
        objectStore = get()
        assertThatThrownBy {
            objectStore.uploadFile(nonexistentFile, s3Path)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("does not exist")
    }

    @Test
    fun `downloadFile downloads from S3 to local path`(
        @TempDir tempDir: Path,
    ) {
        // Given
        val s3Path = ClusterS3Path.root("test-bucket").spark().resolve("test.jar")
        val localPath = tempDir.resolve("downloaded.jar")
        val testContent = "test content".toByteArray()

        // Initialize ObjectStore (triggers mock creation)
        objectStore = get()

        whenever(mockS3Client.getObject(any<GetObjectRequest>(), any<Path>()))
            .thenAnswer { invocation ->
                val path = invocation.getArgument<Path>(1)
                path.toFile().writeBytes(testContent)
                GetObjectResponse.builder().build()
            }

        // When
        val result = objectStore.downloadFile(s3Path, localPath, showProgress = false)

        // Then
        assertThat(result.localPath).isEqualTo(localPath)
        assertThat(result.fileSize).isEqualTo(testContent.size.toLong())
        assertThat(localPath.toFile()).exists()
        verify(mockS3Client).getObject(any<GetObjectRequest>(), any<Path>())
    }

    @Test
    fun `fileExists returns true when file exists in S3`() {
        // Given
        val s3Path = ClusterS3Path.root("test-bucket").spark().resolve("test.jar")

        // Initialize ObjectStore (triggers mock creation)
        objectStore = get()

        whenever(mockS3Client.headObject(any<HeadObjectRequest>()))
            .thenReturn(
                HeadObjectResponse
                    .builder()
                    .contentLength(1024L)
                    .lastModified(Instant.now())
                    .build(),
            )

        // When
        val exists = objectStore.fileExists(s3Path)

        // Then
        assertThat(exists).isTrue()
    }

    @Test
    fun `fileExists returns false when file does not exist in S3`() {
        // Given
        val s3Path = ClusterS3Path.root("test-bucket").spark().resolve("test.jar")

        // Initialize ObjectStore (triggers mock creation)
        objectStore = get()

        whenever(mockS3Client.headObject(any<HeadObjectRequest>()))
            .thenThrow(NoSuchKeyException.builder().build())

        // When
        val exists = objectStore.fileExists(s3Path)

        // Then
        assertThat(exists).isFalse()
    }

    @Test
    fun `getFileInfo returns metadata when file exists`() {
        // Given
        val s3Path = ClusterS3Path.root("test-bucket").spark().resolve("test.jar")
        val lastModified = Instant.now()

        // Initialize ObjectStore (triggers mock creation)
        objectStore = get()

        whenever(mockS3Client.headObject(any<HeadObjectRequest>()))
            .thenReturn(
                HeadObjectResponse
                    .builder()
                    .contentLength(2048L)
                    .lastModified(lastModified)
                    .build(),
            )

        // When
        val fileInfo = objectStore.getFileInfo(s3Path)

        // Then
        assertThat(fileInfo).isNotNull
        assertThat(fileInfo!!.path).isEqualTo(s3Path)
        assertThat(fileInfo.size).isEqualTo(2048L)
        assertThat(fileInfo.lastModified).isEqualTo(lastModified.toString())
    }

    @Test
    fun `getFileInfo returns null when file does not exist`() {
        // Given
        val s3Path = ClusterS3Path.root("test-bucket").spark().resolve("test.jar")

        // Initialize ObjectStore (triggers mock creation)
        objectStore = get()

        whenever(mockS3Client.headObject(any<HeadObjectRequest>()))
            .thenThrow(NoSuchKeyException.builder().build())

        // When
        val fileInfo = objectStore.getFileInfo(s3Path)

        // Then
        assertThat(fileInfo).isNull()
    }

    @Test
    fun `listFiles returns files under prefix`() {
        // Given
        val s3Path = ClusterS3Path.root("test-bucket").spark()
        val lastModified = Instant.now()

        // Initialize ObjectStore (triggers mock creation)
        objectStore = get()

        val s3Object1 =
            S3Object
                .builder()
                .key("spark/app1.jar")
                .size(1024L)
                .lastModified(lastModified)
                .build()

        val s3Object2 =
            S3Object
                .builder()
                .key("spark/app2.jar")
                .size(2048L)
                .lastModified(lastModified)
                .build()

        val response =
            ListObjectsV2Response
                .builder()
                .contents(s3Object1, s3Object2)
                .build()

        val mockPaginator = mock<ListObjectsV2Iterable>()
        whenever(mockPaginator.iterator()).thenReturn(mutableListOf(response).iterator())
        whenever(mockS3Client.listObjectsV2Paginator(any<ListObjectsV2Request>()))
            .thenReturn(mockPaginator)

        // When
        val files = objectStore.listFiles(s3Path)

        // Then
        assertThat(files).hasSize(2)
        assertThat(files[0].size).isEqualTo(1024L)
        assertThat(files[1].size).isEqualTo(2048L)
    }

    @Test
    fun `deleteFile removes file from S3`() {
        // Given
        val s3Path = ClusterS3Path.root("test-bucket").spark().resolve("test.jar")

        // Initialize ObjectStore (triggers mock creation)
        objectStore = get()

        whenever(mockS3Client.deleteObject(any<DeleteObjectRequest>()))
            .thenReturn(DeleteObjectResponse.builder().build())

        // When
        objectStore.deleteFile(s3Path, showProgress = false)

        // Then
        verify(mockS3Client).deleteObject(any<DeleteObjectRequest>())
    }

    // ========== EMPTY RESULTS TESTS ==========

    @Test
    fun `listFiles returns empty list when no files exist`() {
        // Given
        val s3Path = ClusterS3Path.root("test-bucket").spark()

        // Initialize ObjectStore (triggers mock creation)
        objectStore = get()

        val response =
            ListObjectsV2Response
                .builder()
                .contents(emptyList())
                .build()

        val mockPaginator = mock<ListObjectsV2Iterable>()
        whenever(mockPaginator.iterator()).thenReturn(mutableListOf(response).iterator())
        whenever(mockS3Client.listObjectsV2Paginator(any<ListObjectsV2Request>()))
            .thenReturn(mockPaginator)

        // When
        val files = objectStore.listFiles(s3Path)

        // Then
        assertThat(files).isEmpty()
    }

    @Test
    fun `listFiles returns empty list when contents is null`() {
        // Given
        val s3Path = ClusterS3Path.root("test-bucket").spark()

        // Initialize ObjectStore (triggers mock creation)
        objectStore = get()

        val response =
            ListObjectsV2Response
                .builder()
                .build() // No contents() set, will return null

        val mockPaginator = mock<ListObjectsV2Iterable>()
        whenever(mockPaginator.iterator()).thenReturn(mutableListOf(response).iterator())
        whenever(mockS3Client.listObjectsV2Paginator(any<ListObjectsV2Request>()))
            .thenReturn(mockPaginator)

        // When
        val files = objectStore.listFiles(s3Path)

        // Then
        assertThat(files).isEmpty()
    }

    // ========== S3 ERROR HANDLING TESTS ==========

    @Test
    fun `uploadFile throws exception on S3 error`(
        @TempDir tempDir: Path,
    ) {
        // Given
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

        // When/Then
        assertThatThrownBy {
            objectStore.uploadFile(testFile, s3Path)
        }.isInstanceOf(S3Exception::class.java)
    }

    @Test
    fun `downloadFile throws exception on S3 error`(
        @TempDir tempDir: Path,
    ) {
        // Given
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

        // When/Then
        assertThatThrownBy {
            objectStore.downloadFile(s3Path, localPath)
        }.isInstanceOf(S3Exception::class.java)
    }

    @Test
    fun `fileExists throws exception on S3 server error`() {
        // Given
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

        // When/Then
        assertThatThrownBy {
            objectStore.fileExists(s3Path)
        }.isInstanceOf(S3Exception::class.java)
    }

    @Test
    fun `getFileInfo throws exception on S3 server error`() {
        // Given
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

        // When/Then
        assertThatThrownBy {
            objectStore.getFileInfo(s3Path)
        }.isInstanceOf(S3Exception::class.java)
    }

    @Test
    fun `deleteFile throws exception on S3 error`() {
        // Given
        val s3Path = ClusterS3Path.root("test-bucket").spark().resolve("test.jar")

        objectStore = get()

        whenever(mockS3Client.deleteObject(any<DeleteObjectRequest>()))
            .thenThrow(
                S3Exception
                    .builder()
                    .message("Access Denied")
                    .statusCode(403)
                    .build(),
            )

        // When/Then
        assertThatThrownBy {
            objectStore.deleteFile(s3Path)
        }.isInstanceOf(S3Exception::class.java)
    }

    // ========== FILE ALREADY EXISTS TESTS ==========

    @Test
    fun `downloadFile returns result when file already exists`(
        @TempDir tempDir: Path,
    ) {
        // Given
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

        // When
        val result = objectStore.downloadFile(s3Path, localPath, showProgress = false)

        // Then
        assertThat(result.localPath).isEqualTo(localPath)
        assertThat(result.fileSize).isEqualTo(existingContent.length.toLong())
    }

    @Test
    fun `downloadFile rethrows SdkClientException without FileAlreadyExistsException`(
        @TempDir tempDir: Path,
    ) {
        // Given
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

        // When/Then
        assertThatThrownBy {
            objectStore.downloadFile(s3Path, localPath)
        }.isInstanceOf(SdkClientException::class.java)
    }
}
