package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.configuration.ClusterState
import java.io.File

/**
 * Prepares a workspace for the shell-side tools: the wrappers in `<workspace>/bin/` and how the
 * cluster is reached (the Tailscale flag and the network mode) in the proxy env file.
 *
 * `up` and a VPC restore both build a workspace that `env.sh` users and kit processes use right
 * away, before any other command has run, so both write them together. A Tailscale or direct
 * cluster never starts a tunnel, and without the flags the wrappers could not tell it from a SOCKS
 * cluster whose tunnel is down.
 *
 * @param installer writes the wrappers
 */
class WorkspaceShellTools(
    private val installer: ToolWrapperInstaller,
) {
    /**
     * Writes the wrappers and records how the cluster in [state] is reached in [workspace]'s proxy env file.
     *
     * @throws IllegalStateException if `bin/` holds a tool file that easy-db-lab did not write
     */
    fun prepare(
        workspace: File,
        state: ClusterState,
    ) {
        installer.install(workspace)
        ProxyEnvFile(workspace).recordReachability(state)
    }
}
