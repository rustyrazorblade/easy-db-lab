package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import java.io.File
import java.nio.file.Path

/**
 * Cloud-agnostic object storage interface for easy-db-lab.
 *
 * This interface abstracts cloud storage operations to enable multi-cloud support.
 * Implementations exist for AWS S3, and future implementations can support:
 * - Google Cloud Storage (GCP)
 * - Azure Blob Storage
 * - Local filesystem (for testing/development)
 *
 * All operations use ClusterS3Path for type-safe path handling and proper cluster
 * namespacing. Note: ClusterS3Path will be renamed to ClusterStoragePath in a
 * future refactoring to be truly cloud-agnostic.
 *
 * Implementations should:
 * - Use retry logic for transient failures (network issues, throttling)
 * - Provide user feedback via OutputHandler when showProgress is true
 * - Handle provider-specific exceptions and convert to appropriate types
 * - Support cluster-specific namespacing through ClusterS3Path
 *
 * Example usage:
 * ```kotlin
 * val objectStore: ObjectStore by inject()
 * val s3Path = clusterState.s3Path(userConfig).sparkJars().resolve("myapp.jar")
 * val result = objectStore.uploadFile(localJar, s3Path, showProgress = true)
 * ```
 */
interface ObjectStore {
    /**
     * Uploads a local file to cloud storage with retry logic.
     *
     * @param localFile The local file to upload
     * @param remotePath The target cloud storage path (should include filename)
     * @param showProgress If true, displays upload progress to user via OutputHandler
     * @return UploadResult with remote path and file size
     * @throws IllegalArgumentException if local file doesn't exist
     * @throws Exception if upload fails after retries (provider-specific)
     */
    fun uploadFile(
        localFile: File,
        remotePath: ClusterS3Path,
        showProgress: Boolean = true,
    ): UploadResult

    /**
     * Downloads a file from cloud storage to local filesystem with retry logic.
     *
     * @param remotePath The cloud storage path to download from
     * @param localPath The local path to save to
     * @param showProgress If true, displays download progress to user via OutputHandler
     * @return DownloadResult with local path and file size
     * @throws Exception if remote file doesn't exist or download fails (provider-specific)
     */
    fun downloadFile(
        remotePath: ClusterS3Path,
        localPath: Path,
        showProgress: Boolean = true,
    ): DownloadResult

    /**
     * Lists files in a cloud storage directory (prefix).
     *
     * @param remotePath The cloud storage path to list (acts as prefix)
     * @param recursive If true, lists all objects under prefix; if false, only immediate children
     * @return List of FileInfo for files under the prefix
     */
    fun listFiles(
        remotePath: ClusterS3Path,
        recursive: Boolean = true,
    ): List<FileInfo>

    /**
     * Deletes a file from cloud storage.
     *
     * @param remotePath The cloud storage path to delete
     * @param showProgress If true, displays deletion progress to user via OutputHandler
     * @throws Exception if deletion fails (provider-specific)
     */
    fun deleteFile(
        remotePath: ClusterS3Path,
        showProgress: Boolean = true,
    )

    /**
     * Checks if a file exists in cloud storage.
     *
     * @param remotePath The cloud storage path to check
     * @return true if the file exists, false otherwise
     */
    fun fileExists(remotePath: ClusterS3Path): Boolean

    /**
     * Gets file metadata from cloud storage without downloading.
     *
     * @param remotePath The cloud storage path to get metadata for
     * @return FileInfo with file metadata, or null if file doesn't exist
     */
    fun getFileInfo(remotePath: ClusterS3Path): FileInfo?

    /**
     * Downloads all files under a prefix to a local directory.
     * Preserves directory structure relative to the prefix.
     *
     * @param remotePath The cloud storage path prefix to download from
     * @param localDir The local directory to save files to
     * @param showProgress If true, displays progress to user via OutputHandler
     * @return DownloadDirectoryResult with stats about the download
     */
    fun downloadDirectory(
        remotePath: ClusterS3Path,
        localDir: Path,
        showProgress: Boolean = true,
    ): DownloadDirectoryResult

    /**
     * Uploads all files from a local directory to cloud storage.
     * Preserves directory structure relative to the local directory.
     *
     * @param localDir The local directory to upload from
     * @param remotePath The cloud storage path prefix to upload to
     * @param showProgress If true, displays progress to user via OutputHandler
     * @return UploadDirectoryResult with stats about the upload
     */
    fun uploadDirectory(
        localDir: Path,
        remotePath: ClusterS3Path,
        showProgress: Boolean = true,
    ): UploadDirectoryResult

    /**
     * Checks if any files exist under a cloud storage prefix.
     *
     * @param remotePath The cloud storage path prefix to check
     * @return true if at least one file exists under the prefix, false otherwise
     */
    fun directoryExists(remotePath: ClusterS3Path): Boolean

    /**
     * Represents a file in cloud storage with metadata.
     *
     * @property path The cloud storage path to this file
     * @property size File size in bytes
     * @property lastModified Last modification timestamp (ISO-8601 format)
     */
    data class FileInfo(
        val path: ClusterS3Path,
        val size: Long,
        val lastModified: String,
    )

    /**
     * Result of an upload operation.
     *
     * @property remotePath The cloud storage path where the file was uploaded
     * @property fileSize Size of the uploaded file in bytes
     */
    data class UploadResult(
        val remotePath: ClusterS3Path,
        val fileSize: Long,
    )

    /**
     * Result of a download operation.
     *
     * @property localPath Path to the downloaded file
     * @property fileSize Size of the downloaded file in bytes
     */
    data class DownloadResult(
        val localPath: Path,
        val fileSize: Long,
    )

    /**
     * Result of a directory download operation.
     *
     * @property localDir The local directory where files were downloaded
     * @property filesDownloaded Number of files downloaded
     * @property totalBytes Total bytes downloaded
     */
    data class DownloadDirectoryResult(
        val localDir: Path,
        val filesDownloaded: Int,
        val totalBytes: Long,
    )

    /**
     * Result of a directory upload operation.
     *
     * @property remotePath The cloud storage path where files were uploaded
     * @property filesUploaded Number of files uploaded
     * @property totalBytes Total bytes uploaded
     */
    data class UploadDirectoryResult(
        val remotePath: ClusterS3Path,
        val filesUploaded: Int,
        val totalBytes: Long,
    )
}
