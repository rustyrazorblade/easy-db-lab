package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path

class CiliumServiceTest : BaseKoinTest() {
    private val emittedEvents = mutableListOf<Event>()
    private lateinit var mockRemoteOps: RemoteOperationsService

    private val controlHost =
        Host(
            public = "54.1.2.3",
            private = "10.0.0.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<EventBus> {
                    EventBus().also { bus ->
                        bus.addListener(
                            object : EventListener {
                                override fun onEvent(envelope: EventEnvelope) {
                                    emittedEvents.add(envelope.event)
                                }

                                override fun close() {}
                            },
                        )
                    }
                }
                single<RemoteOperationsService> { mock<RemoteOperationsService>().also { mockRemoteOps = it } }
                single { mock<GrafanaDashboardService>() }
                single { CiliumInstallAnnotator(get()) }
            },
        )

    @BeforeEach
    fun setup() {
        emittedEvents.clear()
        mockRemoteOps = getKoin().get()
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any())).doReturn(Response(""))
    }

    private fun annotator(): CiliumInstallAnnotator = getKoin().get()

    private fun makeService(): DefaultCiliumService =
        DefaultCiliumService(
            remoteOps = getKoin().get(),
            eventBus = getKoin().get(),
            installAnnotator = annotator(),
        )

    private val vpcCidr = "10.0.0.0/16"

    /** The two commands `install` issues, in order: the helm release probe, then the chart command. */
    private class CiliumCommands(
        val probe: String,
        val chartCommand: String,
    )

    private fun ciliumCommands(): CiliumCommands {
        val captor = argumentCaptor<String>()
        verify(mockRemoteOps, times(2)).executeRemotely(eq(controlHost), captor.capture(), any(), any())
        return CiliumCommands(probe = captor.firstValue, chartCommand = captor.secondValue)
    }

    /** Every `--set key=value` pair of a `cilium install|upgrade` command, in order. */
    private fun setFlags(command: String): List<String> = Regex("--set (\\S+)").findAll(command).map { it.groupValues[1] }.toList()

    private fun releaseInstalled() {
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any())).thenAnswer { invocation ->
            val command = invocation.getArgument<String>(1)
            if (command == DefaultCiliumService.RELEASE_PROBE) Response("installed\n") else Response("")
        }
    }

    @Test
    fun `install probes the helm release with an always-zero-exit command before choosing a verb`() {
        makeService().install(controlHost, vpcCidr)

        val commands = ciliumCommands()
        assertThat(commands.probe).isEqualTo(
            "KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG} helm status cilium -n kube-system >/dev/null 2>&1 && echo installed || echo absent",
        )
        assertThat(commands.chartCommand).startsWith("KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG} cilium install --version")
        assertThat(emittedEvents).contains(Event.Cilium.Installing).doesNotContain(Event.Cilium.Upgrading)
    }

    @Test
    fun `install upgrades in place with the identical flag list when the release already exists`() {
        makeService().install(controlHost, vpcCidr)
        val freshInstall = ciliumCommands().chartCommand
        reset(mockRemoteOps)
        emittedEvents.clear()
        releaseInstalled()

        makeService().install(controlHost, vpcCidr)

        val rerun = ciliumCommands().chartCommand
        assertThat(rerun).startsWith("KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG} cilium upgrade --version")
        assertThat(rerun).doesNotContain("cilium install")
        assertThat(setFlags(rerun)).isNotEmpty.isEqualTo(setFlags(freshInstall))
        assertThat(emittedEvents).contains(Event.Cilium.Upgrading, Event.Cilium.Installed).doesNotContain(Event.Cilium.Installing)
    }

    @Test
    fun `install fails and records the failure when the release probe itself cannot run`() {
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any()))
            .doThrow(RuntimeException("ssh: connect to host 10.0.0.1 port 22: Connection refused"))

        val result = makeService().install(controlHost, vpcCidr)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageContaining("Connection refused")
        verify(mockRemoteOps, times(1)).executeRemotely(any(), any(), any(), any())
        assertThat(emittedEvents.filterIsInstance<Event.Cilium.InstallFailed>()).hasSize(1)
    }

    @Test
    fun `install issues cilium install with ENI native-routing flags and no VXLAN tunnel`() {
        makeService().install(controlHost, vpcCidr)

        val commandCaptor = ciliumCommands()

        val command = commandCaptor.chartCommand
        assertThat(command).contains("cilium install")
        assertThat(command).contains("--version 1.19.4")
        assertThat(command).contains("k8sServiceHost=${controlHost.private}")
        assertThat(command).contains("k8sServicePort=6443")
        assertThat(command).contains("ipam.mode=eni")
        assertThat(command).contains("eni.enabled=true")
        assertThat(command).contains("routingMode=native")
        assertThat(command).contains("endpointRoutes.enabled=true")
        assertThat(command).contains("enableIPv4Masquerade=true")
        assertThat(command).contains("egressMasqueradeInterfaces=ens+")
        assertThat(command).contains("kubeProxyReplacement=false")
        assertThat(command).contains("bpf.hostLegacyRouting=true")
        assertThat(command).contains("ipv4NativeRoutingCIDR=$vpcCidr")
        assertThat(command).contains("operator.replicas=1")
        assertThat(command).contains("hubble.relay.enabled=true")
        assertThat(command).contains("hubble.ui.enabled=true")
        assertThat(command).doesNotContain("bpf.masquerade")
        assertThat(command).doesNotContain("tunnelProtocol=vxlan")
        assertThat(command).doesNotContain("routingMode=tunnel")
    }

    /**
     * With kubeProxyReplacement=false Cilium does not implement hostPort itself; the portmap CNI
     * plugin chained behind cilium-cni does. Without it hostPort kits (Trino, Presto, Flink) have
     * no host listener. The upgrade path must carry it too, or a re-run of `up` drops it.
     */
    @Test
    fun `install and upgrade both chain the portmap CNI plugin so hostPort works`() {
        makeService().install(controlHost, vpcCidr)
        val install = ciliumCommands().chartCommand
        reset(mockRemoteOps)
        releaseInstalled()

        makeService().install(controlHost, vpcCidr)
        val upgrade = ciliumCommands().chartCommand

        assertThat(install).contains("cilium install")
        assertThat(upgrade).contains("cilium upgrade")
        assertThat(setFlags(install)).contains("cni.chainingMode=portmap")
        assertThat(setFlags(upgrade)).contains("cni.chainingMode=portmap")
    }

    /**
     * With `--flannel-backend=none` K3s leaves containerd on its default CNI bin dir, /opt/cni/bin,
     * where Cilium installs only cilium-cni and loopback. Once portmap is chained, every pod
     * sandbox execs `/opt/cni/bin/portmap`, so each node links K3s's bundled plugin (its stable
     * `data/cni` dir) there before the K3s service starts.
     */
    @Test
    fun `start-k3s scripts link the K3s-bundled portmap plugin into the default CNI bin dir`() {
        listOf("start-k3s-server.sh", "start-k3s-agent.sh").forEach { name ->
            val scriptPath = "/com/rustyrazorblade/easydblab/services/$name"
            val script =
                javaClass.getResourceAsStream(scriptPath)?.bufferedReader()?.readText()
                    ?: error("$name not found on classpath at $scriptPath")

            val link = script.lines().map { it.trim() }.single { it.startsWith("ln ") && it.contains("portmap") }
            assertThat(link)
                .describedAs(name)
                .isEqualTo("ln -sfn /var/lib/rancher/k3s/data/cni/portmap /opt/cni/bin/portmap")
            assertThat(script.indexOf(link))
                .describedAs("$name links portmap before starting K3s")
                .isLessThan(script.indexOf("systemctl start"))
        }
    }

    @Test
    fun `install single-quotes the Hubble metrics list so the remote shell does not brace-expand it`() {
        makeService().install(controlHost, vpcCidr)

        val commandCaptor = ciliumCommands()

        val command = commandCaptor.chartCommand
        assertThat(command).contains("hubble.metrics.enabled='{dns,drop,tcp,flow,port-distribution,icmp,http}'")
    }

    @Test
    fun `install enables the agent and operator Prometheus endpoints the collector scrapes`() {
        makeService().install(controlHost, vpcCidr)

        val commandCaptor = ciliumCommands()

        val command = commandCaptor.chartCommand
        assertThat(command).contains("--set prometheus.enabled=true")
        assertThat(command).contains("--set operator.prometheus.enabled=true")
    }

    @Test
    fun `install exposes Hubble UI as a NodePort on the constant port`() {
        makeService().install(controlHost, vpcCidr)

        val commandCaptor = ciliumCommands()

        val command = commandCaptor.chartCommand
        assertThat(command).contains("--set hubble.ui.service.type=NodePort")
        assertThat(command).contains("--set hubble.ui.service.nodePort=${Constants.Cilium.HUBBLE_UI_NODE_PORT}")
        // K3s NodePort range.
        assertThat(Constants.Cilium.HUBBLE_UI_NODE_PORT).isBetween(30000, 32767)
    }

    @Test
    fun `install records a started and a finished annotation around the remote command`() {
        makeService().install(controlHost, vpcCidr)

        val texts = annotator().pending.map { it.text }
        assertThat(texts).containsExactly(CiliumInstallAnnotator.STARTED_TEXT, CiliumInstallAnnotator.FINISHED_TEXT)
        assertThat(annotator().pending).allSatisfy { assertThat(it.tags).contains(Constants.Cilium.ANNOTATION_TAG) }
    }

    @Test
    fun `install records a failed annotation carrying the error when the remote command throws`() {
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any()))
            .doThrow(RuntimeException("cilium install failed"))

        makeService().install(controlHost, vpcCidr)

        val texts = annotator().pending.map { it.text }
        assertThat(texts).hasSize(2)
        assertThat(texts[0]).isEqualTo(CiliumInstallAnnotator.STARTED_TEXT)
        assertThat(texts[1]).startsWith(CiliumInstallAnnotator.FAILED_TEXT).contains("cilium install failed")
    }

    @Test
    fun `install threads the provided VPC CIDR verbatim into the native-routing flag`() {
        val distinctCidr = "172.31.0.0/16"

        makeService().install(controlHost, distinctCidr)

        val commandCaptor = ciliumCommands()

        assertThat(commandCaptor.chartCommand).contains("ipv4NativeRoutingCIDR=$distinctCidr")
    }

    @Test
    fun `install emits Installing, InstallingChart, and Installed events on success`() {
        makeService().install(controlHost, vpcCidr)

        assertThat(emittedEvents).contains(Event.Cilium.Installing)
        assertThat(emittedEvents).contains(Event.Cilium.InstallingChart)
        assertThat(emittedEvents).contains(Event.Cilium.Installed)
    }

    @Test
    fun `install emits InstallFailed and returns failure when executeRemotely throws`() {
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any()))
            .doThrow(RuntimeException("cilium install failed"))

        val result = makeService().install(controlHost, vpcCidr)

        assertThat(result.isFailure).isTrue()
        val failedEvents = emittedEvents.filterIsInstance<Event.Cilium.InstallFailed>()
        assertThat(failedEvents).hasSize(1)
        assertThat(failedEvents.first().error).contains("cilium install failed")
    }

    @Test
    fun `start-k3s-server script disables flannel for Cilium CNI`() {
        val scriptPath = "/com/rustyrazorblade/easydblab/services/start-k3s-server.sh"
        val script =
            javaClass.getResourceAsStream(scriptPath)?.bufferedReader()?.readText()
                ?: error("start-k3s-server.sh not found on classpath at $scriptPath")

        assertThat(script).contains("--flannel-backend=none")
        assertThat(script).contains("--disable-network-policy")
    }

    /**
     * ServiceLB publishes every node IP as the traefik LoadBalancer ingress, and Cilium's port-0
     * wildcard for a LoadBalancer IP rejects pod traffic to node IPs — including kubelet probe
     * replies. Both add-ons must be off on the Cilium branch and untouched on the Flannel branch.
     */
    @Test
    fun `start-k3s-server script disables Traefik and ServiceLB only for Cilium CNI`() {
        val scriptPath = "/com/rustyrazorblade/easydblab/services/start-k3s-server.sh"
        val script =
            javaClass.getResourceAsStream(scriptPath)?.bufferedReader()?.readText()
                ?: error("start-k3s-server.sh not found on classpath at $scriptPath")

        val execArgLines = script.lines().filter { it.trim().startsWith("K3S_EXEC_ARGS=") }
        assertThat(execArgLines).hasSize(2)
        val (cilium, flannel) = execArgLines.partition { it.contains("--flannel-backend=none") }
        assertThat(cilium.single()).contains("--disable=traefik,servicelb")
        assertThat(flannel.single()).doesNotContain("--disable=")
    }

    @Test
    fun `installTailscaleMasquerade uploads the script to the control node and runs it with sudo`() {
        makeService().installTailscaleMasquerade(controlHost).getOrThrow()

        val pathCaptor = argumentCaptor<String>()
        verify(mockRemoteOps).upload(eq(controlHost), any<Path>(), pathCaptor.capture())
        assertThat(pathCaptor.firstValue).isEqualTo(DefaultCiliumService.MASQUERADE_REMOTE_PATH)

        val commandCaptor = argumentCaptor<String>()
        verify(mockRemoteOps).executeRemotely(eq(controlHost), commandCaptor.capture(), any(), any())
        assertThat(commandCaptor.firstValue)
            .isEqualTo("sudo bash /tmp/install-tailscale-masquerade.sh && rm -f /tmp/install-tailscale-masquerade.sh")

        assertThat(emittedEvents).contains(
            Event.Cilium.TailscaleMasqueradeInstalling("control0"),
            Event.Cilium.TailscaleMasqueradeInstalled("control0"),
        )
    }

    @Test
    fun `installTailscaleMasquerade returns failure when the remote script fails`() {
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any()))
            .doThrow(RuntimeException("nft: command not found"))

        val result = makeService().installTailscaleMasquerade(controlHost)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageContaining("nft")
    }

    /**
     * The script is the contract with the node: an nft NAT chain ahead of iptables POSTROUTING
     * (srcnat 100), matching Tailscale's forward mark, replacing itself on re-run, and loaded by
     * an enabled systemd unit so it survives a reboot.
     */
    @Test
    fun `tailscale masquerade script installs an idempotent boot-persistent nft chain at priority 90`() {
        val scriptPath = "/com/rustyrazorblade/easydblab/services/install-tailscale-masquerade.sh"
        val script =
            javaClass.getResourceAsStream(scriptPath)?.bufferedReader()?.readText()
                ?: error("install-tailscale-masquerade.sh not found on classpath at $scriptPath")

        assertThat(script).contains("type nat hook postrouting priority 90; policy accept;")
        assertThat(script).contains("meta mark & 0x00ff0000 == 0x00040000 oifname \"ens*\" masquerade")
        // Declare + delete + recreate: re-running replaces the table instead of duplicating rules.
        assertThat(script).contains("table ip edl_tailscale\ndelete table ip edl_tailscale\ntable ip edl_tailscale {")
        assertThat(script).contains("systemctl enable edl-tailscale-masquerade.service")
        assertThat(script).contains("systemctl restart edl-tailscale-masquerade.service")
        assertThat(script).contains("WantedBy=multi-user.target")
        assertThat(script).contains("RemainAfterExit=yes")
        assertThat(script).contains("set -euo pipefail")
    }

    @Test
    fun `install pins Cilium devices to the ENA interfaces so tailscale0 cannot lower the MTU`() {
        makeService().install(controlHost, vpcCidr)

        val commandCaptor = ciliumCommands()

        assertThat(commandCaptor.chartCommand).contains("--set devices=ens+")
    }
}
