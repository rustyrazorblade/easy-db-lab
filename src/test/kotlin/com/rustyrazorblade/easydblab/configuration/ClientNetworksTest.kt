package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.network.Ipv4Cidr
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ClientNetworksTest {
    private fun cidr(value: String) = Ipv4Cidr.parse(value).getOrThrow()

    private fun state(
        cidr: String? = null,
        clientNetworks: List<String>? = null,
    ) = ClusterState(
        name = "lab",
        initConfig = InitConfig(cidr = cidr),
        clientNetworks = clientNetworks,
    )

    @Test
    fun `on the nodes easy-db-lab builds the networks are the VPC and the K3s cluster CIDR`() {
        val networks = ClientNetworks.from(state(cidr = "172.16.0.0/16"))

        assertThat(networks.cidrs.map { it.value })
            .containsExactly("172.16.0.0/16", Constants.K3s.CLUSTER_CIDR)
            .doesNotContain("10.0.0.0/8")
    }

    @Test
    fun `a state with no VPC CIDR recorded uses the default VPC block`() {
        assertThat(ClientNetworks.from(state()).cidrs.map { it.value })
            .containsExactly(Constants.Vpc.DEFAULT_CIDR, Constants.K3s.CLUSTER_CIDR)
    }

    @Test
    fun `networks added at init follow the VPC and the cluster CIDR, each once`() {
        val networks =
            ClientNetworks.from(
                state(cidr = "172.16.0.0/16").apply { extraClientNetworks = listOf("192.168.0.0/16", "172.16.0.0/16") },
            )

        assertThat(networks.cidrs.map { it.value })
            .containsExactly("172.16.0.0/16", Constants.K3s.CLUSTER_CIDR, "192.168.0.0/16")
    }

    @Test
    fun `recorded networks win over the VPC`() {
        val networks = ClientNetworks.from(state(cidr = "172.16.0.0/16", clientNetworks = listOf("192.168.0.0/16")))

        assertThat(networks.cidrs.map { it.value }).containsExactly("192.168.0.0/16")
    }

    @Test
    fun `probed networks are each node's pod CIDRs and InternalIP, then the overrides, without repeats`() {
        val nodes =
            listOf(
                ProbedNode(podCidrs = listOf("10.244.0.0/24"), internalIps = listOf("192.168.1.5")),
                ProbedNode(podCidrs = listOf("10.244.0.0/24"), internalIps = emptyList()),
            )

        val networks = ClientNetworks.probed(nodes, overrides = listOf(cidr("192.168.0.0/16")))

        assertThat(networks.cidrs.map { it.value })
            .containsExactly("10.244.0.0/24", "192.168.1.5/32", "192.168.0.0/16")
    }

    @Test
    fun `a probed value that is not an IPv4 network is skipped`() {
        val nodes = listOf(ProbedNode(podCidrs = listOf("fd00::/64", "10.244.1.0/24"), internalIps = listOf("fd00::5")))

        assertThat(ClientNetworks.probed(nodes, emptyList()).cidrs.map { it.value }).containsExactly("10.244.1.0/24")
    }

    @Test
    fun `no network at all is refused, naming the option that adds one`() {
        assertThatThrownBy { ClientNetworks.probed(emptyList(), emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(ClientNetworks.OPTION)
    }

    @Test
    fun `the networks render as a JSON list and as one trust line each`() {
        val networks = ClientNetworks(listOf(cidr("172.16.0.0/16"), cidr("10.42.0.0/16")))

        assertThat(networks.toJson()).isEqualTo("""["172.16.0.0/16","10.42.0.0/16"]""")
        assertThat(networks.toHbaJson()).isEqualTo("""["host all all 172.16.0.0/16 trust","host all all 10.42.0.0/16 trust"]""")
    }
}
