package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.configuration.ClusterState
import java.io.File

/**
 * Prepares a workspace for the shell-side tools: the wrappers in `<workspace>/bin/`, `env.sh`, and
 * the cluster's network mode in the proxy env file.
 *
 * It is the only caller of [ProxyEnvFile.recordNetworkMode], and it always writes the wrappers and
 * `env.sh` first. A workspace made by an earlier release has wrappers and `env.sh` helpers
 * (`with-proxy`, `socks5-status`) that know only the old `EDL_TAILSCALE_ACTIVE` key. Recording the mode
 * converts the env file to `EDL_NETWORK_MODE`, and an old wrapper or helper on a converted Tailscale
 * file would fail with the `socks start` message. So the order is always new wrappers, new `env.sh`,
 * then new env file, and a shell that sources `env.sh` again gets helpers that read the new key.
 *
 * `up`, a VPC restore, `socks start` and the preflight of every `@RequiresProxy` command call it.
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
     * Writes the wrappers and `env.sh`, then records [state]'s network mode in [workspace]'s proxy
     * env file.
     *
     * @throws IllegalStateException if `bin/` holds a tool file that easy-db-lab did not write; the
     *   env file is then left as it was
     */
    fun prepare(
        workspace: File,
        state: ClusterState,
    ) {
        installer.install(workspace)
        envScript.write(workspace, state)
        ProxyEnvFile(workspace).recordNetworkMode(state.networkMode)
    }
}
