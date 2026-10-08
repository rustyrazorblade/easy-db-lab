package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.Version
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import com.rustyrazorblade.easydblab.ssh.Response
import com.rustyrazorblade.easydblab.ssh.redactUrlCredentials
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import org.apache.sshd.common.SshException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.nio.file.Path
import java.time.Duration

/**
 * Default implementation of RemoteOperationsService with automatic retry logic.
 * Provides high-level SSH operations using an SSHConnectionProvider with resilience4j retry support
 * to handle transient network failures and SSH connection issues.
 *
 * @param connectionProvider Provider for SSH connections
 * @param retryConfig Optional retry configuration (defaults to 3 attempts, 2s wait, exponential backoff)
 */
class DefaultRemoteOperationsService(
    private val connectionProvider: SSHConnectionProvider,
    retryConfig: RetryConfig = defaultRetryConfig,
) : RemoteOperationsService,
    KoinComponent {
    private val eventBus: EventBus by inject()

    companion object {
        private val log = KotlinLogging.logger {}

        /**
         * Default retry configuration for SSH operations:
         * - Max 3 attempts
         * - 2 second initial wait
         * - Exponential backoff (1.5x multiplier)
         * - Retries SshException, how MINA reports a refused, timed-out or dropped connection, and
         *   RuntimeException, which includes SsmForwardNotReadyException from the `ssm` transport.
         *   SshException is checked, so every operation is decorated with resilience4j's checked
         *   decorators; the plain ones catch only RuntimeException and would never see it
         * - Fails fast on any other IOException, such as an SFTP "no such file", which a retry
         *   cannot fix
         * - Never retries a command that ran and exited non-zero: that is a deterministic failure
         *   of the command itself, and re-running it would just repeat the work (a failed Cassandra
         *   build three times over) while hiding the real error behind the delay
         */
        val defaultRetryConfig: RetryConfig =
            RetryConfig
                .custom<Any>()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(2000))
                .retryExceptions(SshException::class.java, RuntimeException::class.java)
                .ignoreExceptions(RemoteCommandFailedException::class.java)
                .build()
    }

    private val retry = Retry.of("ssh-operations", retryConfig)

    /**
     * Runs [operation] against [host] under [retry], with the checked decorator so SshException is
     * retried too. An SshException also discards the host's connection and path first, so the
     * retry dials a fresh one instead of a stuck SSM forward.
     */
    private fun <T> withRetry(
        host: Host,
        operation: () -> T,
    ): T =
        Retry
            .decorateCheckedSupplier(retry) {
                try {
                    operation()
                } catch (e: SshException) {
                    connectionProvider.discard(host)
                    throw e
                }
            }.get()

    override fun executeRemotely(
        host: Host,
        command: String,
        output: Boolean,
        secret: Boolean,
    ): Response {
        log.debug {
            "Executing command on ${host.alias}: ${if (secret) "[REDACTED]" else redactUrlCredentials(command)}"
        }
        return withRetry(host) { connectionProvider.getConnection(host).executeRemoteCommand(command, output, secret) }
    }

    override fun upload(
        host: Host,
        local: Path,
        remote: String,
    ) {
        log.info { "Uploading $local to ${host.alias}:$remote" }
        withRetry(host) {
            connectionProvider.getConnection(host).uploadFile(local, remote)
        }
    }

    override fun uploadDirectory(
        host: Host,
        localDir: File,
        remoteDir: String,
    ) {
        log.info { "Uploading directory $localDir to ${host.alias}:$remoteDir" }
        eventBus.emit(Event.Ssh.UploadingDirectory(localDir.toString(), remoteDir))
        withRetry(host) {
            connectionProvider.getConnection(host).uploadDirectory(localDir, remoteDir)
        }
    }

    override fun uploadDirectory(
        host: Host,
        version: Version,
    ) {
        uploadDirectory(host, version.file, version.conf)
    }

    override fun replaceDirectory(
        host: Host,
        localDir: File,
        remoteDir: String,
        owner: String,
    ) {
        val parent = remoteDir.substringBeforeLast('/')
        val template = "${remoteDir.substringAfterLast('/')}.staging.XXXXXX"
        // mktemp runs as root so it can create beside a root-owned target; the directory is then
        // handed to the SSH user so SFTP can write into it.
        val staging =
            executeRemotely(
                host,
                "d=\$(sudo mktemp -d -p $parent $template) && sudo chown \"\$(id -un)\" \"\$d\" && echo \"\$d\"",
                output = false,
            ).text.trim()
        check(staging.startsWith("$parent/")) { "Staging directory '$staging' is not under $parent" }
        try {
            uploadDirectory(host, localDir, staging)
            executeRemotely(host, swapCommand(staging, remoteDir, owner), output = false)
        } catch (e: Exception) {
            executeRemotely(host, "sudo rm -rf $staging", output = false)
            throw e
        }
    }

    /**
     * Moves the old tree aside, renames the staged one in, chowns it, then drops the old one.
     * A leftover `.old` from an interrupted run is cleared first so the move-aside cannot fail
     * on a non-empty target.
     */
    private fun swapCommand(
        staging: String,
        remoteDir: String,
        owner: String,
    ): String {
        val old = "$remoteDir.old"
        return "sudo rm -rf $old && " +
            "if sudo test -e $remoteDir; then sudo mv -T $remoteDir $old; fi && " +
            "sudo mv -T $staging $remoteDir && " +
            "sudo chown -R $owner $remoteDir && " +
            "sudo rm -rf $old"
    }

    override fun download(
        host: Host,
        remote: String,
        local: Path,
    ) {
        log.info { "Downloading ${host.alias}:$remote to $local" }
        withRetry(host) {
            connectionProvider.getConnection(host).downloadFile(remote, local)
        }
    }

    override fun downloadDirectory(
        host: Host,
        remoteDir: String,
        localDir: File,
        includeFilters: List<String>,
        excludeFilters: List<String>,
    ) {
        log.info {
            "Downloading directory ${host.alias}:$remoteDir to $localDir " +
                "(include: $includeFilters, exclude: $excludeFilters)"
        }
        withRetry(host) {
            connectionProvider.getConnection(host).downloadDirectory(
                remoteDir,
                localDir,
                includeFilters,
                excludeFilters,
            )
        }
    }

    override fun getRemoteVersion(
        host: Host,
        inputVersion: String,
    ): Version =
        if (inputVersion == "current") {
            val path = executeRemotely(host, "readlink -f /usr/local/cassandra/current", output = false).text.trim()
            Version(path)
        } else {
            Version.fromString(inputVersion)
        }
}
