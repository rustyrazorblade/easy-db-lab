package com.rustyrazorblade.easydblab.proxy

import java.io.File

/**
 * Prepares a workspace for the shell-side tools: the wrappers in `<workspace>/bin/` and the
 * cluster's Tailscale flag in the proxy env file.
 *
 * `up` and a VPC restore both build a workspace that `env.sh` users and kit processes use right
 * away, before any other command has run, so both write the two together. A Tailscale cluster
 * never starts a tunnel, and without the flag the wrappers could not tell it from a SOCKS cluster
 * whose tunnel is down.
 *
 * @param installer writes the wrappers
 */
class WorkspaceShellTools(
    private val installer: ToolWrapperInstaller,
) {
    /**
     * Writes the wrappers and records [tailscaleActive] in [workspace]'s proxy env file.
     *
     * @throws IllegalStateException if `bin/` holds a tool file that easy-db-lab did not write
     */
    fun prepare(
        workspace: File,
        tailscaleActive: Boolean,
    ) {
        installer.install(workspace)
        ProxyEnvFile(workspace).recordTailscale(tailscaleActive)
    }
}
