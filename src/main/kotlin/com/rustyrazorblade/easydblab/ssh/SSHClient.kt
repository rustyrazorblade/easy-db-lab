package com.rustyrazorblade.easydblab.ssh

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.scp.client.CloseableScpClient
import org.apache.sshd.scp.client.ScpClientCreator
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.ArrayDeque

/**
 * Main class for SSH operations
 * Acts as a facade for all SSH-related functionality
 *
 * This class provides a thread-safe wrapper around Apache SSHD's ClientSession,
 * enabling safe concurrent operations across multiple hosts while serializing
 * operations on individual hosts.
 *
 * Key features:
 * - Thread-safe SSH command execution
 * - File and directory upload/download with SCP
 * - Proper resource cleanup on close
 *
 * Thread-safety: All operations are synchronized via operationLock to prevent
 * concurrent access to the underlying Apache SSHD ClientSession, which is not
 * thread-safe. This allows multiple SSHClient instances (different hosts) to
 * operate in parallel while serializing operations on the same session.
 *
 * Resource management: The class caches SCP clients to ensure proper cleanup
 * when close() is called.
 *
 * @property session The underlying Apache SSHD ClientSession
 */
class SSHClient(
    private val session: ClientSession,
) : ISSHClient,
    KoinComponent {
    private val eventBus: EventBus by inject()
    private val log = KotlinLogging.logger {}

    // Synchronization lock to ensure thread-safe access to the session
    // This allows multiple SSHClient instances (different hosts) to operate in parallel
    // while serializing operations on the same session (same host)
    private val operationLock = Any()

    // Helper property for logging and error messages
    private val remoteAddress: String
        get() = "${session.username}@${session.ioSession.remoteAddress}"

    /**
     * Execute a command on a remote host
     *
     * @param command The command to execute
     * @param output Whether to print the command output
     * @param secret Whether the command contains sensitive information
     * @return The command output wrapped in a Response object
     */
    override fun executeRemoteCommand(
        command: String,
        output: Boolean,
        secret: Boolean,
    ): Response =
        synchronized(operationLock) {
            require(command.isNotBlank()) { "Command cannot be blank" }

            // Create connection for this host
            if (!secret) {
                eventBus.emit(Event.Message("Executing remote command: $command"))
            } else {
                eventBus.emit(Event.Message("Executing remote command: [hidden]"))
            }

            val stderrStream = ByteArrayOutputStream()
            val result = session.executeRemoteCommand(command, stderrStream, Charset.defaultCharset())

            if (output) {
                eventBus.emit(Event.Message(result))
            }

            return@synchronized Response(result, stderrStream.toString())
        }

    /**
     * Upload a file to a remote host
     */
    override fun uploadFile(
        local: Path,
        remote: String,
    ): Unit =
        synchronized(operationLock) {
            require(remote.isNotBlank()) { "Remote path cannot be blank" }
            require(local.toFile().exists()) { "Local file does not exist: ${local.toAbsolutePath()}" }
            require(local.toFile().isFile) { "Local path is not a file: ${local.toAbsolutePath()}" }

            eventBus.emit(Event.Message("Uploading file ${local.toAbsolutePath()} to $remoteAddress:$remote"))
            getScpClient().upload(local, remote)
        }

    /**
     * Upload a directory to a remote host
     *
     * This method uploads all files and subdirectories from the local directory
     * to the remote directory. It uses a two-phase approach:
     * 1. Build a complete list of files to upload (pure function, no I/O)
     * 2. Create directories and upload files (I/O operations)
     *
     * This separation improves testability and allows for optimizations like
     * batching directory creation into a single command.
     *
     * @param localDir The local directory to upload
     * @param remoteDir The remote directory path where files will be uploaded
     * @throws IllegalArgumentException if remoteDir is blank or localDir doesn't exist/isn't a directory
     */
    override fun uploadDirectory(
        localDir: File,
        remoteDir: String,
    ) {
        require(remoteDir.isNotBlank()) { "Remote directory path cannot be blank" }
        require(localDir.exists()) { "Local directory does not exist: ${localDir.absolutePath}" }
        require(localDir.isDirectory) { "Local path is not a directory: ${localDir.absolutePath}" }

        // Phase 1: Build the upload plan (pure function, no side effects)
        val (filesToUpload, directoriesToCreate) = buildUploadList(localDir, remoteDir)

        // Phase 2a: Create all remote directories in a single batched command
        // This reduces SSH round-trips from O(n) to O(1)
        if (directoriesToCreate.isNotEmpty()) {
            synchronized(operationLock) {
                // Sort to ensure parent directories are created before children
                val mkdirCommand =
                    directoriesToCreate
                        .sorted()
                        .joinToString(" && ") { dir -> "mkdir -p '$dir'" }
                executeRemoteCommand(mkdirCommand, false, false)
            }
        }

        // Phase 2b: Upload all files (each synchronized independently)
        filesToUpload.forEach { (localFile, remotePath) ->
            uploadFile(localFile.toPath(), remotePath)
        }
    }

    /**
     * Build a list of all files to upload from a directory tree
     *
     * This is a pure function with no side effects - it only traverses the local
     * filesystem and builds data structures. This makes it easy to test without
     * mocking SSH operations.
     *
     * Uses iterative breadth-first traversal to avoid recursion and stack overflow
     * on deep directory trees.
     *
     * @param localDir Root directory to scan
     * @param remoteDir Remote directory path prefix
     * @return Pair of (files to upload, directories to create)
     */
    private fun buildUploadList(
        localDir: File,
        remoteDir: String,
    ): Pair<List<FileUploadPair>, Set<String>> {
        val filesToUpload = mutableListOf<FileUploadPair>()
        val directoriesToCreate = mutableSetOf<String>()
        val dirsToProcess = ArrayDeque<Pair<File, String>>()

        // Start with the root directory
        dirsToProcess.add(localDir to remoteDir)
        directoriesToCreate.add(remoteDir)

        while (dirsToProcess.isNotEmpty()) {
            val (currentDir, currentRemoteDir) = dirsToProcess.removeFirst()

            // Skip if directory doesn't exist (shouldn't happen, but defensive)
            if (!currentDir.exists() || !currentDir.isDirectory) {
                log.warn { "Skipping non-existent or non-directory: ${currentDir.absolutePath}" }
                continue
            }

            // Process each item in the directory
            currentDir.listFiles()?.forEach { file ->
                val relativePath = file.toRelativeString(currentDir)
                val remotePath = "$currentRemoteDir/$relativePath"

                if (file.isDirectory) {
                    // Queue directory for processing
                    dirsToProcess.add(file to remotePath)
                    directoriesToCreate.add(remotePath)
                } else if (file.isFile) {
                    // Add file to upload list
                    filesToUpload.add(FileUploadPair(file, remotePath))
                } else {
                    // Skip symlinks, special files, etc.
                    log.debug { "Skipping non-regular file: ${file.absolutePath}" }
                }
            }
        }

        return filesToUpload to directoriesToCreate
    }

    /**
     * Download a file from a remote host
     */
    override fun downloadFile(
        remote: String,
        local: Path,
    ): Unit =
        synchronized(operationLock) {
            require(remote.isNotBlank()) { "Remote path cannot be blank" }

            log.debug { "Downloading file from $remoteAddress:$remote to ${local.toAbsolutePath()}" }
            getScpClient().download(remote, local)
        }

    /**
     * Download a directory from a remote host
     *
     * @param remoteDir The remote directory to download
     * @param localDir The local directory where files will be downloaded
     * @param includeFilters Optional list of patterns to filter files for download
     * @param excludeFilters Optional list of patterns to exclude files from download
     */
    override fun downloadDirectory(
        remoteDir: String,
        localDir: File,
        includeFilters: List<String>,
        excludeFilters: List<String>,
    ): Unit =
        synchronized(operationLock) {
            require(remoteDir.isNotBlank()) { "Remote directory path cannot be blank" }

            if (!localDir.exists()) {
                localDir.mkdirs()
            }

            log.debug { "Downloading directory from $remoteAddress:$remoteDir to ${localDir.absolutePath}" }

            val fileListOutput = executeRemoteCommand("find $remoteDir -type f", false, false)
            val remoteFiles = fileListOutput.text.split("\n").filter { it.isNotEmpty() }

            // Download each file
            for (remoteFile in remoteFiles) {
                val relativePath = remoteFile.removePrefix("$remoteDir/")

                if (shouldDownloadFile(relativePath, includeFilters, excludeFilters)) {
                    val localFile = File(localDir, relativePath)
                    // Ensure parent directory exists
                    localFile.parentFile.mkdirs()
                    downloadFile(remoteFile, localFile.toPath())
                }
            }
        }

    /**
     * Determines whether a file should be downloaded based on include/exclude filters
     *
     * @param relativePath The relative path of the file
     * @param includeFilters List of glob patterns to include (e.g., "*.log", "data*")
     * @param excludeFilters List of glob patterns to exclude (e.g., "*.tmp")
     * @return true if the file should be downloaded, false otherwise
     */
    private fun shouldDownloadFile(
        relativePath: String,
        includeFilters: List<String>,
        excludeFilters: List<String>,
    ): Boolean {
        val fileName = relativePath.substringAfterLast("/")

        // Convert glob patterns to regex patterns once
        val excludeRegexes =
            excludeFilters.map { pattern ->
                pattern.replace("*", ".*").toRegex()
            }

        val includeRegexes =
            includeFilters.map { pattern ->
                pattern.replace("*", ".*").toRegex()
            }

        // Check exclude filters
        val isExcluded =
            excludeRegexes.isNotEmpty() &&
                excludeRegexes.any { regex -> fileName.matches(regex) }

        // Check include filters
        val isNotIncluded =
            includeRegexes.isNotEmpty() &&
                !includeRegexes.any { regex -> fileName.matches(regex) }

        return !isExcluded && !isNotIncluded
    }

    /**
     * Get or create an SCP client for file transfers
     *
     * This method returns a cached SCP client instance to prevent resource leaks.
     * The client is automatically created on first access and cleaned up when close() is called.
     *
     * @return A CloseableScpClient instance for this session
     */
    override fun getScpClient(): CloseableScpClient =
        synchronized(operationLock) {
            // Return cached client if available, otherwise create new one
            if (cachedScpClient == null) {
                val creator = ScpClientCreator.instance()
                val client = creator.createScpClient(session)
                cachedScpClient = CloseableScpClient.singleSessionInstance(client)
            }
            return@synchronized cachedScpClient!!
        }

    // Cache SCP client to prevent resource leaks
    private var cachedScpClient: CloseableScpClient? = null

    /**
     * Check if the underlying SSH session is still open and authenticated.
     *
     * @return true if the session is open and authenticated, false otherwise
     */
    override fun isSessionOpen(): Boolean =
        synchronized(operationLock) {
            return@synchronized try {
                session.isOpen && session.isAuthenticated
            } catch (e: Exception) {
                log.debug(e) { "Error checking session status for $remoteAddress" }
                false
            }
        }

    /**
     * Stop the SSH client and clean up all resources
     *
     * This method:
     * - Closes the cached SCP client
     * - Closes the underlying SSH session
     *
     * It is safe to call this method multiple times.
     */
    override fun close(): Unit =
        synchronized(operationLock) {
            log.debug { "Stopping SSH client for $remoteAddress" }

            // Close cached SCP client if it exists
            cachedScpClient?.let {
                try {
                    it.close()
                    cachedScpClient = null
                } catch (e: java.io.IOException) {
                    log.debug(e) { "Error closing SCP client for $remoteAddress during session cleanup" }
                }
            }

            session.close()
        }
}
