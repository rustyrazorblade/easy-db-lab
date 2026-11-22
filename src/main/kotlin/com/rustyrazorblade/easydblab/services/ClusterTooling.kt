package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
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
 * One argument of a command run with input ([ClusterTooling.runWithInput]): a plain [Value], or
 * [Input], the file argument that reads the input (`apply -f <Input>`, `--values <Input>`).
 *
 * The input's position is a type, not a magic string, so a value that happens to be `-` is never
 * taken for it, and a command with input says where the input goes.
 */
sealed interface ToolArg {
    /** An argument passed to the tool exactly as [text]. */
    data class Value(
        val text: String,
    ) : ToolArg

    /** The file argument that reads the command's input; both tools spell it `-`. */
    data object Input : ToolArg

    companion object {
        /** [texts] as plain values. */
        fun values(vararg texts: String): List<ToolArg> = texts.map(::Value)
    }
}

/**
 * The argument list [tool] sees for [ClusterTooling.runWithInput]: each [ToolArg.Value] as its text
 * and [ToolArg.Input] as `-`. The port's one-input rule is checked here, so every implementation
 * enforces it the same way before it touches a cluster.
 *
 * @throws IllegalArgumentException when the list does not hold exactly one [ToolArg.Input]; the
 *   message holds the command redacted
 */
internal fun List<ToolArg>.toToolArguments(tool: ClusterTool): List<String> {
    val rendered =
        map { arg ->
            when (arg) {
                is ToolArg.Value -> arg.text
                ToolArg.Input -> Constants.ClusterTooling.STDIN_ARGUMENT
            }
        }
    require(count { it == ToolArg.Input } == 1) {
        "A command with input needs exactly one input argument, got " + redact((listOf(tool.executable) + rendered).joinToString(" "))
    }
    return rendered
}

/**
 * The one seam through which `KubectlService` and `HelmService` run helm and kubectl.
 *
 * A service builds an argument list and never says where the tool runs, so a cluster reached some
 * other way than SSH to the control node needs a new implementation, not new service code.
 * [SshClusterTooling] is the only implementation today; the local form comes with #1163.
 *
 * Every argument reaches the tool as exactly the value passed, so a caller passes raw values and
 * never quotes one itself. [echo] shows stdout to the user; callers that parse stdout pass false.
 *
 * Both functions throw:
 * - [ClusterToolFailedException] when the tool ran and exited non-zero;
 * - [IllegalStateException] when there is no cluster to run it on (for SSH, no control node in
 *   cluster state);
 * - the transport's own exception when the cluster cannot be reached after the transport's
 *   retries (for SSH, MINA's `SshException`, an `IOException`). It is not wrapped: the command
 *   never ran, and the transport's message is what explains why.
 */
interface ClusterTooling {
    /** Runs [tool] with [args] and no input, and returns its stdout. */
    fun run(
        tool: ClusterTool,
        args: List<String>,
        echo: Boolean = true,
    ): String

    /**
     * Runs [tool] with [args], feeding it [input] at the one [ToolArg.Input] argument, and returns
     * its stdout.
     *
     * @throws IllegalArgumentException when [args] does not hold exactly one [ToolArg.Input]; the
     *   cluster is not touched
     */
    fun runWithInput(
        tool: ClusterTool,
        args: List<ToolArg>,
        input: String,
        echo: Boolean = true,
    ): String
}

/**
 * Thrown when a helm or kubectl command fails.
 *
 * It names the command, where it ran and its exit status, and carries the tool's stderr and stdout,
 * because stderr is what explains the failure. [stderr] and [stdout] arrive already tailed and
 * redacted by the transport, and the command in the message is redacted here; the raw arguments are
 * not kept, because a kit variable interpolated into them can hold a credential. [exitStatus] is
 * null when the transport reported none. Like `RemoteCommandFailedException` it carries no cause,
 * whose message would repeat the unredacted command.
 *
 * @param args the arguments as the tool saw them, used only for the redacted message
 */
class ClusterToolFailedException(
    val tool: ClusterTool,
    args: List<String>,
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
