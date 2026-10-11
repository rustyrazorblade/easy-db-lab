package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.proxy.TunnelStopResult
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Stops the workspace's SOCKS5 tunnel and removes its port from the proxy env file, leaving the
 * cluster running. Wrapped tools then fail with a pointer to `start-socks` until it runs again; the
 * next CLI command that needs the tunnel starts a new one on its own.
 */
@Command(
    name = "stop-socks",
    description = ["Stop the SOCKS5 tunnel to the cluster without tearing the cluster down"],
)
class StopSocks : PicoBaseCommand() {
    private val socksProxyService: SocksProxyService by inject()

    override fun execute() {
        when (val result = socksProxyService.stop()) {
            is TunnelStopResult.Stopped -> eventBus.emit(Event.Proxy.TunnelStopped(result.pid))
            TunnelStopResult.NotRunning -> eventBus.emit(Event.Proxy.NoTunnelRunning)
            is TunnelStopResult.StopFailed -> {
                eventBus.emit(Event.Proxy.TunnelStopFailed(result.pid))
                throw CommandFailedException("the SOCKS5 tunnel process ${result.pid} did not stop")
            }
        }
    }
}
