package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Verifies host fan-out semantics, in particular that a failure inside a parallel per-host action
 * reaches the caller instead of dying silently inside its worker thread.
 */
class HostOperationsServiceTest {
    private val service = HostOperationsService(mock<ClusterStateManager>())

    private fun host(alias: String) =
        ClusterHost(
            publicIp = "54.0.0.1",
            privateIp = "10.0.0.1",
            alias = alias,
            availabilityZone = "us-west-2a",
            instanceId = "i-$alias",
        )

    private val hosts =
        mapOf(
            ServerType.Cassandra to listOf(host("db0"), host("db1"), host("db2")),
        )

    @Test
    fun `parallel withHosts surfaces a per-host failure to the caller`() {
        assertThatThrownBy {
            service.withHosts(hosts, ServerType.Cassandra, parallel = true) { clusterHost ->
                if (clusterHost.alias == "db1") {
                    error("install failed on ${clusterHost.alias}")
                }
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("install failed on db1")
    }

    @Test
    fun `parallel withHosts still runs every host when one fails`() {
        val visited = ConcurrentLinkedQueue<String>()

        assertThatThrownBy {
            service.withHosts(hosts, ServerType.Cassandra, parallel = true) { clusterHost ->
                visited.add(clusterHost.alias)
                if (clusterHost.alias == "db0") {
                    error("boom")
                }
            }
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(visited).containsExactlyInAnyOrder("db0", "db1", "db2")
    }

    @Test
    fun `parallel withHosts surfaces every failure, not just the first`() {
        // Otherwise an operator fixes one host, reruns, and only then learns about the next.
        assertThatThrownBy {
            service.withHosts(hosts, ServerType.Cassandra, parallel = true) { clusterHost ->
                if (clusterHost.alias != "db2") {
                    error("broken on ${clusterHost.alias}")
                }
            }
        }.satisfies({ thrown ->
            val reported = (listOf(thrown) + thrown.suppressed).mapNotNull { it.message }
            assertThat(reported).containsExactlyInAnyOrder("broken on db0", "broken on db1")
        })
    }

    @Test
    fun `collectFromHosts returns per-host outcomes in host order`() {
        val results =
            service.collectFromHosts(hosts, ServerType.Cassandra, parallel = true) { clusterHost ->
                if (clusterHost.alias == "db1") {
                    error("no route to host")
                }
                "installed on ${clusterHost.alias}"
            }

        assertThat(results.map { it.host.alias }).containsExactly("db0", "db1", "db2")
        assertThat(results[0].result.getOrNull()).isEqualTo("installed on db0")
        assertThat(results[1].result.exceptionOrNull()).hasMessageContaining("no route to host")
        assertThat(results[2].result.getOrNull()).isEqualTo("installed on db2")
    }

    @Test
    fun `collectFromHosts honors the host filter`() {
        val results =
            service.collectFromHosts(hosts, ServerType.Cassandra, hostFilter = "db2") { it.alias }

        assertThat(results.map { it.host.alias }).containsExactly("db2")
    }

    @Test
    fun `serial withHosts propagates a failure`() {
        assertThatThrownBy {
            service.withHosts(hosts, ServerType.Cassandra) { clusterHost ->
                if (clusterHost.alias == "db0") {
                    error("serial boom")
                }
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("serial boom")
    }
}
