package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests [DefaultCiliumInspectionService]: the kubectl commands it runs on the control node, and
 * the parse of real-shaped `cilium-config` and `CiliumNode` JSON into the report model.
 */
class CiliumInspectionServiceTest : BaseKoinTest() {
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
                single<RemoteOperationsService> { mock<RemoteOperationsService>().also { mockRemoteOps = it } }
            },
        )

    @BeforeEach
    fun setup() {
        mockRemoteOps = getKoin().get()
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any())).thenAnswer { invocation ->
            val command = invocation.getArgument<String>(1)
            when {
                command.contains("get cm cilium-config") -> Response(CONFIG_JSON)
                command.contains("get ciliumnode") -> Response(NODES_JSON)
                else -> error("unexpected command: $command")
            }
        }
    }

    private fun makeService() = DefaultCiliumInspectionService(remoteOps = getKoin().get())

    @Test
    fun `inspect reads the cilium-config ConfigMap and the CiliumNode list with the remote kubeconfig`() {
        makeService().inspect(controlHost).getOrThrow()

        val commandCaptor = argumentCaptor<String>()
        verify(mockRemoteOps, times(2)).executeRemotely(eq(controlHost), commandCaptor.capture(), any(), any())

        val commands = commandCaptor.allValues
        assertThat(commands).allSatisfy { assertThat(it).startsWith("KUBECONFIG=${Constants.K3s.REMOTE_KUBECONFIG} kubectl") }
        assertThat(commands[0]).contains("-n kube-system get cm cilium-config -o json")
        assertThat(commands[1]).contains("get ciliumnode -o json")
    }

    @Test
    fun `inspect parses the effective agent configuration from the ConfigMap data`() {
        val config = makeService().inspect(controlHost).getOrThrow().config

        assertThat(config.routingMode).isEqualTo("native")
        assertThat(config.ipamMode).isEqualTo("eni")
        assertThat(config.kubeProxyReplacement).isEqualTo("false")
        assertThat(config.masqueradeInterfaces).isEqualTo("ens+")
        assertThat(config.nativeRoutingCidr).isEqualTo("10.0.0.0/16")
        assertThat(config.ipv4Masquerade).isEqualTo("true")
    }

    @Test
    fun `inspect summarises each node's ENIs, subnets, and IP pool from its CiliumNode`() {
        val nodes = makeService().inspect(controlHost).getOrThrow().nodes

        assertThat(nodes.map { it.name }).containsExactly("ip-10-0-1-10", "ip-10-0-2-20")

        val db0 = nodes[0]
        assertThat(db0.eniCount).isEqualTo(2)
        assertThat(db0.subnetCidrs).containsExactly("10.0.1.0/24")
        assertThat(db0.ipsAllocated).isEqualTo(4)
        assertThat(db0.ipsUsed).isEqualTo(1)
        assertThat(db0.ipsAvailable).isEqualTo(3)

        // The operator has not populated this node yet: everything reads as zero, not as an error.
        val db1 = nodes[1]
        assertThat(db1.eniCount).isEqualTo(0)
        assertThat(db1.subnetCidrs).isEmpty()
        assertThat(db1.ipsAllocated).isEqualTo(0)
        assertThat(db1.ipsUsed).isEqualTo(0)
    }

    @Test
    fun `parseCiliumConfig reports an absent key as unset rather than failing`() {
        val config = parseCiliumConfig("""{"data":{"routing-mode":"native"}}""")

        assertThat(config.routingMode).isEqualTo("native")
        assertThat(config.ipamMode).isEqualTo(CiliumConfigSummary.UNSET)
        assertThat(config.masqueradeInterfaces).isEqualTo(CiliumConfigSummary.UNSET)
    }

    @Test
    fun `parseCiliumNodes returns no nodes for an empty list`() {
        assertThat(parseCiliumNodes("""{"apiVersion":"v1","kind":"List","items":[]}""")).isEmpty()
    }

    @Test
    fun `inspect returns failure when kubectl cannot be run`() {
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any()))
            .doThrow(RuntimeException("ssh: connect to host 10.0.0.1 port 22: Connection refused"))

        val result = makeService().inspect(controlHost)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageContaining("Connection refused")
    }

    companion object {
        const val CONFIG_JSON = """
            {
              "apiVersion": "v1",
              "kind": "ConfigMap",
              "metadata": {"name": "cilium-config", "namespace": "kube-system"},
              "data": {
                "routing-mode": "native",
                "ipam": "eni",
                "kube-proxy-replacement": "false",
                "egress-masquerade-interfaces": "ens+",
                "ipv4-native-routing-cidr": "10.0.0.0/16",
                "enable-ipv4-masquerade": "true",
                "tunnel-protocol": "vxlan"
              }
            }
        """

        const val NODES_JSON = """
            {
              "apiVersion": "v1",
              "kind": "List",
              "items": [
                {
                  "apiVersion": "cilium.io/v2",
                  "kind": "CiliumNode",
                  "metadata": {"name": "ip-10-0-1-10"},
                  "spec": {
                    "eni": {"instance-type": "i4i.xlarge", "vpc-id": "vpc-1", "availability-zone": "us-west-2a"},
                    "ipam": {
                      "pool": {
                        "10.0.1.20": {"resource": "eni-primary"},
                        "10.0.1.21": {"resource": "eni-primary"},
                        "10.0.1.30": {"resource": "eni-secondary"},
                        "10.0.1.31": {"resource": "eni-secondary"}
                      }
                    }
                  },
                  "status": {
                    "eni": {
                      "enis": {
                        "eni-primary": {"id": "eni-primary", "number": 0, "subnet": {"id": "subnet-a", "cidr": "10.0.1.0/24"}},
                        "eni-secondary": {"id": "eni-secondary", "number": 1, "subnet": {"id": "subnet-a", "cidr": "10.0.1.0/24"}}
                      }
                    },
                    "ipam": {
                      "used": {
                        "10.0.1.20": {"resource": "eni-primary", "owner": "default/otel-collector-abc"}
                      }
                    }
                  }
                },
                {
                  "apiVersion": "cilium.io/v2",
                  "kind": "CiliumNode",
                  "metadata": {"name": "ip-10-0-2-20"},
                  "spec": {"eni": {"instance-type": "i4i.xlarge"}, "ipam": {}},
                  "status": {}
                }
              ]
            }
        """
    }
}
