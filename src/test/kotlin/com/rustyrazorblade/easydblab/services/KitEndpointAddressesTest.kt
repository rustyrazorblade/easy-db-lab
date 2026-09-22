package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests how a kit's declared endpoints resolve to addresses: one per host of the endpoint's
 * node type, case-insensitive on the node type, and nothing (rather than an exception) for a
 * node type the cluster does not know.
 */
class KitEndpointAddressesTest {
    private fun host(
        alias: String,
        privateIp: String,
    ) = ClusterHost(
        publicIp = "3.4.5.6",
        privateIp = privateIp,
        alias = alias,
        availabilityZone = "us-west-2a",
        instanceId = "i-$alias",
    )

    private val hosts =
        mapOf(
            ServerType.Cassandra to listOf(host("db0", "10.0.2.1"), host("db1", "10.0.2.2")),
            ServerType.Control to listOf(host("control0", "10.0.0.1")),
        )

    private fun endpoint(nodeType: String) =
        KitEndpoint(name = "bolt", nodeType = nodeType, port = 30687, type = KitEndpoint.EndpointType.NATIVE)

    @Test
    fun `an unknown node type resolves to no addresses instead of throwing`() {
        assertThat(KitEndpointAddresses.privateIps("bogus", hosts)).isEmpty()
        assertThat(KitEndpointAddresses.resolve(listOf(endpoint("bogus")), hosts)).isEmpty()
    }

    @Test
    fun `every host of the node type yields its own address`() {
        val resolved = KitEndpointAddresses.resolve(listOf(endpoint("db")), hosts)

        assertThat(resolved.map { it.address }).containsExactly("10.0.2.1:30687", "10.0.2.2:30687")
    }

    @Test
    fun `node type is matched case-insensitively`() {
        assertThat(KitEndpointAddresses.privateIps("DB", hosts)).containsExactly("10.0.2.1", "10.0.2.2")
    }

    @Test
    fun `formatted lines carry name, lowercased type, and address, one per resolved host`() {
        val lines = KitEndpointAddresses.formatLines(KitEndpointAddresses.resolve(listOf(endpoint("db")), hosts))

        assertThat(lines.lines()).hasSize(2)
        assertThat(lines.lines()[0]).contains("bolt", "native", "10.0.2.1:30687")
        assertThat(lines.lines()[1]).contains("bolt", "native", "10.0.2.2:30687")
    }
}
