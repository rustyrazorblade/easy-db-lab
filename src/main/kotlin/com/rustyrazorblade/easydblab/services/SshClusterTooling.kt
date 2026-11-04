package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.redactUrlCredentials
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * [ClusterTooling] for AWS: runs helm and kubectl on the control node over SSH, where the tools are
 * baked into the AMI and `k3s.yaml` is the kubeconfig.
 *
 * It keeps the command strings the services used to build themselves, so a change of seam does not
 * change what runs on the cluster: `KUBECONFIG=<remote kubeconfig> <tool> <args>`, joined with single
 * spaces. Only the arguments a caller marks in `shellQuoted` are single-quoted; the rest are joined
 * raw, as before. Input (a manifest, helm values) is staged in a local temporary file under [tempDir],
 * uploaded to `/tmp/easydblab-input-<uuid>.yaml` on the control node, and the `-` argument is replaced
 * by that remote path. Both files are removed whether the command succeeds or fails.
 *
 * The control host is read from cluster state on every call and never cached, because `Server`, `Repl`
 * and `up` outlive a change of state.
 *
 * @param tempDir where the local staging copy of stdin is written. Injected rather than read from
 *   `java.io.tmpdir` at use, because the JVM caches that property at startup.
 */
class SshClusterTooling(
    private val remoteOps: RemoteOperationsService,
    private val clusterStateManager: ClusterStateManager,
    private val tempDir: Path,
) : ClusterTooling {
    private val log = KotlinLogging.logger {}

    override fun run(
        tool: ClusterTool,
        args: List<String>,
        stdin: String?,
        echo: Boolean,
        shellQuoted: Set<Int>,
    ): String {
        require(stdin == null || args.count { it == Constants.ClusterTooling.STDIN_ARGUMENT } == 1) {
            "A command with stdin needs exactly one '${Constants.ClusterTooling.STDIN_ARGUMENT}' argument " +
                "to stand for the uploaded file, got " +
                redactUrlCredentials((listOf(tool.executable) + args).joinToString(" "))
        }
        requireShellQuotedWithin(tool, args, shellQuoted)
        val call = Call(controlHost(), tool, args, echo, shellQuoted)
        try {
            return if (stdin == null) execute(call) else executeWithInput(call, stdin)
        } catch (e: RemoteCommandFailedException) {
            throw ClusterToolFailedException(
                tool = tool,
                args = args,
                exitStatus = e.exitStatus ?: Constants.ClusterTooling.UNKNOWN_EXIT_STATUS,
                stderr = e.stderr,
                location = describe(call.host),
                stdout = e.stdout,
            )
        }
    }

    /**
     * The control node as a failure names it: `alias (public address)`, or `alias (private address)`
     * when the public one is blank (`EC2InstanceService` records a blank `publicIp` for a node with
     * none), or just the alias when both are blank.
     */
    private fun describe(host: Host): String =
        when {
            host.public.isNotBlank() -> "${host.alias} (${host.public})"
            host.private.isNotBlank() -> "${host.alias} (${host.private})"
            else -> host.alias
        }

    private fun controlHost(): Host =
        clusterStateManager.load().getControlHost()?.toHost()
            ?: error("No control node found. Please ensure the environment is running.")

    /** One [ClusterTooling.run] call, bound to the control host it resolved. */
    private data class Call(
        val host: Host,
        val tool: ClusterTool,
        val args: List<String>,
        val echo: Boolean,
        val shellQuoted: Set<Int>,
    )

    private fun execute(call: Call): String {
        val rendered = call.args.mapIndexed { index, arg -> if (index in call.shellQuoted) shellQuote(arg) else arg }
        val command = "KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG} ${(listOf(call.tool.executable) + rendered).joinToString(" ")}"
        return remoteOps.executeRemotely(call.host, command, output = call.echo).text
    }

    private fun executeWithInput(
        call: Call,
        stdin: String,
    ): String {
        val host = call.host
        val localFile =
            Files.createTempFile(
                tempDir,
                Constants.ClusterTooling.INPUT_FILE_PREFIX,
                Constants.ClusterTooling.INPUT_FILE_SUFFIX,
            )
        val remotePath =
            "${Constants.ClusterTooling.REMOTE_INPUT_DIRECTORY}/${Constants.ClusterTooling.INPUT_FILE_PREFIX}-" +
                "${UUID.randomUUID()}${Constants.ClusterTooling.INPUT_FILE_SUFFIX}"
        try {
            Files.writeString(localFile, stdin)
            remoteOps.upload(host, localFile, remotePath)
            val remoteArgs = call.args.map { if (it == Constants.ClusterTooling.STDIN_ARGUMENT) remotePath else it }
            return execute(call.copy(args = remoteArgs))
        } finally {
            // A cleanup failure is logged and never replaces the tool's own exception.
            bestEffort("remove $localFile") { Files.deleteIfExists(localFile) }
            bestEffort("remove ${host.alias}:$remotePath") { remoteOps.executeRemotely(host, "rm -f $remotePath", output = false) }
        }
    }

    private fun bestEffort(
        what: String,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (e: InterruptedException) {
            // Cleanup must not swallow a cancellation: put the flag back for the caller.
            Thread.currentThread().interrupt()
            log.warn(e) { "Interrupted while trying to $what" }
        } catch (e: Exception) {
            log.warn(e) { "Could not $what" }
        }
    }

    /** Single-quotes [value] for the remote shell, so a selector like `tier in (a, b)` stays one word. */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
