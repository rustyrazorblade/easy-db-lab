package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests [DefaultCiliumInspectionService]: the kubectl reads it runs through [ClusterTooling], and
 * the parse of real-shaped `cilium-config` and `CiliumNode` JSON into the report model.
 */
class CiliumInspectionServiceTest {
    private val tooling =
        RecordingClusterTooling { call ->
            when (call.args) {
                DefaultCiliumInspectionService.CONFIG_ARGS -> CONFIG_JSON
                DefaultCiliumInspectionService.NODES_ARGS -> NODES_JSON
                else -> error("unexpected call: $call")
            }
        }

    private fun makeService() = DefaultCiliumInspectionService(tooling)

    @Test
    fun `inspect reads the cilium-config ConfigMap and the CiliumNode list without echoing them`() {
        makeService().inspect().getOrThrow()

        assertThat(tooling.calls).containsExactly(
            RecordingClusterTooling.Call(
                ClusterTool.KUBECTL,
                listOf("-n", "kube-system", "get", "cm", "cilium-config", "-o", "json"),
                echo = false,
            ),
            RecordingClusterTooling.Call(ClusterTool.KUBECTL, listOf("get", "ciliumnode", "-o", "json"), echo = false),
        )
    }

    @Test
    fun `inspect parses the effective agent configuration from the ConfigMap data`() {
        val config = makeService().inspect().getOrThrow().config

        assertThat(config.routingMode).isEqualTo("native")
        assertThat(config.ipamMode).isEqualTo("eni")
        assertThat(config.kubeProxyReplacement).isEqualTo("false")
        assertThat(config.masqueradeInterfaces).isEqualTo("en+")
        assertThat(config.nativeRoutingCidr).isEqualTo("10.0.0.0/16")
        assertThat(config.ipv4Masquerade).isEqualTo("true")
    }

    @Test
    fun `inspect summarises each node's ENIs, subnets, and IP pool from its CiliumNode`() {
        val nodes = makeService().inspect().getOrThrow().nodes

        assertThat(nodes.map { it.name }).containsExactly("ip-10-0-1-10", "ip-10-0-2-20")

        val db0 = nodes[0]
        assertThat(db0.eniCount).isEqualTo(2)
        assertThat(db0.subnetCidrs).containsExactly("10.0.0.0/19")
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
    fun `inspect returns failure when kubectl fails`() {
        val failing =
            RecordingClusterTooling { throw ClusterToolFailedException(ClusterTool.KUBECTL, it.args, 1, "control0", "forbidden") }

        val result = DefaultCiliumInspectionService(failing).inspect()

        assertThat(result.exceptionOrNull()).isInstanceOf(ClusterToolFailedException::class.java).hasMessageContaining("forbidden")
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
                "egress-masquerade-interfaces": "en+",
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
                        "eni-primary": {"id": "eni-primary", "number": 0, "subnet": {"id": "subnet-a", "cidr": "10.0.0.0/19"}},
                        "eni-secondary": {"id": "eni-secondary", "number": 1, "subnet": {"id": "subnet-a", "cidr": "10.0.0.0/19"}}
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
