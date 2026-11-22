package com.rustyrazorblade.easydblab.ssh

import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import com.rustyrazorblade.easydblab.output.LineSplittingOutputStream
import com.rustyrazorblade.easydblab.output.NodeStream
import com.rustyrazorblade.easydblab.redact
import com.rustyrazorblade.easydblab.redactUrlCredentials
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.io.output.TeeOutputStream
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.scp.client.CloseableScpClient
import org.apache.sshd.scp.client.ScpClientCreator
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.file.Path
import java.rmi.RemoteException
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
 * Thread-safety: All operations are serialized via operationLock to prevent
 * concurrent access to the underlying Apache SSHD ClientSession, which is not
 * thread-safe. This allows multiple SSHClient instances (different hosts) to
 * operate in parallel while serializing operations on the same session.
 *
 * Resource management: The class caches SCP clients to ensure proper cleanup
 * when close() is called.
 *
 * @property session The underlying Apache SSHD ClientSession
 * @property exec Runs one command on [session]; the default is [ChannelRemoteExec]
 */
class SSHClient(
    private val session: ClientSession,
    private val exec: RemoteExec = ChannelRemoteExec(session),
) : ISSHClient {
    private val log = KotlinLogging.logger {}

    companion object {
        /** How much of a failed command's output to keep in the exception message. */
        private const val MAX_FAILURE_OUTPUT_LINES = 50

        /** Stands in for anything belonging to a command marked secret. */
        private const val HIDDEN = "[hidden]"

        /** Where [RemoteException]'s message starts repeating its cause. */
        private const val NESTED_EXCEPTION = "; nested exception is:"
    }

    // Serializes operations on this session (one host) while other SSHClient instances run in
    // parallel. A ReentrantLock, not `synchronized`: callers are virtual threads, and on JDK 21 a
    // virtual thread that blocks inside `synchronized` pins its carrier. A command blocks for as
    // long as it runs, so enough pinned callers would leave no carrier for anything else.
    private val operationLock = ReentrantLock()

    // Helper property for logging and error messages
    private val remoteAddress: String
        get() = "${session.username}@${session.ioSession.remoteAddress}"

    /**
     * Execute a command on a remote host
     *
     * @param command The command to execute
     * @param sink Receives each redacted output line while the command runs; never used for a secret command
     * @param secret Whether the command contains sensitive information
     * @return The command output wrapped in a Response object
     */
    override fun executeRemoteCommand(
        command: String,
        sink: RemoteOutputSink?,
        secret: Boolean,
    ): Response =
        operationLock.withLock {
            require(command.isNotBlank()) { "Command cannot be blank" }

            // Every sink below — the debug log, the streamed output lines, the returned Response and the
            // thrown exception — is redacted here rather than at each call site. A remote command
            // legitimately carries a git URL with an embedded token, and the remote side echoes it
            // straight back; a per-call-site convention only has to be forgotten once to leak it.
            if (!secret) {
                log.debug { "Executing remote command: ${redact(command)}" }
            } else {
                log.debug { "Executing remote command: [hidden]" }
            }

            // Own both streams so a non-zero exit still yields whatever the command said before it
            // failed — MINA's RemoteException carries only "Remote command failed (N): <command>",
            // and the command's own error message is the only thing that explains the failure.
            val stdoutStream = ByteArrayOutputStream()
            val stderrStream = ByteArrayOutputStream()
            // A secret command echoes its own argument, so nothing of it is ever streamed. MINA writes
            // into these streams on its shared I/O threads, so the sink is called off them.
            val lineSink = sink.takeUnless { secret }?.let { OffThreadOutputSink(sink = it) }
            val stdoutLines = lineSink?.let { lines(it, NodeStream.Stdout) }
            val stderrLines = lineSink?.let { lines(it, NodeStream.Stderr) }
            try {
                PlatformThreadCall.run {
                    exec.run(command, tee(stdoutStream, stdoutLines), tee(stderrStream, stderrLines))
                }
            } catch (e: RemoteException) {
                throw remoteCommandFailure(command, secret, stdoutStream, stderrStream, e)
            } finally {
                // The sink is closed last, so every line is printed before the command returns.
                closeAll(stdoutLines, stderrLines, lineSink)
            }

            val result = redactUrlCredentials(stdoutStream.toString(Charset.defaultCharset()))

            return@withLock Response(result, redactUrlCredentials(stderrStream.toString(Charset.defaultCharset())))
        }

    private fun remoteCommandFailure(
        command: String,
        secret: Boolean,
        stdoutStream: ByteArrayOutputStream,
        stderrStream: ByteArrayOutputStream,
        failure: RemoteException,
    ): RemoteCommandFailedException {
        if (secret) {
            // A secret command routinely echoes its own argument back on failure — a usage line,
            // an error naming the key — so none of its output can be repeated.
            return RemoteCommandFailedException(
                command = HIDDEN,
                stdout = HIDDEN,
                stderr = HIDDEN,
                summary = "Remote command failed on $remoteAddress: $HIDDEN",
            )
        }
        return RemoteCommandFailedException(
            command = redact(command),
            stdout = tail(stdoutStream.toString(Charset.defaultCharset())),
            stderr = tail(stderrStream.toString(Charset.defaultCharset())),
            summary = "$remoteAddress: ${redact(exitSummary(failure) ?: "Remote command failed: $command")}",
        )
    }

    /**
     * The "Remote command failed (N): <command>" part of [failure]. Its message also appends the
     * nested ServerException MINA puts the exit status in ("; nested exception is: ..."), which
     * tells a reader nothing the first part has not.
     */
    private fun exitSummary(failure: RemoteException): String? = failure.message?.substringBefore(NESTED_EXCEPTION)

    /**
     * Closes each of [closeables] in order, even when one before it throws, so the output sink is
     * always closed and its drain thread always ends. The first failure is rethrown, with any later
     * one suppressed on it.
     */
    private fun closeAll(vararg closeables: Closeable?) {
        var failure: IOException? = null
        closeables.filterNotNull().forEach { closeable ->
            try {
                closeable.close()
            } catch (e: IOException) {
                failure?.addSuppressed(e) ?: run { failure = e }
            }
        }
        failure?.let { throw it }
    }

    /** Splits one remote stream into whole lines and hands each, redacted, to [sink]. */
    private fun lines(
        sink: RemoteOutputSink,
        stream: NodeStream,
    ) = LineSplittingOutputStream { sink.line(stream, redact(it)) }

    /** Writes to [capture] always, and also to [lines] when there is one. */
    private fun tee(
        capture: OutputStream,
        lines: OutputStream?,
    ): OutputStream = if (lines == null) capture else TeeOutputStream(capture, lines)

    /** Keeps a failure message readable when the command spewed (e.g. a replayed ant build log). */
    private fun tail(text: String): String =
        redact(
            text.lines().takeLast(MAX_FAILURE_OUTPUT_LINES).joinToString("\n"),
        )

    /**
     * Upload a file to a remote host
     */
    override fun uploadFile(
        local: Path,
        remote: String,
    ): Unit =
        operationLock.withLock {
            require(remote.isNotBlank()) { "Remote path cannot be blank" }
            require(local.toFile().exists()) { "Local file does not exist: ${local.toAbsolutePath()}" }
            require(local.toFile().isFile) { "Local path is not a file: ${local.toAbsolutePath()}" }

            val scp = getScpClient()
            PlatformThreadCall.run { scp.upload(local, remote) }
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
            operationLock.withLock {
                // Sort to ensure parent directories are created before children
                val mkdirCommand =
                    directoriesToCreate
                        .sorted()
                        .joinToString(" && ") { dir -> "mkdir -p '$dir'" }
                executeRemoteCommand(mkdirCommand, null, false)
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
        operationLock.withLock {
            require(remote.isNotBlank()) { "Remote path cannot be blank" }

            log.debug { "Downloading file from $remoteAddress:$remote to ${local.toAbsolutePath()}" }
            val scp = getScpClient()
            PlatformThreadCall.run { scp.download(remote, local) }
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
        operationLock.withLock {
            require(remoteDir.isNotBlank()) { "Remote directory path cannot be blank" }

            if (!localDir.exists()) {
                localDir.mkdirs()
            }

            log.debug { "Downloading directory from $remoteAddress:$remoteDir to ${localDir.absolutePath}" }

            val fileListOutput = executeRemoteCommand("find $remoteDir -type f", null, false)
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
        operationLock.withLock {
            return@withLock cachedScpClient
                ?: CloseableScpClient
                    .singleSessionInstance(ScpClientCreator.instance().createScpClient(session))
                    .also { cachedScpClient = it }
        }

    // Cache SCP client to prevent resource leaks
    private var cachedScpClient: CloseableScpClient? = null

    /**
     * Check if the underlying SSH session is still open and authenticated.
     *
     * @return true if the session is open and authenticated, false otherwise
     */
    override fun isSessionOpen(): Boolean =
        operationLock.withLock {
            return@withLock try {
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
        operationLock.withLock {
            log.debug { "Stopping SSH client for $remoteAddress" }

            // Close cached SCP client if it exists
            cachedScpClient?.let {
                try {
                    it.close()
                    cachedScpClient = null
                } catch (e: IOException) {
                    log.debug(e) { "Error closing SCP client for $remoteAddress during session cleanup" }
                }
            }

            PlatformThreadCall.run { session.close() }
        }
}
