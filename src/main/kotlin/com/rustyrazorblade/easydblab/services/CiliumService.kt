package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.github.oshai.kotlinlogging.KotlinLogging

interface CiliumService {
    fun install(controlHost: Host): Result<Unit>
}

class DefaultCiliumService(
    private val remoteOps: RemoteOperationsService,
    private val eventBus: EventBus,
) : CiliumService {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val CILIUM_VERSION = "1.19.4"
    }

    override fun install(controlHost: Host): Result<Unit> =
        runCatching {
            eventBus.emit(Event.Cilium.Installing)
            eventBus.emit(Event.Cilium.InstallingChart)

            // VXLAN tunnel mode: Cilium encapsulates pod traffic rather than installing
            // native host routes. In native routing mode, Cilium's own eBPF programs
            // intercept the agent's outbound TCP to the API server during bootstrap,
            // causing an i/o timeout before the agent can finish initialization.
            // VXLAN avoids this by keeping host-to-host traffic outside eBPF interception.
            //
            // Direct API server endpoint so worker-node agents don't use the ClusterIP,
            // which requires Cilium itself to route — a bootstrap deadlock.
            val command =
                buildList {
                    add("KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG}")
                    add("cilium")
                    add("install")
                    add("--version")
                    add(CILIUM_VERSION)
                    add("--set")
                    add("k8sServiceHost=${controlHost.private}")
                    add("--set")
                    add("k8sServicePort=6443")
                    add("--set")
                    add("tunnelProtocol=vxlan")
                    add("--set")
                    add("routingMode=tunnel")
                    add("--set")
                    add("operator.replicas=1")
                    add("--set")
                    add("hubble.relay.enabled=true")
                    add("--set")
                    add("hubble.ui.enabled=true")
                    add("--set")
                    add("hubble.metrics.enabled={dns,drop,tcp,flow,port-distribution,icmp,http}")
                }.joinToString(" ")

            remoteOps.executeRemotely(controlHost, command)
            log.info { "Cilium installed successfully" }
            eventBus.emit(Event.Cilium.Installed)
        }.onFailure { e ->
            log.error(e) { "Failed to install Cilium" }
            eventBus.emit(Event.Cilium.InstallFailed(e.message ?: "unknown error"))
        }
}
