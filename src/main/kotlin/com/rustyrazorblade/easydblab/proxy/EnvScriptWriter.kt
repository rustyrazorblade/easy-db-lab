package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.configuration.ClusterConfigWriter
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.providers.ssh.SshRoute
import com.rustyrazorblade.easydblab.writeTextAtomically
import java.io.File
import java.io.StringWriter

/**
 * Writes a workspace's `env.sh` from the packaged template and the cluster state.
 *
 * `env.sh` defines `with-proxy` and `socks5-status`, which read the network mode from the proxy env
 * file. A workspace made by an earlier release has an `env.sh` whose helpers know only the old key, so
 * [WorkspaceShellTools] rewrites it before it converts the env file: a user who sources `env.sh` again
 * then always gets helpers that read the new key.
 *
 * The file is replaced by an atomic rename, so a shell sourcing it never reads half of it.
 *
 * @param sshRoute supplies each host's `ProxyCommand` for the fallback `sshConfig` that `env.sh`
 *   writes when the workspace has none, so it routes hosts as the real `sshConfig` does
 */
class EnvScriptWriter(
    private val sshRoute: SshRoute,
) {
    /** Writes `<workspace>/env.sh` for [state]. */
    fun write(
        workspace: File,
        state: ClusterState,
    ) {
        val text = StringWriter()
        text.buffered().use { writer ->
            ClusterConfigWriter.writeEnvironmentFile(writer, state.hosts, state.clusterLabelName(), proxyCommandsFor(state.hosts))
        }
        File(workspace, FILE_NAME).writeTextAtomically(text.toString())
    }

    /** The `ProxyCommand` for each host alias whose route needs one; empty under the direct transport. */
    fun proxyCommandsFor(hosts: Map<ServerType, List<ClusterHost>>): Map<String, String> =
        hosts.values
            .flatten()
            .mapNotNull { host -> sshRoute.proxyCommand(host.toHost())?.let { host.alias to it } }
            .toMap()

    companion object {
        /** The workspace's environment script. */
        const val FILE_NAME = "env.sh"
    }
}
