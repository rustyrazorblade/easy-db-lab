package com.rustyrazorblade.easydblab.ssh

import org.apache.sshd.scp.client.CloseableScpClient
import java.io.File
import java.nio.file.Path

/**
 * Interface for SSH operations
 * Allows for testing with mock implementations
 */
interface ISSHClient {
    /**
     * Execute a command on a remote host.
     *
     * @param sink receives each output line as it arrives; null shows nothing
     * @param secret the command carries a secret, so neither it nor its output is ever repeated
     */
    fun executeRemoteCommand(
        command: String,
        sink: RemoteOutputSink?,
        secret: Boolean,
    ): Response

    /**
     * Execute a command on a remote host with [input] as its stdin, ended with EOF. A command with
     * input is never secret: [input] is a manifest or a values file, and its output is streamed.
     *
     * @param sink receives each output line as it arrives; null shows nothing
     */
    fun executeRemoteCommandWithInput(
        command: String,
        input: String,
        sink: RemoteOutputSink?,
    ): Response

    /**
     * Upload a file to a remote host
     */
    fun uploadFile(
        local: Path,
        remote: String,
    )

    /**
     * Upload a directory to a remote host
     */
    fun uploadDirectory(
        localDir: File,
        remoteDir: String,
    )

    /**
     * Download a file from a remote host
     */
    fun downloadFile(
        remote: String,
        local: Path,
    )

    /**
     * Download a directory from a remote host
     *
     * @param remoteDir The remote directory to download
     * @param localDir The local directory where files will be downloaded
     * @param includeFilters Optional list of patterns to filter files for download
     * @param excludeFilters Optional list of patterns to exclude files from download
     */
    fun downloadDirectory(
        remoteDir: String,
        localDir: File,
        includeFilters: List<String> = listOf(),
        excludeFilters: List<String> = listOf(),
    )

    /**
     * Get an SCP client for this connection
     */
    fun getScpClient(): CloseableScpClient

    /**
     * Check if the underlying SSH session is still open and authenticated.
     *
     * @return true if the session is open and authenticated, false otherwise
     */
    fun isSessionOpen(): Boolean

    /**
     * Close the connection
     */
    fun close() {}
}
