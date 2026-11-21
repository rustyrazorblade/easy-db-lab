package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * What the command executor does for a `@RequiresProxy` command before it runs: start or reuse the
 * SOCKS tunnel when the cluster is provisioned, its infrastructure is UP, and it is reached through
 * the tunnel ([reachedBySocksTunnel]: a Socks exposure in the `socks` network mode). A Direct or
 * PortForward cluster never gets one, and neither does one in the `tailscale` or `direct` network
 * mode; an environment other than EC2 (K3s in Docker, a kubeconfig cluster) is always `direct`
 * ([effectiveNetworkMode]), whatever else its state says.
 *
 * Whenever the workspace has a cluster state, it also writes the wrappers and records the cluster's
 * network mode in the proxy env file, through [WorkspaceShellTools]. A cluster that needs no tunnel
 * never starts one, so without this the tool wrappers and `env.sh` would have no record of how to
 * reach the cluster. A tunnel recorded earlier is left recorded and running when the mode says no
 * tunnel is needed: the wrappers ignore it, and `socks stop` and `down` stop it.
 *
 * Before every command, with or without the annotation, the executor also has it restore the mode
 * ([recordReachability]) when the env file has lost it or records another one, so a lost key is back
 * before the command or a `bin/` wrapper it launches reads it.
 *
 * A failure to establish the tunnel propagates: the annotation says the command cannot work without it.
 *
 * The tunnel it starts or reuses is recorded for the command about to run ([tunnelForCommand]), so
 * a command that reports the tunnel (`up`) says what happened in this invocation. Asking the service
 * again would find the tunnel the preflight just started and call it "already running". The record
 * is per thread, like [ProxyAvailability], and the executor clears it before every command.
 */
class ProxyPreflight(
    private val clusterStateManager: ClusterStateManager,
    private val socksProxyService: SocksProxyService,
    private val workspaceShellTools: WorkspaceShellTools,
    private val workspace: File,
) {
    private val obtained = ThreadLocal<SocksProxyState?>()

    /**
     * The tunnel this thread's last [ensureTunnel] started or reused, with its `reused` flag as the
     * service returned it; null when it obtained none or [clear] has run since.
     */
    fun tunnelForCommand(): SocksProxyState? = obtained.get()

    /** Forgets the recorded tunnel. The executor calls it before every command. */
    fun clear() {
        obtained.remove()
    }

    /**
     * Restores the cluster's network mode in the env file, without starting a tunnel, when the file
     * does not record the mode the state resolves to; it goes through [WorkspaceShellTools.prepare],
     * so the wrappers are written first. It writes nothing when the file already agrees, and nothing
     * in a directory with no readable cluster state: the command that loads the state reports that.
     */
    fun recordReachability() {
        if (!clusterStateManager.exists()) return
        val state =
            runCatching { clusterStateManager.load() }.getOrElse { e ->
                log.debug(e) { "Could not load cluster state to record its network mode" }
                return
            }
        if (ProxyEnvFile(workspace).read().networkMode != state.effectiveNetworkMode()) {
            workspaceShellTools.prepare(workspace, state)
        }
    }

    /**
     * Starts or reuses the tunnel when the cluster needs one; does nothing in a directory with no cluster.
     *
     * A `state.json` that cannot be loaded fails here, with the load's own message. Several
     * `@RequiresProxy` commands (`stress list`, `kit uninstall`, ...) never load the state themselves,
     * so skipping the tunnel would surface later as a connect timeout that names no cause.
     */
    fun ensureTunnel() {
        obtained.remove()
        if (!clusterStateManager.exists()) return
        val state: ClusterState = clusterStateManager.load()
        workspaceShellTools.prepare(workspace, state)
        if (!state.isInfrastructureUp() || !state.reachedBySocksTunnel()) return
        val controlHost = state.getControlHost() ?: return
        obtained.set(socksProxyService.ensureRunning(controlHost))
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
