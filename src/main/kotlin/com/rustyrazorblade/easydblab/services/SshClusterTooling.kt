package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.shellQuote

/**
 * [ClusterTooling] for a cluster reached over SSH: runs helm and kubectl on the control node, where
 * the tools are baked into the image and `k3s.yaml` is the kubeconfig.
 *
 * The command is `KUBECONFIG=<remote kubeconfig> <tool> <args>`, each argument passed through
 * `String.shellQuote`, so it reaches the remote shell as one word holding exactly its value: an
 * argument of shell-safe characters is joined as it is, and any other one (a space, `;`, `$`, which
 * an interpolated kit variable can hold) is single-quoted.
 *
 * Input (a manifest, helm values) is sent as the command's stdin over the same SSH channel, and
 * [ToolArg.Input] is rendered as `-`, so every call is one remote command and nothing is staged on
 * either machine.
 *
 * The control host is read from cluster state on every call and never cached, because `Server`,
 * `Repl` and `up` outlive a change of state.
 */
class SshClusterTooling(
    private val remoteOps: RemoteOperationsService,
    private val clusterStateManager: ClusterStateManager,
) : ClusterTooling {
    override fun run(
        tool: ClusterTool,
        args: List<String>,
        echo: Boolean,
    ): String {
        val host = controlHost()
        return failingAsTool(tool, args, host) {
            remoteOps.executeRemotely(host, command(tool, args), output = echo).text
        }
    }

    override fun runWithInput(
        tool: ClusterTool,
        args: List<ToolArg>,
        input: String,
        echo: Boolean,
    ): String {
        val toolArgs = args.toToolArguments(tool)
        val host = controlHost()
        return failingAsTool(tool, toolArgs, host) {
            remoteOps.executeRemotelyWithInput(host, command(tool, toolArgs), input, output = echo).text
        }
    }

    private fun command(
        tool: ClusterTool,
        args: List<String>,
    ): String =
        "KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG} " + (listOf(tool.executable) + args.map { it.shellQuote() }).joinToString(" ")

    /** Runs [call], turning the tool's non-zero exit into a [ClusterToolFailedException] that names [host]. */
    private fun failingAsTool(
        tool: ClusterTool,
        args: List<String>,
        host: Host,
        call: () -> String,
    ): String =
        try {
            call()
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

    /**
     * The control node as a failure names it: `alias (public address)`, or `alias (private address)`
     * when the public one is blank (a node with no public address records a blank one).
     */
    private fun describe(host: Host): String = "${host.alias} (${host.public.ifBlank { host.private }})"

    private fun controlHost(): Host =
        clusterStateManager.load().getControlHost()?.toHost()
            ?: error("No control node found. Please ensure the environment is running.")
}
