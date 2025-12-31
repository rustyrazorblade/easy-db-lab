package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.ObjectStore
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.Event
import software.amazon.awssdk.services.s3.model.FilterRule
import software.amazon.awssdk.services.s3.model.FilterRuleName
import software.amazon.awssdk.services.s3.model.GetBucketNotificationConfigurationRequest
import software.amazon.awssdk.services.s3.model.GetBucketNotificationConfigurationResponse
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.NotificationConfiguration
import software.amazon.awssdk.services.s3.model.NotificationConfigurationFilter
import software.amazon.awssdk.services.s3.model.PutBucketNotificationConfigurationRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.QueueConfiguration
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.S3KeyFilter
import java.io.File
import java.nio.file.Path

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
 * @property outputHandler Handler for user-facing output messages
 */
class S3ObjectStore(
    private val s3Client: S3Client,
    private val outputHandler: OutputHandler,
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
            outputHandler.handleMessage("Uploading ${localFile.name} to ${remotePath.toUri()}...")
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
            outputHandler.handleMessage("Upload complete: ${remotePath.toUri()}")
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
            outputHandler.handleMessage("Downloading ${remotePath.toUri()} to $localPath...")
        }

        val getRequest =
            GetObjectRequest
                .builder()
                .bucket(remotePath.bucket)
                .key(remotePath.getKey())
                .build()

        val retryConfig = RetryUtil.createAwsRetryConfig<Unit>()
        val retry = Retry.of("s3-download", retryConfig)

        Retry
            .decorateRunnable(retry) {
                s3Client.getObject(getRequest, localPath)
                log.info { "Downloaded ${remotePath.toUri()} to $localPath" }
            }.run()

        if (showProgress) {
            outputHandler.handleMessage("Download complete: $localPath")
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
            outputHandler.handleMessage("Deleting ${remotePath.toUri()}...")
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
            outputHandler.handleMessage("Deleted: ${remotePath.toUri()}")
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
            outputHandler.handleMessage("Found ${files.size} files to download")
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
            outputHandler.handleMessage("Downloaded $filesDownloaded files to $localDir")
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
            outputHandler.handleMessage("Found ${files.size} files to upload to ${remotePath.toUri()}")
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
            outputHandler.handleMessage("Uploaded $filesUploaded files to ${remotePath.toUri()}")
        }

        return ObjectStore.UploadDirectoryResult(remotePath, filesUploaded, totalBytes)
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

    /**
     * Configures S3 bucket notifications to send EMR log events to an SQS queue.
     *
     * When objects are created in the spark/emr-logs/ prefix, S3 will send a notification
     * to the specified SQS queue. Vector can then poll the queue to ingest logs.
     *
     * @param bucket The S3 bucket name
     * @param queueArn The ARN of the SQS queue to receive notifications
     * @param prefix The S3 key prefix to monitor (default: Constants.EMR.S3_LOG_PREFIX)
     * @return Result indicating success or containing an exception on failure
     */
    fun configureEMRLogNotifications(
        bucket: String,
        queueArn: String,
        prefix: String = Constants.EMR.S3_LOG_PREFIX,
    ): Result<Unit> =
        runCatching {
            outputHandler.handleMessage("Configuring S3 bucket notifications for EMR logs...")

            val filterRule =
                FilterRule
                    .builder()
                    .name(FilterRuleName.PREFIX)
                    .value(prefix)
                    .build()

            val keyFilter =
                S3KeyFilter
                    .builder()
                    .filterRules(filterRule)
                    .build()

            val notificationFilter =
                NotificationConfigurationFilter
                    .builder()
                    .key(keyFilter)
                    .build()

            val queueConfig =
                QueueConfiguration
                    .builder()
                    .id("emr-logs-to-sqs")
                    .queueArn(queueArn)
                    .events(Event.S3_OBJECT_CREATED)
                    .filter(notificationFilter)
                    .build()

            val notificationConfig =
                NotificationConfiguration
                    .builder()
                    .queueConfigurations(queueConfig)
                    .build()

            val request =
                PutBucketNotificationConfigurationRequest
                    .builder()
                    .bucket(bucket)
                    .notificationConfiguration(notificationConfig)
                    .build()

            val retryConfig = RetryUtil.createAwsRetryConfig<Unit>()
            val retry = Retry.of("s3-put-notification", retryConfig)

            Retry
                .decorateRunnable(retry) {
                    s3Client.putBucketNotificationConfiguration(request)
                    log.info { "Configured S3 bucket notifications for $bucket with prefix $prefix" }
                }.run()

            outputHandler.handleMessage("S3 bucket notifications configured for EMR logs")
        }

    /**
     * Retrieves the current bucket notification configuration.
     *
     * Used to verify that S3 → SQS notifications are correctly configured
     * for the EMR log ingestion pipeline.
     *
     * @param bucket The S3 bucket name
     * @return The notification configuration for the bucket
     */
    fun getBucketNotificationConfiguration(bucket: String): GetBucketNotificationConfigurationResponse {
        val request =
            GetBucketNotificationConfigurationRequest
                .builder()
                .bucket(bucket)
                .build()
        return s3Client.getBucketNotificationConfiguration(request)
    }
}
