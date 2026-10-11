package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import java.io.File

/**
 * Applies a rewritten `sshConfig` to the SOCKS tunnel that is already running on it.
 *
 * A command can start or reuse the tunnel before it rewrites the workspace's `sshConfig` (`up` on a
 * cluster that is already up does), and the running `ssh` read the old file. The writer of
 * `sshConfig` calls this when the content changed, so the tunnel picks up the new file at once
 * rather than at its next reconnect. It is an interface of its own so that writer does not depend on
 * the whole [SocksProxyService].
 */
fun interface SshConfigReloader {
    /**
     * [sshConfig] was just rewritten with new content, for the cluster reached through [gatewayHost].
     * A verified tunnel running on that file gets a new `ssh` on the same port, verified end to end
     * before this returns; with no such tunnel, nothing happens.
     */
    fun sshConfigChanged(
        sshConfig: File,
        gatewayHost: ClusterHost,
    )
}
