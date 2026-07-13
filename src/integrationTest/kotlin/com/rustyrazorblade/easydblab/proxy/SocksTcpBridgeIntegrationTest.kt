package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.sql.KitJdbcSqlService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * End-to-end test proving [SocksTcpBridge] lets a raw-TCP JDBC driver (pgjdbc) reach a
 * cluster-private database through a real SOCKS5 proxy — the exact SOCKS-only-cluster scenario.
 *
 * A Postgres container and a `serjs/go-socks5-proxy` container share a Docker network; Postgres
 * is reachable only by its network alias (never by a mapped port). We publish the proxy's mapped
 * port via [Constants.Proxy.PORT_PROPERTY] and drive [KitJdbcSqlService.execute], which opens a
 * loopback bridge and rewrites the URL authority. The query must reach Postgres via the proxy.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SocksTcpBridgeIntegrationTest {
    companion object {
        private const val POSTGRES_ALIAS = "pgdb"
        private const val POSTGRES_PORT = 5432
        private const val SOCKS_PORT = 1080

        // Pinned by digest for reproducible CI: `serjs/go-socks5-proxy:latest` now defaults
        // REQUIRE_AUTH=true and exits 1 at startup when no credentials are set. The bridge
        // connects with no SOCKS credentials, so the proxy must run open — REQUIRE_AUTH=false.
        private const val SOCKS_IMAGE =
            "serjs/go-socks5-proxy@sha256:0af522996f402c03ecd985a87997158eabeb28935365e3a384df37eafcf740ea"

        private val network: Network = Network.newNetwork()

        @Container
        @JvmStatic
        val postgres: GenericContainer<*> =
            GenericContainer("postgres:16-alpine")
                .withNetwork(network)
                .withNetworkAliases(POSTGRES_ALIAS)
                .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
                .withExposedPorts(POSTGRES_PORT)
                .waitingFor(Wait.forListeningPort())

        @Container
        @JvmStatic
        val socks: GenericContainer<*> =
            GenericContainer(SOCKS_IMAGE)
                .withNetwork(network)
                .withEnv("REQUIRE_AUTH", "false")
                .withExposedPorts(SOCKS_PORT)
                .waitingFor(Wait.forListeningPort())
    }

    private val dbHost =
        ClusterHost(
            publicIp = "203.0.113.10",
            privateIp = POSTGRES_ALIAS,
            alias = "db0",
            availabilityZone = "us-west-2a",
        )

    private val endpoint =
        KitEndpoint(
            name = "JDBC",
            nodeType = "db",
            port = POSTGRES_PORT,
            type = KitEndpoint.EndpointType.JDBC,
            scheme = "postgresql",
            path = "/postgres",
        )

    @AfterEach
    fun clearProxyPort() {
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
    }

    @Test
    fun `raw-TCP jdbc query reaches a private-only postgres through the SOCKS bridge`() {
        val clusterStateManager = mock<ClusterStateManager>()
        whenever(clusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                hosts = mapOf(ServerType.Cassandra to listOf(dbHost)),
            ),
        )

        System.setProperty(Constants.Proxy.PORT_PROPERTY, socks.getMappedPort(SOCKS_PORT).toString())

        val service =
            KitJdbcSqlService(
                clusterStateManager = clusterStateManager,
                endpoint = endpoint,
                user = "postgres",
            )

        val result = service.execute("SELECT 1").getOrThrow()

        assertThat(result.rows).hasSize(1)
        assertThat(result.rows[0]).containsExactly("1")
    }
}
