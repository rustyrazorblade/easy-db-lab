package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.Version
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.observability.TelemetryNames
import com.rustyrazorblade.easydblab.observability.TelemetryProvider
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.ssh.Response
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.IOException
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
    private val outputHandler: OutputHandler by inject()
    private val telemetryProvider: TelemetryProvider by inject()

    companion object {
        private val log = KotlinLogging.logger {}

        /**
         * Maximum length of command to include in telemetry attributes.
         * Commands longer than this are truncated to avoid bloating traces.
         */
        private const val MAX_COMMAND_LENGTH_FOR_ATTRIBUTE = 200

        /**
         * Default retry configuration for SSH operations:
         * - Max 3 attempts
         * - 2 second initial wait
         * - Exponential backoff (1.5x multiplier)
         * - Retries on IOException and RuntimeException
         */
        val defaultRetryConfig: RetryConfig =
            RetryConfig
                .custom<Any>()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(2000))
                .retryExceptions(IOException::class.java, RuntimeException::class.java)
                .build()
    }

    private val retry = Retry.of("ssh-operations", retryConfig)

    override fun executeRemotely(
        host: Host,
        command: String,
        output: Boolean,
        secret: Boolean,
    ): Response {
        log.debug { "Executing command on ${host.alias}: ${if (secret) "[REDACTED]" else command}" }
        val attributes =
            mutableMapOf(
                TelemetryNames.Attributes.HOST_ALIAS to host.alias,
                TelemetryNames.Attributes.HOST_TARGET to host.public,
            )
        if (!secret) {
            attributes[TelemetryNames.Attributes.SSH_COMMAND] = command.take(MAX_COMMAND_LENGTH_FOR_ATTRIBUTE)
        } else {
            attributes[TelemetryNames.Attributes.SSH_COMMAND_REDACTED] = "true"
        }

        return telemetryProvider.withSpan(TelemetryNames.Spans.SSH_EXECUTE, attributes) {
            Retry
                .decorateSupplier(retry) {
                    connectionProvider.getConnection(host).executeRemoteCommand(command, output, secret)
                }.get()
        }
    }

    override fun upload(
        host: Host,
        local: Path,
        remote: String,
    ) {
        log.info { "Uploading $local to ${host.alias}:$remote" }
        val attributes =
            mapOf(
                TelemetryNames.Attributes.HOST_ALIAS to host.alias,
                TelemetryNames.Attributes.HOST_TARGET to host.public,
                TelemetryNames.Attributes.FILE_PATH_LOCAL to local.toString(),
                TelemetryNames.Attributes.FILE_PATH_REMOTE to remote,
            )
        telemetryProvider.withSpan(TelemetryNames.Spans.SSH_UPLOAD, attributes) {
            Retry
                .decorateRunnable(retry) {
                    connectionProvider.getConnection(host).uploadFile(local, remote)
                }.run()
        }
    }

    override fun uploadDirectory(
        host: Host,
        localDir: File,
        remoteDir: String,
    ) {
        log.info { "Uploading directory $localDir to ${host.alias}:$remoteDir" }
        outputHandler.handleMessage("Uploading directory $localDir to $remoteDir")
        val attributes =
            mapOf(
                TelemetryNames.Attributes.HOST_ALIAS to host.alias,
                TelemetryNames.Attributes.HOST_TARGET to host.public,
                TelemetryNames.Attributes.FILE_PATH_LOCAL to localDir.toString(),
                TelemetryNames.Attributes.FILE_PATH_REMOTE to remoteDir,
            )
        telemetryProvider.withSpan(TelemetryNames.Spans.SSH_UPLOAD_DIRECTORY, attributes) {
            Retry
                .decorateRunnable(retry) {
                    connectionProvider.getConnection(host).uploadDirectory(localDir, remoteDir)
                }.run()
        }
    }

    override fun uploadDirectory(
        host: Host,
        version: Version,
    ) {
        uploadDirectory(host, version.file, version.conf)
    }

    override fun download(
        host: Host,
        remote: String,
        local: Path,
    ) {
        log.info { "Downloading ${host.alias}:$remote to $local" }
        val attributes =
            mapOf(
                TelemetryNames.Attributes.HOST_ALIAS to host.alias,
                TelemetryNames.Attributes.HOST_TARGET to host.public,
                TelemetryNames.Attributes.FILE_PATH_REMOTE to remote,
                TelemetryNames.Attributes.FILE_PATH_LOCAL to local.toString(),
            )
        telemetryProvider.withSpan(TelemetryNames.Spans.SSH_DOWNLOAD, attributes) {
            Retry
                .decorateRunnable(retry) {
                    connectionProvider.getConnection(host).downloadFile(remote, local)
                }.run()
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
        val attributes =
            mapOf(
                TelemetryNames.Attributes.HOST_ALIAS to host.alias,
                TelemetryNames.Attributes.HOST_TARGET to host.public,
                TelemetryNames.Attributes.FILE_PATH_REMOTE to remoteDir,
                TelemetryNames.Attributes.FILE_PATH_LOCAL to localDir.toString(),
            )
        telemetryProvider.withSpan(TelemetryNames.Spans.SSH_DOWNLOAD_DIRECTORY, attributes) {
            Retry
                .decorateRunnable(retry) {
                    connectionProvider.getConnection(host).downloadDirectory(
                        remoteDir,
                        localDir,
                        includeFilters,
                        excludeFilters,
                    )
                }.run()
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
