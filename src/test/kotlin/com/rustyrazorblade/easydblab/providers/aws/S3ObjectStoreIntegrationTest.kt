package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
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
import java.io.File
import java.nio.file.Path

/**
 * Integration tests for S3ObjectStore using LocalStack.
 *
 * Tests verify that S3 operations (upload, download, list, delete, exists, metadata)
 * work correctly through real AWS SDK API calls against a LocalStack S3 endpoint.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ObjectStoreIntegrationTest {
    companion object {
        private const val TEST_BUCKET = "test-bucket"

        @Container
        @JvmStatic
        val localStack: LocalStackContainer =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                .withServices(Service.S3)
    }

    private lateinit var s3Client: S3Client
    private lateinit var outputHandler: BufferedOutputHandler
    private lateinit var objectStore: S3ObjectStore

    @BeforeAll
    fun setupClients() {
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

        s3Client.createBucket(CreateBucketRequest.builder().bucket(TEST_BUCKET).build())
    }

    @BeforeEach
    fun setup() {
        outputHandler = BufferedOutputHandler()
        objectStore = S3ObjectStore(s3Client, outputHandler)
    }

    @Nested
    inner class UploadFile {
        @Test
        fun `should upload file and return correct result`(
            @TempDir tempDir: Path,
        ) {
            val testFile = tempDir.resolve("upload-test.txt").toFile()
            testFile.writeText("hello world")
            val s3Path = ClusterS3Path.root(TEST_BUCKET).resolve("upload/upload-test.txt")

            val result = objectStore.uploadFile(testFile, s3Path, showProgress = false)

            assertThat(result.remotePath).isEqualTo(s3Path)
            assertThat(result.fileSize).isEqualTo(testFile.length())
            assertThat(objectStore.fileExists(s3Path)).isTrue()
        }

        @Test
        fun `should throw when file does not exist`() {
            val nonexistent = File("/nonexistent/file.txt")
            val s3Path = ClusterS3Path.root(TEST_BUCKET).resolve("upload/nonexistent.txt")

            assertThatThrownBy {
                objectStore.uploadFile(nonexistent, s3Path)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("does not exist")
        }
    }

    @Nested
    inner class DownloadFile {
        @Test
        fun `should download file with correct content`(
            @TempDir tempDir: Path,
        ) {
            // Upload first
            val sourceFile = tempDir.resolve("source.txt").toFile()
            sourceFile.writeText("download content")
            val s3Path = ClusterS3Path.root(TEST_BUCKET).resolve("download/source.txt")
            objectStore.uploadFile(sourceFile, s3Path, showProgress = false)

            // Download
            val downloadPath = tempDir.resolve("downloaded.txt")
            val result = objectStore.downloadFile(s3Path, downloadPath, showProgress = false)

            assertThat(result.localPath).isEqualTo(downloadPath)
            assertThat(result.fileSize).isEqualTo(sourceFile.length())
            assertThat(downloadPath.toFile().readText()).isEqualTo("download content")
        }
    }

    @Nested
    inner class FileExists {
        @Test
        fun `should return true when file exists`(
            @TempDir tempDir: Path,
        ) {
            val testFile = tempDir.resolve("exists.txt").toFile()
            testFile.writeText("exists")
            val s3Path = ClusterS3Path.root(TEST_BUCKET).resolve("exists/exists.txt")
            objectStore.uploadFile(testFile, s3Path, showProgress = false)

            assertThat(objectStore.fileExists(s3Path)).isTrue()
        }

        @Test
        fun `should return false when file does not exist`() {
            val s3Path = ClusterS3Path.root(TEST_BUCKET).resolve("exists/nonexistent.txt")

            assertThat(objectStore.fileExists(s3Path)).isFalse()
        }
    }

    @Nested
    inner class GetFileInfo {
        @Test
        fun `should return metadata for existing file`(
            @TempDir tempDir: Path,
        ) {
            val testFile = tempDir.resolve("info.txt").toFile()
            testFile.writeText("file info content")
            val s3Path = ClusterS3Path.root(TEST_BUCKET).resolve("info/info.txt")
            objectStore.uploadFile(testFile, s3Path, showProgress = false)

            val fileInfo = objectStore.getFileInfo(s3Path)

            assertThat(fileInfo).isNotNull
            assertThat(fileInfo!!.path).isEqualTo(s3Path)
            assertThat(fileInfo.size).isEqualTo(testFile.length())
            assertThat(fileInfo.lastModified).isNotEmpty()
        }

        @Test
        fun `should return null when file does not exist`() {
            val s3Path = ClusterS3Path.root(TEST_BUCKET).resolve("info/nonexistent.txt")

            assertThat(objectStore.getFileInfo(s3Path)).isNull()
        }
    }

    @Nested
    inner class ListFiles {
        @Test
        fun `should list uploaded files`(
            @TempDir tempDir: Path,
        ) {
            val file1 = tempDir.resolve("list1.txt").toFile()
            file1.writeText("content1")
            val file2 = tempDir.resolve("list2.txt").toFile()
            file2.writeText("content two")
            val prefix = ClusterS3Path.root(TEST_BUCKET).resolve("listfiles")
            objectStore.uploadFile(file1, prefix.resolve("list1.txt"), showProgress = false)
            objectStore.uploadFile(file2, prefix.resolve("list2.txt"), showProgress = false)

            val files = objectStore.listFiles(prefix)

            assertThat(files).hasSize(2)
            assertThat(files.map { it.path.getFileName() }).containsExactlyInAnyOrder("list1.txt", "list2.txt")
            assertThat(files.map { it.size }).containsExactlyInAnyOrder(
                "content1".length.toLong(),
                "content two".length.toLong(),
            )
        }

        @Test
        fun `should return empty list when no files exist`() {
            val s3Path = ClusterS3Path.root(TEST_BUCKET).resolve("listfiles-empty")

            assertThat(objectStore.listFiles(s3Path)).isEmpty()
        }
    }

    @Nested
    inner class DeleteFile {
        @Test
        fun `should delete file and verify it is gone`(
            @TempDir tempDir: Path,
        ) {
            val testFile = tempDir.resolve("delete.txt").toFile()
            testFile.writeText("to delete")
            val s3Path = ClusterS3Path.root(TEST_BUCKET).resolve("delete/delete.txt")
            objectStore.uploadFile(testFile, s3Path, showProgress = false)
            assertThat(objectStore.fileExists(s3Path)).isTrue()

            objectStore.deleteFile(s3Path, showProgress = false)

            assertThat(objectStore.fileExists(s3Path)).isFalse()
        }
    }

    @Nested
    inner class DirectoryOperations {
        @Test
        fun `should upload and download directory`(
            @TempDir tempDir: Path,
        ) {
            // Create local directory structure
            val uploadDir = tempDir.resolve("upload-dir").toFile()
            uploadDir.mkdirs()
            File(uploadDir, "a.txt").writeText("file a")
            File(uploadDir, "b.txt").writeText("file b content")
            val subDir = File(uploadDir, "sub")
            subDir.mkdirs()
            File(subDir, "c.txt").writeText("file c in subdir")

            val remotePath = ClusterS3Path.root(TEST_BUCKET).resolve("dir-test")

            // Upload directory
            val uploadResult = objectStore.uploadDirectory(uploadDir.toPath(), remotePath, showProgress = false)
            assertThat(uploadResult.filesUploaded).isEqualTo(3)

            // Download to a different local directory
            val downloadDir = tempDir.resolve("download-dir")
            val downloadResult = objectStore.downloadDirectory(remotePath, downloadDir, showProgress = false)

            assertThat(downloadResult.filesDownloaded).isEqualTo(3)
            assertThat(downloadDir.resolve("a.txt").toFile().readText()).isEqualTo("file a")
            assertThat(downloadDir.resolve("b.txt").toFile().readText()).isEqualTo("file b content")
            assertThat(downloadDir.resolve("sub/c.txt").toFile().readText()).isEqualTo("file c in subdir")
        }

        @Test
        fun `directoryExists should return true when files exist under prefix`(
            @TempDir tempDir: Path,
        ) {
            val testFile = tempDir.resolve("dircheck.txt").toFile()
            testFile.writeText("exists")
            val prefix = ClusterS3Path.root(TEST_BUCKET).resolve("direxists")
            objectStore.uploadFile(testFile, prefix.resolve("dircheck.txt"), showProgress = false)

            assertThat(objectStore.directoryExists(prefix)).isTrue()
        }

        @Test
        fun `directoryExists should return false when no files under prefix`() {
            val prefix = ClusterS3Path.root(TEST_BUCKET).resolve("direxists-empty")

            assertThat(objectStore.directoryExists(prefix)).isFalse()
        }
    }
}
