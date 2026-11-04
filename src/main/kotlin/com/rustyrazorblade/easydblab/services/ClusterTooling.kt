package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.exceptions.EasyDBLabException
import com.rustyrazorblade.easydblab.ssh.redactUrlCredentials

/**
 * The cluster command-line tools a kit or service drives: what [ClusterTooling] can run.
 *
 * [executable] is the program name, which is also the name of its workspace wrapper in `bin/`.
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
 * It exists so the same service code can reach a cluster two ways: over SSH on the control node (AWS),
 * or as a local process in the kit process environment (a local cluster with no SSH host). A service
 * builds an argument list and never says where the tool runs.
 */
interface ClusterTooling {
    /**
     * Runs [tool] with [args], feeding [stdin] when given, and returns its stdout.
     *
     * A file argument of `-` (`apply -f -`, `--values -`) reads [stdin]. The local form pipes it; the
     * SSH form uploads it to a temporary file on the control node and replaces the `-` with that path.
     * When [stdin] is given, exactly one argument in [args] must be `-` for the SSH form.
     *
     * [shellQuoted] holds the indexes into [args] that the SSH form single-quotes because the remote
     * shell would otherwise split or expand them (a label selector, an object name). The local form
     * passes arguments as a list, never through a shell, so it quotes nothing; both forms fail fast on
     * an index outside [args]. A caller therefore passes raw values and marks them, and never quotes a
     * value itself.
     *
     * [echo] shows stdout to the user; callers that parse stdout pass false.
     *
     * @throws ClusterToolFailedException when the tool exits non-zero
     */
    fun run(
        tool: ClusterTool,
        args: List<String>,
        stdin: String? = null,
        echo: Boolean = true,
        shellQuoted: Set<Int> = emptySet(),
    ): String
}

/**
 * Fails fast when [shellQuoted] names an argument [args] does not have. Both [ClusterTooling] forms
 * call it first, so a wrong index fails the same way wherever the tool would have run.
 */
internal fun requireShellQuotedWithin(
    tool: ClusterTool,
    args: List<String>,
    shellQuoted: Set<Int>,
) = require(shellQuoted.all { it in args.indices }) {
    "shellQuoted $shellQuoted names an index outside the ${args.size} arguments of ${tool.executable}"
}

/**
 * Thrown when a helm or kubectl command exits non-zero, in either [ClusterTooling] form.
 *
 * Carries what the tool actually said, because its stderr is the main thing that explains the failure.
 * [stderr] and [stdout] each keep the last [Constants.ClusterTooling.FAILURE_TAIL_LINES] lines with URL
 * credentials redacted, so a failed `apply -f https://user:token@host/...` cannot print its token. The
 * message shows stdout after stderr, when there is any, as `RemoteCommandFailedException`'s message did
 * before the SSH form replaced it. [exitStatus] is
 * [Constants.ClusterTooling.UNKNOWN_EXIT_STATUS] when the transport did not report one. [location] names
 * where the tool ran when that is not this machine, such as the SSH form's control node. [args] is
 * kept exactly as the caller passed it, unredacted, for code that inspects the failure; only the
 * message and [stderr] are redacted, so never print [args] directly.
 *
 * Like `RemoteCommandFailedException` it carries no cause: the cause's message would repeat the
 * unredacted command.
 */
class ClusterToolFailedException(
    val tool: ClusterTool,
    val args: List<String>,
    val exitStatus: Int,
    stderr: String,
    val location: String? = null,
    stdout: String = "",
) : EasyDBLabException(
        describe(tool, args, exitStatus, location, listOf("stderr" to redactedTail(stderr), "stdout" to redactedTail(stdout))),
    ) {
    /** The tool's stderr, tailed and with URL credentials redacted. */
    val stderr: String = redactedTail(stderr)

    /** The tool's stdout, tailed and with URL credentials redacted. */
    val stdout: String = redactedTail(stdout)

    private companion object {
        fun redactedTail(text: String): String =
            redactUrlCredentials(
                text
                    .lines()
                    .takeLast(Constants.ClusterTooling.FAILURE_TAIL_LINES)
                    .joinToString("\n")
                    .trimEnd(),
            )

        fun describe(
            tool: ClusterTool,
            args: List<String>,
            exitStatus: Int,
            location: String?,
            streams: List<Pair<String, String>>,
        ): String =
            buildString {
                append(redactUrlCredentials((listOf(tool.executable) + args).joinToString(" ")))
                append(" failed with exit status ").append(exitStatus)
                location?.let { append(" on ").append(it) }
                streams.filter { (_, text) -> text.isNotBlank() }.forEach { (name, text) ->
                    append("\n").append(name).append(":\n").append(text)
                }
            }
    }
}
