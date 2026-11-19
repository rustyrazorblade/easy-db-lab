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
    fun `a recorded network that is not an IPv4 network names the value, state json and the option`() {
        assertThatThrownBy { ClientNetworks.from(state(clientNetworks = listOf("10.0.0.0/33"))) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("10.0.0.0/33")
            .hasMessageContaining("state.json")
            .hasMessageContaining(ClientNetworks.OPTION)
        assertThatThrownBy {
            ClientNetworks.from(state().apply { extraClientNetworks = listOf("not-a-cidr") })
        }.hasMessageContaining("not-a-cidr").hasMessageContaining(ClientNetworks.OPTION)
    }

    @Test
    fun `a recorded empty list is refused, naming state json and the option, instead of trusting no client`() {
        assertThatThrownBy { ClientNetworks.from(state(cidr = "172.16.0.0/16", clientNetworks = emptyList())) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("state.json")
            .hasMessageContaining(ClientNetworks.OPTION)
    }

    @Test
    fun `a slash 0 option is refused, naming the value`() {
        assertThatThrownBy { ClientNetworks.parseOption("0.0.0.0/0") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("0.0.0.0/0")
            .hasMessageContaining(ClientNetworks.OPTION)
    }

    @Test
    fun `an option with host bits set is refused, naming the value`() {
        assertThatThrownBy { ClientNetworks.parseOption("10.0.0.1/8") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("10.0.0.1/8")
    }

    @Test
    fun `a slash 1 to slash 7 network is very wide, a slash 8 is not`() {
        assertThat(cidr("128.0.0.0/1").isVeryWideClientNetwork).isTrue()
        assertThat(cidr("10.0.0.0/7").isVeryWideClientNetwork).isTrue()
        assertThat(cidr("10.0.0.0/8").isVeryWideClientNetwork).isFalse()
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
    fun `a probed IPv6 network of a dual-stack node is skipped`() {
        val nodes = listOf(ProbedNode(podCidrs = listOf("fd00::/64", "10.244.1.0/24"), internalIps = listOf("fd00::5")))

        assertThat(ClientNetworks.probed(nodes, emptyList()).cidrs.map { it.value }).containsExactly("10.244.1.0/24")
    }

    @Test
    fun `a probed value that is neither IPv4 nor IPv6 is refused, naming it and the option`() {
        val nodes = listOf(ProbedNode(podCidrs = listOf("10.244.1.0/24"), internalIps = listOf("10.0.0.300")))

        assertThatThrownBy { ClientNetworks.probed(nodes, emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("10.0.0.300")
            .hasMessageContaining(ClientNetworks.OPTION)
    }

    @Test
    fun `no network at all is refused, naming the option that adds one`() {
        assertThatThrownBy { ClientNetworks.probed(emptyList(), emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(ClientNetworks.OPTION)
    }

    @Test
    fun `an IPv6-only cluster is refused, saying its IPv6 values were skipped and naming the option`() {
        val nodes = listOf(ProbedNode(podCidrs = listOf("fd00:10:244::/64"), internalIps = listOf("fd00::5")))

        assertThatThrownBy { ClientNetworks.probed(nodes, emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("skipped 2 IPv6 values")
            .hasMessageContaining("fd00:10:244::/64")
            .hasMessageContaining("fd00::5")
            .hasMessageContaining(ClientNetworks.OPTION)
    }

    @Test
    fun `the networks render as a JSON list and as one trust line each`() {
        val networks = ClientNetworks(listOf(cidr("172.16.0.0/16"), cidr("10.42.0.0/16")))

        assertThat(networks.toJson()).isEqualTo("""["172.16.0.0/16","10.42.0.0/16"]""")
        assertThat(networks.toHbaJson()).isEqualTo("""["host all all 172.16.0.0/16 trust","host all all 10.42.0.0/16 trust"]""")
    }
}
