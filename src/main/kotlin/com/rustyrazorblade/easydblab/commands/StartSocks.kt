package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.proxy.ProxyEnvFile
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Starts the SOCKS5 tunnel to the cluster, or keeps the one that runs, and records its port.
 *
 * Every other command that needs the tunnel starts it on its own; this one exists for what runs
 * outside the CLI. The tool wrappers in `<workspace>/bin/` read the port from the proxy env file and
 * fail until one is recorded, for example after `stop-socks`, and a browser needs the port to reach
 * the cluster's web UIs. On a Tailscale cluster there is no tunnel to start.
 */
@Command(
    name = "start-socks",
    description = ["Start the SOCKS5 tunnel to the cluster (or reuse the running one) and record its port"],
)
class StartSocks : PicoBaseCommand() {
    private val socksProxyService: SocksProxyService by inject()
    private val proxyEnvFile: ProxyEnvFile by inject()

    override fun execute() {
        val state = clusterStateManager.takeIf { it.exists() }?.load()
        val controlHost = state?.takeIf { it.isInfrastructureUp() }?.getControlHost()
        if (state == null || controlHost == null) {
            eventBus.emit(Event.Proxy.NoRunningCluster(context.workingDirectory.absolutePath))
            throw CommandFailedException("no running cluster in ${context.workingDirectory.absolutePath}")
        }
        proxyEnvFile.recordTailscale(state.isTailscaleEnabled())
        if (state.isTailscaleEnabled()) {
            eventBus.emit(Event.Proxy.TunnelNotNeeded)
            return
        }
        val proxy = socksProxyService.ensureRunning(controlHost)
        eventBus.emit(Event.Proxy.TunnelReady(port = proxy.localPort, reused = proxy.reused))
    }
}
