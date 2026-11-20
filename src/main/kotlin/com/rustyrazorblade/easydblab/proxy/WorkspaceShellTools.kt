package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.configuration.ClusterState
import java.io.File

/**
 * Prepares a workspace for the shell-side tools: the wrappers in `<workspace>/bin/`, `env.sh`, and
 * the cluster's network mode in the proxy env file.
 *
 * It is the only caller of [ProxyEnvFile.recordNetworkMode], and it always writes the wrappers first.
 * A workspace made by an earlier release has wrappers and `env.sh` helpers (`with-proxy`,
 * `socks5-status`) that know only the old `EDL_TAILSCALE_ACTIVE` key. Recording the mode converts the
 * env file to `EDL_NETWORK_MODE`, and an old wrapper or helper on a converted Tailscale file would
 * fail with the `socks start` message. So the order is always new wrappers, new `env.sh`, then new
 * env file, and a shell that sources `env.sh` again gets helpers that read the new key.
 *
 * `up` and a VPC restore call [prepareWithEnvScript], which always rewrites `env.sh` with the
 * `ProxyCommand`s they already built for `sshConfig`. `socks start` and the preflight of every
 * `@RequiresProxy` command call [prepare], which rewrites `env.sh` only while the env file does not
 * yet record `EDL_NETWORK_MODE`: that is the one write that converts it. Building `env.sh` asks the
 * SSH route for each host's `ProxyCommand`, which under the `ssm` transport resolves the SSM
 * credentials, so a command on a converted workspace does not pay for it.
 *
 * A cluster that needs no tunnel never starts one, and without the mode the wrappers could not tell
 * it from a SOCKS cluster whose tunnel is down.
 *
 * @param installer writes the wrappers
 * @param envScript writes `env.sh`
 */
class WorkspaceShellTools(
    private val installer: ToolWrapperInstaller,
    private val envScript: EnvScriptWriter,
) {
    /**
     * Writes the wrappers, rewrites `env.sh` when the env file is about to be converted, then
     * records [state]'s network mode in [workspace]'s proxy env file.
     *
     * @throws IllegalStateException if `bin/` holds a tool file that easy-db-lab did not write; the
     *   env file is then left as it was
     */
    fun prepare(
        workspace: File,
        state: ClusterState,
    ) {
        installer.install(workspace)
        val envFile = ProxyEnvFile(workspace)
        if (!envFile.recordsNetworkMode()) envScript.write(workspace, state, envScript.proxyCommandsFor(state.hosts))
        envFile.recordNetworkMode(state.networkMode)
    }

    /**
     * Writes the wrappers and `env.sh` (its fallback `sshConfig` routed by [proxyCommands]), then
     * records [state]'s network mode.
     *
     * @throws IllegalStateException as [prepare] does
     */
    fun prepareWithEnvScript(
        workspace: File,
        state: ClusterState,
        proxyCommands: Map<String, String>,
    ) {
        installer.install(workspace)
        envScript.write(workspace, state, proxyCommands)
        ProxyEnvFile(workspace).recordNetworkMode(state.networkMode)
    }
}
