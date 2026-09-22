package com.rustyrazorblade.easydblab.commands.platform

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.services.CiliumConfigSummary
import com.rustyrazorblade.easydblab.services.CiliumInspection
import com.rustyrazorblade.easydblab.services.CiliumInspectionService
import com.rustyrazorblade.easydblab.services.CiliumInspectionServiceTest
import com.rustyrazorblade.easydblab.services.CiliumNodeSummary
import com.rustyrazorblade.easydblab.services.DefaultCiliumInspectionService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests `platform cni`: the Flannel short-circuit, the reads it issues on the control node
 * through the real [DefaultCiliumInspectionService], and the report rendering.
 */
class PlatformCniTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var mockRemoteOps: RemoteOperationsService

    private val controlHost =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = "10.0.0.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<ClusterStateManager>().also { mockClusterStateManager = it } }
                single<RemoteOperationsService> { mock<RemoteOperationsService>().also { mockRemoteOps = it } }
                single<CiliumInspectionService> { DefaultCiliumInspectionService(get()) }
            },
        )

    @BeforeEach
    fun setup() {
        mockClusterStateManager = getKoin().get()
        mockRemoteOps = getKoin().get()
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any())).thenAnswer { invocation ->
            val command = invocation.getArgument<String>(1)
            when {
                command.contains("get cm cilium-config") -> Response(CiliumInspectionServiceTest.CONFIG_JSON)
                command.contains("get ciliumnode") -> Response(CiliumInspectionServiceTest.NODES_JSON)
                else -> error("unexpected command: $command")
            }
        }
    }

    private fun state(
        cni: CniMode,
        hosts: Map<ServerType, List<ClusterHost>> = mapOf(ServerType.Control to listOf(controlHost)),
    ) = ClusterState(
        name = "test",
        versions = mutableMapOf(),
        hosts = hosts,
        initConfig = InitConfig(region = "us-west-2", cni = cni),
    )

    @Test
    fun `on a Flannel cluster it prints the one-line answer and never touches the control node`() {
        whenever(mockClusterStateManager.load()).thenReturn(state(CniMode.Flannel))

        PlatformCni().execute()

        verify(mockRemoteOps, never()).executeRemotely(any(), any(), any(), any())
        assertThat(PlatformCni.FLANNEL_TEXT).contains("flannel")
    }

    @Test
    fun `on a Cilium cluster it reads cilium-config and the CiliumNode list from the control node`() {
        whenever(mockClusterStateManager.load()).thenReturn(state(CniMode.Cilium))

        PlatformCni().execute()

        val hostCaptor = argumentCaptor<Host>()
        val commandCaptor = argumentCaptor<String>()
        verify(mockRemoteOps, atLeastOnce()).executeRemotely(hostCaptor.capture(), commandCaptor.capture(), any(), any())
        assertThat(hostCaptor.allValues.map { it.alias }).containsOnly("control0")
        assertThat(commandCaptor.allValues).anySatisfy {
            assertThat(it).isEqualTo(
                "KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG} kubectl -n kube-system get cm cilium-config -o json",
            )
        }
        assertThat(commandCaptor.allValues).anySatisfy {
            assertThat(it).isEqualTo("KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG} kubectl get ciliumnode -o json")
        }
    }

    @Test
    fun `on a Cilium cluster with no control node it fails rather than printing an empty report`() {
        whenever(mockClusterStateManager.load()).thenReturn(state(CniMode.Cilium, hosts = emptyMap()))

        assertThatThrownBy { PlatformCni().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No control node")
        verify(mockRemoteOps, never()).executeRemotely(any(), any(), any(), any())
    }

    @Test
    fun `buildReport renders the configuration, the Hubble UI URL, and one block per node`() {
        val inspection =
            CiliumInspection(
                config =
                    CiliumConfigSummary(
                        routingMode = "native",
                        ipamMode = "eni",
                        kubeProxyReplacement = "false",
                        masqueradeInterfaces = "ens+",
                        nativeRoutingCidr = "10.0.0.0/16",
                        ipv4Masquerade = "true",
                    ),
                nodes =
                    listOf(
                        CiliumNodeSummary(
                            name = "ip-10-0-1-10",
                            eniCount = 2,
                            subnetCidrs = listOf("10.0.1.0/24", "10.0.2.0/24"),
                            ipsAllocated = 8,
                            ipsUsed = 3,
                        ),
                        CiliumNodeSummary(name = "ip-10-0-2-20", eniCount = 0, subnetCidrs = emptyList(), ipsAllocated = 0, ipsUsed = 0),
                    ),
            )

        val report = PlatformCni.buildReport(inspection, controlPrivateIp = "10.0.0.1")

        assertThat(report).contains("CNI: cilium")
        assertThat(report).contains("Routing mode:            native")
        assertThat(report).contains("IPAM mode:               eni")
        assertThat(report).contains("kube-proxy replacement:  false")
        assertThat(report).contains("Masquerade interfaces:   ens+")
        assertThat(report).contains("Native routing CIDR:     10.0.0.0/16")
        assertThat(report).contains("http://10.0.0.1:${Constants.Cilium.HUBBLE_UI_NODE_PORT}")
        assertThat(report).contains("Nodes (2):")
        assertThat(report).contains("ip-10-0-1-10")
        assertThat(report).contains("ENIs:           2")
        assertThat(report).contains("Subnet CIDRs:   10.0.1.0/24, 10.0.2.0/24")
        assertThat(report).contains("IPs allocated:  8")
        assertThat(report).contains("IPs used:       3")
        assertThat(report).contains("IPs available:  5")
        assertThat(report).contains("Subnet CIDRs:   (none)")
    }

    @Test
    fun `buildReport says so when the operator has produced no CiliumNode objects yet`() {
        val inspection =
            CiliumInspection(
                config = CiliumConfigSummary("native", "eni", "false", "ens+", "10.0.0.0/16", "true"),
                nodes = emptyList(),
            )

        val report = PlatformCni.buildReport(inspection, controlPrivateIp = "10.0.0.1")

        assertThat(report).contains("Nodes (0):").contains("no CiliumNode objects yet")
    }

    @Test
    fun `execute renders what the control node returned`() {
        whenever(mockClusterStateManager.load()).thenReturn(state(CniMode.Cilium))
        val service: CiliumInspectionService = getKoin().get()

        val report = PlatformCni.buildReport(service.inspect(controlHost.toHost()).getOrThrow(), controlHost.privateIp)

        assertThat(report).contains("Routing mode:            native")
        assertThat(report).contains("ip-10-0-1-10").contains("ip-10-0-2-20")
        assertThat(report).contains("IPs available:  3")
    }
}
