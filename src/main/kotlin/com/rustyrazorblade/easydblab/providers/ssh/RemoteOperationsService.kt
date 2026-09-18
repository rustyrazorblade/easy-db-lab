package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.Version
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.ssh.Response
import java.io.File
import java.nio.file.Path

/**
 * Service interface for remote operations over SSH.
 * Provides high-level operations for executing commands and transferring files.
 */
interface RemoteOperationsService {
    /**
     * Execute a command on a remote host.
     *
     * @param host The host to execute the command on
     * @param command The command to execute
     * @param output Whether to capture and display output
     * @param secret Whether the command contains sensitive information
     * @return The response from the command execution
     */
    fun executeRemotely(
        host: Host,
        command: String,
        output: Boolean = true,
        secret: Boolean = false,
    ): Response

    /**
     * Upload a file to a remote host.
     *
     * @param host The target host
     * @param local The local file path
     * @param remote The remote destination path
     */
    fun upload(
        host: Host,
        local: Path,
        remote: String,
    )

    /**
     * Upload a directory recursively to a remote host.
     *
     * @param host The target host
     * @param localDir The local directory to upload
     * @param remoteDir The remote directory where files will be uploaded
     */
    fun uploadDirectory(
        host: Host,
        localDir: File,
        remoteDir: String,
    )

    /**
     * Upload a directory using Version information.
     * Convenience method that uses the file and conf dir of Version to upload.
     *
     * @param host The target host
     * @param version The version containing directory information
     */
    fun uploadDirectory(
        host: Host,
        version: Version,
    )

    /**
     * Replaces the directory at [remoteDir] with the contents of [localDir], atomically as seen
     * by anything polling [remoteDir].
     *
     * The tree is uploaded to a staging directory beside [remoteDir] (same filesystem, so the
     * final move is a rename), then the old directory is moved aside, the staged one moved in and
     * chowned to [owner], and the old one removed. A reader of [remoteDir] therefore sees either
     * the complete old tree or the complete new one, never an empty or half-copied directory.
     * If anything fails before the swap the staging directory is removed and the failure
     * propagates.
     *
     * @param host The target host
     * @param localDir The local directory whose contents become [remoteDir]
     * @param remoteDir The remote directory to replace; created if absent
     * @param owner `chown` spec (`user:group`, ids or names) the new directory is handed to
     */
    fun replaceDirectory(
        host: Host,
        localDir: File,
        remoteDir: String,
        owner: String,
    )

    /**
     * Download a file from a remote host.
     *
     * @param host The source host
     * @param remote The remote file path
     * @param local The local destination path
     */
    fun download(
        host: Host,
        remote: String,
        local: Path,
    )

    /**
     * Download a directory recursively from a remote host.
     *
     * @param host The source host
     * @param remoteDir The remote directory to download
     * @param localDir The local directory where files will be downloaded
     * @param includeFilters Optional list of patterns to filter files for download (e.g. "jvm*")
     * @param excludeFilters Optional list of patterns to exclude files from download (e.g. "*.bak")
     */
    fun downloadDirectory(
        host: Host,
        remoteDir: String,
        localDir: File,
        includeFilters: List<String> = listOf(),
        excludeFilters: List<String> = listOf(),
    )

    /**
     * Get the version currently set on the remote server.
     *
     * @param host The host to check
     * @param inputVersion The version to use, or "current" to check the symlink
     * @return A Version object containing the path and version component
     */
    fun getRemoteVersion(
        host: Host,
        inputVersion: String = "current",
    ): Version
}
