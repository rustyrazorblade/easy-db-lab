package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterStoragePath
import java.io.File
import java.nio.file.Path

/**
 * Cloud-agnostic object storage interface for easy-db-lab.
 *
 * This interface abstracts cloud storage operations so a second provider is a new adapter, not a
 * change to its callers. Two implementations exist: `services/aws/S3ObjectStore` and
 * [InMemoryObjectStore] (tests and local work). Another provider (GCS, Azure Blob, a volume) adds
 * a [com.rustyrazorblade.easydblab.configuration.store.StoreBackend] case and an adapter.
 *
 * All operations take a [ClusterStoragePath], which is provider-neutral: a bucket and a key under
 * the store prefix, with no scheme. Only an adapter knows how its provider spells a location;
 * callers that show one to the user ask [uriOf].
 *
 * Implementations should:
 * - Use retry logic for transient failures (network issues, throttling)
 * - Provide user feedback via OutputHandler when showProgress is true
 * - Handle provider-specific exceptions and convert to appropriate types
 * - Support cluster-specific namespacing through ClusterStoragePath
 *
 * Example usage:
 * ```kotlin
 * val objectStore: ObjectStore by inject()
 * val jarPath = ClusterStoragePath.from(clusterState).spark().resolve("myapp.jar")
 * val result = objectStore.uploadFile(localJar, jarPath, showProgress = true)
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
        remotePath: ClusterStoragePath,
        showProgress: Boolean = true,
    ): UploadResult

    /**
     * Downloads a file from cloud storage to local filesystem with retry logic.
     *
     * A file already at [localPath] is replaced with the object's current content.
     *
     * @param remotePath The cloud storage path to download from
     * @param localPath The local path to save to
     * @param showProgress If true, displays download progress to user via OutputHandler
     * @return DownloadResult with local path and file size
     * @throws Exception if remote file doesn't exist or download fails (provider-specific)
     */
    fun downloadFile(
        remotePath: ClusterStoragePath,
        localPath: Path,
        showProgress: Boolean = true,
    ): DownloadResult

    /**
     * Lists files in a cloud storage directory (prefix).
     *
     * @param remotePath The cloud storage path to list (acts as prefix)
     * @param recursive If true, lists all objects under prefix; if false, only immediate children
     * @param startAfter When not empty, only keys that sort after this full key are listed. Keys
     *   are listed in order, so the listing skips everything before it.
     * @return List of FileInfo for files under the prefix
     */
    fun listFiles(
        remotePath: ClusterStoragePath,
        recursive: Boolean = true,
        startAfter: String = "",
    ): List<FileInfo>

    /**
     * Deletes a file from cloud storage.
     *
     * @param remotePath The cloud storage path to delete
     * @param showProgress If true, displays deletion progress to user via OutputHandler
     * @throws Exception if deletion fails (provider-specific)
     */
    fun deleteFile(
        remotePath: ClusterStoragePath,
        showProgress: Boolean = true,
    )

    /**
     * Checks if a file exists in cloud storage.
     *
     * @param remotePath The cloud storage path to check
     * @return true if the file exists, false otherwise
     */
    fun fileExists(remotePath: ClusterStoragePath): Boolean

    /**
     * Gets file metadata from cloud storage without downloading.
     *
     * @param remotePath The cloud storage path to get metadata for
     * @return FileInfo with file metadata, or null if file doesn't exist
     */
    fun getFileInfo(remotePath: ClusterStoragePath): FileInfo?

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
        remotePath: ClusterStoragePath,
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
        remotePath: ClusterStoragePath,
        showProgress: Boolean = true,
    ): UploadDirectoryResult

    /**
     * Uploads string content to cloud storage.
     *
     * @param content The string content to upload
     * @param remotePath The target cloud storage path
     */
    fun uploadContent(
        content: String,
        remotePath: ClusterStoragePath,
    )

    /**
     * Reads the content of a cloud storage file as a UTF-8 string.
     *
     * @param remotePath The cloud storage path to read from
     * @return The file content as a string
     * @throws Exception if the file doesn't exist or read fails (provider-specific)
     */
    fun readContent(remotePath: ClusterStoragePath): String

    /**
     * Checks if any files exist under a cloud storage prefix.
     *
     * @param remotePath The cloud storage path prefix to check
     * @return true if at least one file exists under the prefix, false otherwise
     */
    fun directoryExists(remotePath: ClusterStoragePath): Boolean

    /**
     * The location of [path] as this store spells it, for showing to the user: `s3://bucket/key`
     * on S3. Every event and message that names a stored location renders it through here, so the
     * text depends on the store that holds the object rather than on the path type.
     */
    fun uriOf(path: ClusterStoragePath): String

    /**
     * Represents a file in cloud storage with metadata.
     *
     * @property path The cloud storage path to this file
     * @property size File size in bytes
     * @property lastModified Last modification timestamp (ISO-8601 format)
     */
    data class FileInfo(
        val path: ClusterStoragePath,
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
        val remotePath: ClusterStoragePath,
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
        val remotePath: ClusterStoragePath,
        val filesUploaded: Int,
        val totalBytes: Long,
    )
}
