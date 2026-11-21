package com.rustyrazorblade.easydblab.commands.socks

import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.NetworkMode
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.proxy.WorkspaceShellTools
import com.rustyrazorblade.easydblab.proxy.effectiveNetworkMode
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Starts the SOCKS5 tunnel to the cluster, or keeps the one that runs, and records its port.
 *
 * Every other command that needs the tunnel starts it on its own; this one exists for what runs
 * outside the CLI. The tool wrappers in `<workspace>/bin/` read the port from the proxy env file and
 * fail until one is recorded, for example after `socks stop`, and a browser needs the port to reach
 * the cluster's web UIs. It writes the wrappers and records the cluster's network mode first; in
 * `tailscale` or `direct` mode there is no tunnel to start. A workspace whose environment is in the
 * direct network mode (K3s in Docker, a kubeconfig cluster) needs none even before its cluster runs.
 */
@Command(
    name = "start",
    description = ["Start the SOCKS5 tunnel to the cluster (or reuse the running one) and record its port"],
)
class SocksStart : PicoBaseCommand() {
    private val socksProxyService: SocksProxyService by inject()
    private val workspaceShellTools: WorkspaceShellTools by inject()

    override fun execute() {
        val state = clusterStateManager.takeIf { it.exists() }?.load()
        if (state != null && state.effectiveNetworkMode() == NetworkMode.Direct) {
            workspaceShellTools.prepare(context.workingDirectory, state)
            eventBus.emit(Event.Proxy.TunnelNotNeeded(NetworkMode.Direct.id))
            return
        }
        val controlHost = state?.takeIf { it.isInfrastructureUp() }?.getControlHost()
        if (state == null || controlHost == null) {
            eventBus.emit(Event.Proxy.NoRunningCluster(context.workingDirectory.absolutePath))
            throw CommandFailedException("no running cluster in ${context.workingDirectory.absolutePath}")
        }
        workspaceShellTools.prepare(context.workingDirectory, state)
        val mode = state.effectiveNetworkMode()
        if (!mode.needsTunnel) {
            eventBus.emit(Event.Proxy.TunnelNotNeeded(mode.id))
            return
        }
        val proxy = socksProxyService.ensureRunning(controlHost)
        eventBus.emit(Event.Proxy.TunnelReady(port = proxy.localPort, reused = proxy.reused))
    }
}
