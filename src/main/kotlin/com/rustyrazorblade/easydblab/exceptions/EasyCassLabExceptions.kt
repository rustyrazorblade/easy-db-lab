package com.rustyrazorblade.easydblab.exceptions

/**
 * Base exception for all Easy DB Lab specific exceptions
 */
open class EasyDBLabException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Thrown when duplicate Cassandra versions are found in configuration
 */
class DuplicateVersionException(
    versions: Set<String>,
) : EasyDBLabException(
        "Duplicate Cassandra version(s) found: ${versions.joinToString(", ")}. " +
            "Please ensure each version is unique.",
    )

/**
 * Thrown when a configuration error occurs
 */
class ConfigurationException(
    message: String,
    cause: Throwable? = null,
) : EasyDBLabException(message, cause)

/**
 * Thrown when a Docker operation fails
 */
class DockerOperationException(
    message: String,
    cause: Throwable? = null,
) : EasyDBLabException(message, cause)

/**
 * Thrown when a command execution fails
 */
class CommandExecutionException(
    message: String,
    cause: Throwable? = null,
) : EasyDBLabException(message, cause)

/**
 * Thrown when SSH operations fail
 */
class SSHException(
    message: String,
    cause: Throwable? = null,
) : EasyDBLabException(message, cause)

/**
 * Thrown when a remote command runs to completion but exits non-zero.
 *
 * Carries what the remote side actually said, because the command's own error message is the only
 * thing that explains the failure — the transport-level "Remote command failed (1)" never does.
 *
 * Deliberately not an [IOException] or [RuntimeException]: a non-zero exit is a deterministic
 * failure of the command itself, so the SSH retry policy must not re-run it. Deliberately carries
 * no cause either — the underlying exception's message repeats the unredacted command, which a
 * printed stack trace would then leak.
 */
class RemoteCommandFailedException(
    val command: String,
    val stdout: String,
    val stderr: String,
    summary: String,
) : EasyDBLabException(
        buildString {
            append(summary)
            if (stderr.isNotBlank()) {
                append("\nstderr:\n").append(stderr.trimEnd())
            }
            if (stdout.isNotBlank()) {
                append("\nstdout:\n").append(stdout.trimEnd())
            }
        },
    )

/**
 * Thrown when an AWS operation times out waiting for resources
 */
class AwsTimeoutException(
    message: String,
    cause: Throwable? = null,
) : EasyDBLabException(message, cause)
