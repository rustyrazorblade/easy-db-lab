package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The Socks and Direct seams dial the node that runs a Service, at its private address. */
class NodeServiceAccessTest {
    private val control = ClusterHost("54.0.0.1", "10.0.1.5", "control0", "us-west-2a")
    private val state =
        ClusterState(name = "lab", versions = mutableMapOf(), hosts = mapOf(ServerType.Control to listOf(control)))
    private val empty = ClusterState(name = "lab", versions = mutableMapOf())

    @Test
    fun `socks access dials the node's private address through the tunnel's current port`() {
        val tunnelPorts = ArrayDeque(listOf(21000, 22000))
        val access = SocksServiceAccess({ state }) { tunnelPorts.removeFirst() }

        assertThat(access.open(ClusterServices.LOKI)).isEqualTo(ServiceEndpoint.ViaSocks("10.0.1.5", 3100, 21000))
        // A replaced tunnel is followed on the next open; no port is held between calls.
        assertThat(access.open(ClusterServices.GRAFANA)).isEqualTo(ServiceEndpoint.ViaSocks("10.0.1.5", 3000, 22000))
    }

    @Test
    fun `direct access dials the node's private address with no proxy`() {
        val access = DirectServiceAccess { state }

        assertThat(access.open(ClusterServices.MIMIR)).isEqualTo(ServiceEndpoint.Plain("10.0.1.5", 9009))
    }

    @Test
    fun `a cluster with no node of the Service's role fails naming the role and the Service`() {
        assertThatThrownBy { DirectServiceAccess { empty }.open(ClusterServices.TEMPO) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("control")
            .hasMessageContaining("tempo")
    }
}
