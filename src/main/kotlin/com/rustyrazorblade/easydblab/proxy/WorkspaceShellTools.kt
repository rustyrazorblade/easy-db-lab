package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.configuration.NetworkMode
import java.io.File

/**
 * Prepares a workspace for the shell-side tools: the wrappers in `<workspace>/bin/` and the
 * cluster's network mode in the proxy env file.
 *
 * It is the only caller of [ProxyEnvFile.recordNetworkMode], and it always installs the wrappers
 * first. A workspace made by an earlier release has wrappers that know only the old
 * `EDL_TAILSCALE_ACTIVE` key; recording the mode converts the env file to `EDL_NETWORK_MODE`, and an
 * old wrapper on a converted Tailscale file would fail with the `socks start` message. So the order
 * is always new wrappers, then new env file.
 *
 * `up`, a VPC restore, `socks start` and the preflight of every `@RequiresProxy` command call it.
 * A cluster that needs no tunnel never starts one, and without the mode the wrappers could not tell
 * it from a SOCKS cluster whose tunnel is down.
 *
 * @param installer writes the wrappers
 */
class WorkspaceShellTools(
    private val installer: ToolWrapperInstaller,
) {
    /**
     * Writes the wrappers and then records [mode] in [workspace]'s proxy env file.
     *
     * @throws IllegalStateException if `bin/` holds a tool file that easy-db-lab did not write; the
     *   env file is then left as it was
     */
    fun prepare(
        workspace: File,
        mode: NetworkMode,
    ) {
        installer.install(workspace)
        ProxyEnvFile(workspace).recordNetworkMode(mode)
    }
}
