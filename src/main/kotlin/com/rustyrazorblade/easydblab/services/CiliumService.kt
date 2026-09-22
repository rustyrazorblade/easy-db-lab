package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files

/**
 * Installs Cilium as the K3s CNI on the control node.
 *
 * Runs the `cilium` CLI baked into the AMI over SSH; nothing runs on the developer's machine.
 */
interface CiliumService {
    /**
     * Installs Cilium in ENI IPAM native-routing mode.
     *
     * @param controlHost The control node, where the `cilium` CLI and kubeconfig live.
     * @param vpcCidr The VPC CIDR, marked directly routable so pod traffic inside it is not masqueraded.
     */
    fun install(
        controlHost: Host,
        vpcCidr: String,
    ): Result<Unit>

    /**
     * Installs, on the control node, the nftables chain that masquerades Tailscale-forwarded
     * traffic ahead of Cilium's NAT chain, so the tailnet subnet route reaches the db nodes.
     *
     * Cilium's `CILIUM_POST_nat` ACCEPTs packets to cluster nodes before `ts-postrouting` can
     * masquerade them, so a tailnet packet to a db node leaves with a `100.x` source and never
     * gets a reply. Only needed when Tailscale is enabled; the rule is inert without the mark.
     *
     * @param controlHost The control node, which is the Tailscale subnet router.
     */
    fun installTailscaleMasquerade(controlHost: Host): Result<Unit>
}

/**
 * Default [CiliumService]: one `cilium install` (or `cilium upgrade`, when the release already
 * exists) invocation with the ENI native-routing flag set, the portmap CNI plugin chained so
 * `hostPort` works, metrics enabled on the agent and the operator, and Hubble UI exposed as a
 * NodePort. Both verbs take the same flag list, so a re-run
 * of `up` converges a changed flag instead of failing on the release name. Records the
 * install window on [CiliumInstallAnnotator] so `up` can mark it on Grafana once Grafana exists.
 */
class DefaultCiliumService(
    private val remoteOps: RemoteOperationsService,
    private val eventBus: EventBus,
    private val installAnnotator: CiliumInstallAnnotator,
) : CiliumService {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val INSTALLED = "installed"
        const val RELEASE_PROBE =
            "KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG} helm status cilium -n ${Constants.Cilium.NAMESPACE} " +
                ">/dev/null 2>&1 && echo $INSTALLED || echo absent"
        private const val MASQUERADE_SCRIPT = "install-tailscale-masquerade.sh"
        private const val MASQUERADE_SCRIPT_RESOURCE = "/com/rustyrazorblade/easydblab/services/$MASQUERADE_SCRIPT"
        const val MASQUERADE_REMOTE_PATH = "/tmp/$MASQUERADE_SCRIPT"
    }

    override fun install(
        controlHost: Host,
        vpcCidr: String,
    ): Result<Unit> =
        runCatching {
            // The install window on the Grafana timeline starts here, probe included.
            installAnnotator.installStarted()
            // Converge, do not fail: a re-run of `up` after a later failure hits this hook again,
            // and `cilium install` refuses a release name that is in use. `helm status` exits 0
            // when the release exists and non-zero when it does not, which is the cleanest
            // installed-or-not signal (`cilium status` also exits non-zero for an unhealthy
            // install). The `&& || echo` keeps the probe's own exit at 0 so only an SSH or helm
            // failure propagates.
            val installed = remoteOps.executeRemotely(controlHost, RELEASE_PROBE, output = false).text.trim() == INSTALLED
            val verb = if (installed) "upgrade" else "install"
            eventBus.emit(if (installed) Event.Cilium.Upgrading else Event.Cilium.Installing)
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
                    add(verb)
                    add("--version")
                    add(Constants.Cilium.VERSION)
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
                    // Pin Cilium's devices to the ENA interfaces. Auto-detection also picks up
                    // tailscale0 (MTU 1280) and Cilium's MTU updater then lowers every device,
                    // ens5/ens6 included, to the minimum — 1280 instead of the 9001 AWS offers.
                    set("devices=ens+")
                    // Explicitly off: keep K3s kube-proxy and avoid Cilium's BPF host
                    // routing, which blackholes node SSH:22 / apiserver:6443 (cilium#46010).
                    set("kubeProxyReplacement=false")
                    // Host networking stays on the kernel stack so node SSH/API stay reachable.
                    set("bpf.hostLegacyRouting=true")
                    // hostPort: with kube-proxy replacement off, Cilium does not implement
                    // hostPort itself; the portmap CNI plugin, chained after cilium-cni, does.
                    // The binary comes from K3s — start-k3s-*.sh links it into /opt/cni/bin.
                    set("cni.chainingMode=portmap")
                    set("ipv4NativeRoutingCIDR=$vpcCidr")
                    set("k8sServiceHost=${controlHost.private}")
                    set("k8sServicePort=6443")
                    set("operator.replicas=1")
                    set("hubble.relay.enabled=true")
                    set("hubble.ui.enabled=true")
                    // Single-quoted so the remote shell does not brace-expand the list into
                    // seven separate tokens (which fed cilium install an invalid value).
                    set("hubble.metrics.enabled='{dns,drop,tcp,flow,port-distribution,icmp,http}'")
                    // Agent (9962) and operator (9963) Prometheus endpoints; the OTel collector
                    // scrapes both (see OtelManifestBuilder.buildCniScrapeJobs).
                    set("prometheus.enabled=true")
                    set("operator.prometheus.enabled=true")
                    // Hubble UI as a NodePort so it is reachable on any node's private IP.
                    set("hubble.ui.service.type=NodePort")
                    set("hubble.ui.service.nodePort=${Constants.Cilium.HUBBLE_UI_NODE_PORT}")
                }.joinToString(" ")

            remoteOps.executeRemotely(controlHost, command)
            log.info { "Cilium installed successfully" }
            installAnnotator.installFinished()
            eventBus.emit(Event.Cilium.Installed)
        }.onFailure { e ->
            log.error(e) { "Failed to install Cilium" }
            val error = e.message ?: "unknown error"
            installAnnotator.installFailed(error)
            eventBus.emit(Event.Cilium.InstallFailed(error))
        }

    override fun installTailscaleMasquerade(controlHost: Host): Result<Unit> =
        runCatching {
            eventBus.emit(Event.Cilium.TailscaleMasqueradeInstalling(controlHost.alias))
            val script =
                javaClass.getResourceAsStream(MASQUERADE_SCRIPT_RESOURCE)
                    ?: error("Script not found on classpath: $MASQUERADE_SCRIPT_RESOURCE")
            val tempFile = Files.createTempFile("edl-", "-$MASQUERADE_SCRIPT")
            try {
                script.use { input -> Files.newOutputStream(tempFile).use { output -> input.copyTo(output) } }
                remoteOps.upload(controlHost, tempFile, MASQUERADE_REMOTE_PATH)
            } finally {
                Files.deleteIfExists(tempFile)
            }
            remoteOps.executeRemotely(controlHost, "sudo bash $MASQUERADE_REMOTE_PATH && rm -f $MASQUERADE_REMOTE_PATH")
            log.info { "Tailscale masquerade chain installed on ${controlHost.alias}" }
            eventBus.emit(Event.Cilium.TailscaleMasqueradeInstalled(controlHost.alias))
        }
}
