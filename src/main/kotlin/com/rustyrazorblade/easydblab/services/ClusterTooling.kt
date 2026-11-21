package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.exceptions.EasyDBLabException
import com.rustyrazorblade.easydblab.redact

/**
 * The cluster command-line tools a kit or service drives: what [ClusterTooling] can run.
 *
 * [executable] is the program name.
 */
enum class ClusterTool(
    val executable: String,
) {
    KUBECTL("kubectl"),
    HELM("helm"),
}

/**
 * The one seam through which `KubectlService` and `HelmService` run helm and kubectl.
 *
 * A service builds an argument list and never says where the tool runs, so a cluster reached some
 * other way than SSH to the control node needs a new implementation, not new service code.
 * [SshClusterTooling] is the only implementation today; the local form comes with #1163.
 */
interface ClusterTooling {
    /**
     * Runs [tool] with [args], feeding [stdin] when given, and returns its stdout.
     *
     * A file argument of `-` (`apply -f -`, `--values -`) reads [stdin]; when [stdin] is given,
     * exactly one argument must be `-`. Every argument reaches the tool as exactly the value passed,
     * so a caller passes raw values and never quotes one itself. [echo] shows stdout to the user;
     * callers that parse stdout pass false.
     *
     * @throws ClusterToolFailedException when the tool exits non-zero
     */
    fun run(
        tool: ClusterTool,
        args: List<String>,
        stdin: String? = null,
        echo: Boolean = true,
    ): String
}

/**
 * Thrown when a helm or kubectl command fails.
 *
 * It names the command, where it ran and its exit status, and carries the tool's stderr and stdout,
 * because stderr is what explains the failure. [stderr] and [stdout] arrive already tailed and
 * redacted by the transport; the command in the message is redacted here. [exitStatus] is null when
 * the transport reported none. [args] is kept exactly as the caller passed it, unredacted, for code
 * that inspects the failure, so never print it directly. Like `RemoteCommandFailedException` it
 * carries no cause, whose message would repeat the unredacted command.
 */
class ClusterToolFailedException(
    val tool: ClusterTool,
    val args: List<String>,
    val exitStatus: Int?,
    val location: String,
    val stderr: String,
    val stdout: String = "",
) : EasyDBLabException(
        buildString {
            append(redact((listOf(tool.executable) + args).joinToString(" ")))
            append(" failed on ").append(location)
            append(exitStatus?.let { " with exit status $it" } ?: " with no exit status reported")
            if (stderr.isNotBlank()) append("\nstderr:\n").append(stderr.trimEnd())
            if (stdout.isNotBlank()) append("\nstdout:\n").append(stdout.trimEnd())
        },
    )
