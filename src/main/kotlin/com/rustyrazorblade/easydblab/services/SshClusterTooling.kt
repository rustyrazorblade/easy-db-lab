package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.redact
import com.rustyrazorblade.easydblab.runCatchingException
import com.rustyrazorblade.easydblab.shellQuote
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * [ClusterTooling] for a cluster reached over SSH: runs helm and kubectl on the control node, where
 * the tools are baked into the image and `k3s.yaml` is the kubeconfig.
 *
 * The command is `KUBECONFIG=<remote kubeconfig> <tool> <args>`, each argument passed through
 * `String.shellQuote`, so it reaches the remote shell as one word holding exactly its value: an
 * argument of shell-safe characters is joined as it is, and any other one (a space, `;`, `$`, which
 * an interpolated kit variable can hold) is single-quoted.
 *
 * Input (a manifest, helm values) is staged in a local temporary file under [tempDir], uploaded to
 * `/tmp/easydblab-input-<uuid>.yaml` on the control node, and the `-` argument is replaced by that
 * path. Both files are removed whether the command succeeds or fails.
 *
 * The control host is read from cluster state on every call and never cached, because `Server`,
 * `Repl` and `up` outlive a change of state.
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
    ): String {
        require(stdin == null || args.count { it == Constants.ClusterTooling.STDIN_ARGUMENT } == 1) {
            "A command with stdin needs exactly one '${Constants.ClusterTooling.STDIN_ARGUMENT}' argument, got " +
                redact((listOf(tool.executable) + args).joinToString(" "))
        }
        val host = controlHost()
        try {
            return if (stdin == null) execute(host, tool, args, echo) else executeWithInput(host, tool, args, echo, stdin)
        } catch (e: RemoteCommandFailedException) {
            throw ClusterToolFailedException(
                tool = tool,
                args = args,
                exitStatus = e.exitStatus,
                location = describe(host),
                stderr = e.stderr,
                stdout = e.stdout,
            )
        }
    }

    /**
     * The control node as a failure names it: `alias (public address)`, or `alias (private address)`
     * when the public one is blank (a node with no public address records a blank one).
     */
    private fun describe(host: Host): String = "${host.alias} (${host.public.ifBlank { host.private }})"

    private fun controlHost(): Host =
        clusterStateManager.load().getControlHost()?.toHost()
            ?: error("No control node found. Please ensure the environment is running.")

    private fun execute(
        host: Host,
        tool: ClusterTool,
        args: List<String>,
        echo: Boolean,
    ): String {
        val command = (listOf(tool.executable) + args.map { it.shellQuote() }).joinToString(" ")
        return remoteOps.executeRemotely(host, "KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG} $command", output = echo).text
    }

    private fun executeWithInput(
        host: Host,
        tool: ClusterTool,
        args: List<String>,
        echo: Boolean,
        stdin: String,
    ): String {
        val prefix = Constants.ClusterTooling.INPUT_FILE_PREFIX
        val suffix = Constants.ClusterTooling.INPUT_FILE_SUFFIX
        val localFile = Files.createTempFile(tempDir, prefix, suffix)
        val remotePath = "${Constants.ClusterTooling.REMOTE_INPUT_DIRECTORY}/$prefix-${UUID.randomUUID()}$suffix"
        try {
            Files.writeString(localFile, stdin)
            remoteOps.upload(host, localFile, remotePath)
            val remoteArgs = args.map { if (it == Constants.ClusterTooling.STDIN_ARGUMENT) remotePath else it }
            return execute(host, tool, remoteArgs, echo)
        } finally {
            // A cleanup failure is logged and never replaces the tool's own exception.
            runCatchingException { Files.deleteIfExists(localFile) }
                .onFailure { log.warn(it) { "Could not remove $localFile" } }
            runCatchingException { remoteOps.executeRemotely(host, "rm -f $remotePath", output = false) }
                .onFailure { log.warn(it) { "Could not remove ${host.alias}:$remotePath" } }
        }
    }
}
