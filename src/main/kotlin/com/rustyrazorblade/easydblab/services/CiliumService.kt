package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.github.oshai.kotlinlogging.KotlinLogging

interface CiliumService {
    fun install(
        controlHost: Host,
        vpcCidr: String,
    ): Result<Unit>
}

class DefaultCiliumService(
    private val remoteOps: RemoteOperationsService,
    private val eventBus: EventBus,
) : CiliumService {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val CILIUM_VERSION = "1.19.4"
    }

    override fun install(
        controlHost: Host,
        vpcCidr: String,
    ): Result<Unit> =
        runCatching {
            eventBus.emit(Event.Cilium.Installing)
            eventBus.emit(Event.Cilium.InstallingChart)

            // ENI IPAM native routing: pods receive real VPC-routable secondary IPs on the
            // node's ENIs, so cross-AZ pod traffic is routed by the VPC itself — no
            // encapsulation. This is the multi-AZ-safe way to run Cilium with no tunnel
            // (autoDirectNodeRoutes only works within a single L2 domain and breaks across
            // AZs). ipv4NativeRoutingCIDR marks the VPC CIDR as directly routable so traffic
            // to those IPs is not masqueraded. Masquerade stays enabled for egress to the
            // internet. Native routing (VPC-routable ENI pod IPs, no encapsulation) is the
            // actual performance win here and is unchanged.
            //
            // egressMasqueradeInterfaces=ens+ : ENI mode requires a NAMED egress masquerade
            // interface — with it empty the agent panics ("Egress masquerading interfaces
            // cannot be empty..."). The wildcard ens+ matches the Nitro primary NIC (ens5)
            // plus ENI secondaries; masquerading is done via iptables (not BPF). Do not
            // hardcode eth0 — that is wrong on Nitro instances.
            //
            // kubeProxyReplacement=false : explicitly OFF so K3s keeps its own kube-proxy.
            // Enabling it turns on BPF masquerade / BPF host routing on the primary NIC,
            // which blackholes the node's own inbound SSH:22 and kube-apiserver:6443 (see
            // cilium/cilium#46010 on 1.19.4). A live AWS test hit exactly this. Leaving it
            // false — and NOT setting bpf.masquerade — avoids the host-networking blackhole.
            //
            // bpf.hostLegacyRouting=true : keep host networking on the kernel stack so node
            // SSH / API-server reachability is preserved.
            //
            // Direct API server endpoint so worker-node agents don't use the ClusterIP,
            // which requires Cilium itself to route — a bootstrap deadlock.
            val command =
                buildList {
                    fun set(kv: String) {
                        add("--set")
                        add(kv)
                    }

                    add("KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG}")
                    add("cilium")
                    add("install")
                    add("--version")
                    add(CILIUM_VERSION)
                    set("ipam.mode=eni")
                    set("eni.enabled=true")
                    set("routingMode=native")
                    set("endpointRoutes.enabled=true")
                    set("enableIPv4Masquerade=true")
                    // ENI mode requires a named egress masquerade interface or the agent
                    // panics. ens+ wildcard covers the Nitro primary NIC (ens5) + ENI
                    // secondaries; masquerading via iptables. '+' is not a shell metachar,
                    // so it renders unquoted safely over the SSH command.
                    set("egressMasqueradeInterfaces=ens+")
                    // Explicitly off: keep K3s kube-proxy and avoid Cilium's BPF host
                    // routing, which blackholes node SSH:22 / apiserver:6443 (cilium#46010).
                    set("kubeProxyReplacement=false")
                    // Host networking stays on the kernel stack so node SSH/API stay reachable.
                    set("bpf.hostLegacyRouting=true")
                    set("ipv4NativeRoutingCIDR=$vpcCidr")
                    set("k8sServiceHost=${controlHost.private}")
                    set("k8sServicePort=6443")
                    set("operator.replicas=1")
                    set("hubble.relay.enabled=true")
                    set("hubble.ui.enabled=true")
                    // Single-quoted so the remote shell does not brace-expand the list into
                    // seven separate tokens (which fed cilium install an invalid value).
                    set("hubble.metrics.enabled='{dns,drop,tcp,flow,port-distribution,icmp,http}'")
                }.joinToString(" ")

            remoteOps.executeRemotely(controlHost, command)
            log.info { "Cilium installed successfully" }
            eventBus.emit(Event.Cilium.Installed)
        }.onFailure { e ->
            log.error(e) { "Failed to install Cilium" }
            eventBus.emit(Event.Cilium.InstallFailed(e.message ?: "unknown error"))
        }
}
