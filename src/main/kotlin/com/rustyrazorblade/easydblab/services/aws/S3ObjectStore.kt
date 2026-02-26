package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import com.rustyrazorblade.easydblab.services.ObjectStore
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import com.rustyrazorblade.easydblab.events.Event as DomainEvent

/**
 * AWS S3 implementation of the ObjectStore interface.
 *
 * This implementation provides S3-specific object storage operations with:
 * - Automatic retry logic using RetryUtil (exponential backoff: 1s, 2s, 4s)
 * - User feedback via OutputHandler for long-running operations
 * - Type-safe path handling using ClusterS3Path
 * - Proper error handling for S3-specific exceptions
 *
 * All S3 operations use cluster-specific paths (clusters/{clusterId}/) to prevent
 * conflicts when multiple clusters share the same S3 bucket.
 *
 * @property s3Client AWS SDK S3 client for S3 operations
 */
class S3ObjectStore(
    private val s3Client: S3Client,
    private val eventBus: EventBus,
) : ObjectStore {
    private val log = KotlinLogging.logger {}

    override fun uploadFile(
        localFile: File,
        remotePath: ClusterS3Path,
        showProgress: Boolean,
    ): ObjectStore.UploadResult {
        require(localFile.exists()) {
            "Local file does not exist: ${localFile.absolutePath}"
        }

        if (showProgress) {
            eventBus.emit(DomainEvent.S3.Uploading(localFile.name, remotePath.toUri()))
        }

        val putRequest =
            PutObjectRequest
                .builder()
                .bucket(remotePath.bucket)
                .key(remotePath.getKey())
                .build()

        val retryConfig = RetryUtil.createAwsRetryConfig<Unit>()
        val retry = Retry.of("s3-upload", retryConfig)

        Retry
            .decorateRunnable(retry) {
                s3Client.putObject(putRequest, localFile.toPath())
                log.info { "Uploaded ${localFile.name} to ${remotePath.toUri()}" }
            }.run()

        if (showProgress) {
            eventBus.emit(DomainEvent.S3.UploadComplete(remotePath.toUri()))
        }

        return ObjectStore.UploadResult(
            remotePath = remotePath,
            fileSize = localFile.length(),
        )
    }

    override fun downloadFile(
        remotePath: ClusterS3Path,
        localPath: Path,
        showProgress: Boolean,
    ): ObjectStore.DownloadResult {
        if (showProgress) {
            eventBus.emit(DomainEvent.S3.Downloading(remotePath.toUri(), localPath.toString()))
        }

        val getRequest =
            GetObjectRequest
                .builder()
                .bucket(remotePath.bucket)
                .key(remotePath.getKey())
                .build()

        val retryConfig = RetryUtil.createAwsRetryConfig<Unit>()
        val retry = Retry.of("s3-download", retryConfig)

        try {
            Retry
                .decorateRunnable(retry) {
                    s3Client.getObject(getRequest, localPath)
                    log.info { "Downloaded ${remotePath.toUri()} to $localPath" }
                }.run()
        } catch (e: SdkClientException) {
            val hasFileExists =
                generateSequence(e as Throwable) { it.cause }
                    .any { it is FileAlreadyExistsException }
            if (hasFileExists) {
                log.info { "File already exists, skipping download: $localPath" }
                return ObjectStore.DownloadResult(
                    localPath = localPath,
                    fileSize = localPath.toFile().length(),
                )
            }
            throw e
        }

        if (showProgress) {
            eventBus.emit(DomainEvent.S3.DownloadComplete(localPath.toString()))
        }

        return ObjectStore.DownloadResult(
            localPath = localPath,
            fileSize = localPath.toFile().length(),
        )
    }

    override fun listFiles(
        remotePath: ClusterS3Path,
        recursive: Boolean,
    ): List<ObjectStore.FileInfo> {
        val listRequest =
            ListObjectsV2Request
                .builder()
                .bucket(remotePath.bucket)
                .prefix(remotePath.getKey())
                .apply {
                    if (!recursive) {
                        delimiter("/")
                    }
                }.build()

        val results = mutableListOf<ObjectStore.FileInfo>()

        // Use paginator to handle >1000 files (S3 default limit)
        s3Client.listObjectsV2Paginator(listRequest).forEach { response ->
            response.contents()?.forEach { s3Object ->
                results.add(
                    ObjectStore.FileInfo(
                        path = ClusterS3Path.fromKey(remotePath.bucket, s3Object.key()),
                        size = s3Object.size(),
                        lastModified = s3Object.lastModified().toString(),
                    ),
                )
            }
        }

        return results
    }

    override fun deleteFile(
        remotePath: ClusterS3Path,
        showProgress: Boolean,
    ) {
        if (showProgress) {
            eventBus.emit(DomainEvent.S3.Deleting(remotePath.toUri()))
        }

        val deleteRequest =
            DeleteObjectRequest
                .builder()
                .bucket(remotePath.bucket)
                .key(remotePath.getKey())
                .build()

        val retryConfig = RetryUtil.createAwsRetryConfig<Unit>()
        val retry = Retry.of("s3-delete", retryConfig)

        Retry
            .decorateRunnable(retry) {
                s3Client.deleteObject(deleteRequest)
                log.info { "Deleted ${remotePath.toUri()}" }
            }.run()

        if (showProgress) {
            eventBus.emit(DomainEvent.S3.Deleted(remotePath.toUri()))
        }
    }

    override fun fileExists(remotePath: ClusterS3Path): Boolean =
        try {
            s3Client.headObject(createHeadRequest(remotePath))
            true
        } catch (_: NoSuchKeyException) {
            false
        } catch (e: S3Exception) {
            log.warn { "S3 error checking file existence: ${e.message}" }
            throw e
        }

    override fun getFileInfo(remotePath: ClusterS3Path): ObjectStore.FileInfo? =
        try {
            val response = s3Client.headObject(createHeadRequest(remotePath))

            ObjectStore.FileInfo(
                path = remotePath,
                size = response.contentLength(),
                lastModified = response.lastModified().toString(),
            )
        } catch (_: NoSuchKeyException) {
            null
        } catch (e: S3Exception) {
            log.warn { "S3 error getting file info: ${e.message}" }
            throw e
        }

    override fun downloadDirectory(
        remotePath: ClusterS3Path,
        localDir: Path,
        showProgress: Boolean,
    ): ObjectStore.DownloadDirectoryResult {
        val files = listFiles(remotePath, recursive = true)

        if (showProgress) {
            eventBus.emit(DomainEvent.S3.DirectoryDownloadFound(files.size))
        }

        // Ensure base directory exists and use absolute path for reliable directory creation
        val absoluteLocalDir = localDir.toAbsolutePath()
        java.nio.file.Files
            .createDirectories(absoluteLocalDir)

        var totalBytes = 0L
        var filesDownloaded = 0
        val prefixLength = remotePath.getKey().length

        for (file in files) {
            // Calculate relative path from the prefix
            val relativePath =
                file.path
                    .getKey()
                    .substring(prefixLength)
                    .trimStart('/')

            // Skip empty relative paths (the prefix directory itself)
            if (relativePath.isEmpty()) continue

            val localFile = absoluteLocalDir.resolve(relativePath)

            // Create parent directories (handle null parent case)
            val parentDir = localFile.parent ?: absoluteLocalDir
            java.nio.file.Files
                .createDirectories(parentDir)

            // Download the file
            downloadFile(file.path, localFile, showProgress = false)
            totalBytes += file.size
            filesDownloaded++
        }

        if (showProgress) {
            eventBus.emit(DomainEvent.S3.DirectoryDownloadComplete(filesDownloaded, localDir.toString()))
        }

        return ObjectStore.DownloadDirectoryResult(localDir, filesDownloaded, totalBytes)
    }

    override fun uploadDirectory(
        localDir: Path,
        remotePath: ClusterS3Path,
        showProgress: Boolean,
    ): ObjectStore.UploadDirectoryResult {
        val localDirFile = localDir.toFile()
        require(localDirFile.exists() && localDirFile.isDirectory) {
            "Local directory does not exist or is not a directory: ${localDir.toAbsolutePath()}"
        }

        val files =
            localDirFile
                .walkTopDown()
                .filter { it.isFile }
                .toList()

        if (showProgress) {
            eventBus.emit(DomainEvent.S3.DirectoryUploadFound(files.size, remotePath.toUri()))
        }

        var totalBytes = 0L
        var filesUploaded = 0

        for (file in files) {
            // Calculate relative path from the local directory
            val relativePath = localDir.relativize(file.toPath()).toString()
            val targetPath = remotePath.resolve(relativePath)

            // Upload the file
            uploadFile(file, targetPath, showProgress = false)
            totalBytes += file.length()
            filesUploaded++
        }

        if (showProgress) {
            eventBus.emit(DomainEvent.S3.DirectoryUploadComplete(filesUploaded, remotePath.toUri()))
        }

        return ObjectStore.UploadDirectoryResult(remotePath, filesUploaded, totalBytes)
    }

    override fun uploadContent(
        content: String,
        remotePath: ClusterS3Path,
    ) {
        val putRequest =
            PutObjectRequest
                .builder()
                .bucket(remotePath.bucket)
                .key(remotePath.getKey())
                .contentType("text/plain")
                .build()

        val retryConfig = RetryUtil.createAwsRetryConfig<Unit>()
        val retry = Retry.of("s3-upload-content", retryConfig)

        Retry
            .decorateRunnable(retry) {
                val bytes = content.toByteArray()
                s3Client.putObject(
                    putRequest,
                    software.amazon.awssdk.core.sync.RequestBody
                        .fromBytes(bytes),
                )
                log.info { "Uploaded content (${bytes.size} bytes) to ${remotePath.toUri()}" }
            }.run()
    }

    override fun directoryExists(remotePath: ClusterS3Path): Boolean {
        // Use recursive=true to find any files under the prefix
        // With recursive=false, S3's delimiter "/" would roll up nested files
        // into CommonPrefixes instead of Contents, causing false negatives
        val files = listFiles(remotePath, recursive = true)
        return files.isNotEmpty()
    }

    /**
     * Creates a HeadObjectRequest for the given S3 path.
     *
     * @param remotePath The S3 path to check
     * @return A configured HeadObjectRequest
     */
    private fun createHeadRequest(remotePath: ClusterS3Path): HeadObjectRequest =
        HeadObjectRequest
            .builder()
            .bucket(remotePath.bucket)
            .key(remotePath.getKey())
            .build()
}
