package com.rustyrazorblade.easydblab.services

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions
import com.datastax.oss.driver.api.core.cql.ResultSet
import com.datastax.oss.driver.api.core.cql.Statement
import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Exposure
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.driver.CqlSessionFactory
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.services.access.CassandraPodForward
import com.rustyrazorblade.easydblab.services.access.PodTarget
import io.fabric8.kubernetes.client.LocalPortForward
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.net.InetSocketAddress

class CqlSessionServiceTest : BaseKoinTest() {
    private lateinit var mockSocksProxy: SocksProxyService
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var mockSessionFactory: CqlSessionFactory
    private lateinit var mockResourceManager: ResourceManager

    private val testHost =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = "10.0.1.5",
            alias = "db0",
            availabilityZone = "us-west-2a",
        )

    private fun stateWith(tailscaleActive: Boolean) =
        ClusterState(
            name = "test",
            versions = mutableMapOf(),
            tailscaleActive = tailscaleActive,
            initConfig = InitConfig(region = "us-west-2"),
            hosts = mapOf(ServerType.Cassandra to listOf(testHost)),
            // As init records it: a Tailscale cluster is a Direct one.
            exposure = Exposure.atInit(tailscaleActive = tailscaleActive, directNetwork = false),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single<SocksProxyService> { mockSocksProxy }
                single<CqlSessionFactory> { mockSessionFactory }
                single<ResourceManager> { mockResourceManager }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockSocksProxy = mock()
        mockClusterStateManager = mock()
        mockSessionFactory = mock()
        mockResourceManager = mock()

        whenever(mockSocksProxy.getLocalPort()).thenReturn(1080)
        val mockSession = mock<CqlSession>()
        whenever(mockSession.isClosed).thenReturn(false)
        val noRows = mock<ResultSet>()
        whenever(noRows.columnDefinitions).thenReturn(mock<ColumnDefinitions>())
        whenever(mockSession.execute(any<Statement<*>>())).thenReturn(noRows)
        whenever(mockSessionFactory.createDirectSession(any(), any())).thenReturn(mockSession)
        whenever(mockSessionFactory.createSession(any(), any(), any())).thenReturn(mockSession)
        whenever(mockSessionFactory.createSingleEndpointSession(any(), any())).thenReturn(mockSession)
    }

    @Test
    fun `does not start SOCKS proxy when tailscaleActive is true`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWith(tailscaleActive = true))

        val service =
            DefaultCqlSessionService(
                socksProxyService = mockSocksProxy,
                clusterStateManager = mockClusterStateManager,
                sessionFactory = mockSessionFactory,
                resourceManager = mockResourceManager,
            )

        service.execute("SELECT now() FROM system.local")

        verify(mockSocksProxy, never()).ensureRunning(any())
        verify(mockSessionFactory).createDirectSession(any(), any())
        verify(mockSessionFactory, never()).createSession(any(), any(), any())
    }

    @Test
    fun `a Direct cluster gets a direct session with no SOCKS port, even without Tailscale`() {
        whenever(mockClusterStateManager.load())
            .thenReturn(stateWith(tailscaleActive = false).apply { exposure = Exposure.Direct })

        val service =
            DefaultCqlSessionService(
                socksProxyService = mockSocksProxy,
                clusterStateManager = mockClusterStateManager,
                sessionFactory = mockSessionFactory,
                resourceManager = mockResourceManager,
            )

        service.execute("SELECT now() FROM system.local")

        verify(mockSocksProxy, never()).getLocalPort()
        verify(mockSessionFactory).createDirectSession(listOf("10.0.1.5"), "us-west-2")
        verify(mockSessionFactory, never()).createSession(any(), any(), any())
    }

    @Test
    fun `uses proxied session with port from SocksProxyService when tailscaleActive is false`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWith(tailscaleActive = false))

        val service =
            DefaultCqlSessionService(
                socksProxyService = mockSocksProxy,
                clusterStateManager = mockClusterStateManager,
                sessionFactory = mockSessionFactory,
                resourceManager = mockResourceManager,
            )

        service.execute("SELECT now() FROM system.local")

        verify(mockSocksProxy).getLocalPort()
        verify(mockSessionFactory).createSession(any(), any(), any())
        verify(mockSessionFactory, never()).createDirectSession(any(), any())
    }

    private val forwardedPorts = mutableListOf<Int>()
    private var forwardAlive = true

    private fun singleEndpoint() =
        SingleEndpointCql(
            forward =
                CassandraPodForward(
                    pod = PodTarget("default", "cassandra-pods-0", 30942),
                    forwarder = { _, localPort ->
                        forwardedPorts += localPort
                        forwardAlive = true
                        mock<LocalPortForward>().also {
                            whenever(it.localPort).thenReturn(localPort)
                            whenever(it.isAlive).thenAnswer { forwardAlive }
                        }
                    },
                    localPorts = { 24042 },
                ),
            datacenter = "dc1",
        )

    @Test
    fun `a port-forwarded cluster gets a single-endpoint session on the forward, with no SOCKS tunnel`() {
        whenever(mockClusterStateManager.load())
            .thenReturn(stateWith(tailscaleActive = false).apply { exposure = Exposure.PortForward })

        val service =
            DefaultCqlSessionService(
                socksProxyService = mockSocksProxy,
                clusterStateManager = mockClusterStateManager,
                sessionFactory = mockSessionFactory,
                resourceManager = mockResourceManager,
                singleEndpoint = ::singleEndpoint,
            )

        service.execute("SELECT now() FROM system.local").getOrThrow()

        verify(mockSessionFactory).createSingleEndpointSession(InetSocketAddress("127.0.0.1", 24042), "dc1")
        verify(mockSocksProxy, never()).getLocalPort()
        verify(mockSessionFactory, never()).createSession(any(), any(), any())
    }

    @Test
    fun `a forward that died is reopened on the same port before the next statement, keeping the session`() {
        whenever(mockClusterStateManager.load())
            .thenReturn(stateWith(tailscaleActive = false).apply { exposure = Exposure.PortForward })
        val service =
            DefaultCqlSessionService(
                socksProxyService = mockSocksProxy,
                clusterStateManager = mockClusterStateManager,
                sessionFactory = mockSessionFactory,
                resourceManager = mockResourceManager,
                singleEndpoint = ::singleEndpoint,
            )

        service.execute("SELECT 1").getOrThrow()
        forwardAlive = false
        service.execute("SELECT 1").getOrThrow()

        assertThat(forwardedPorts).containsExactly(24042, 24042)
        verify(mockSessionFactory, times(1)).createSingleEndpointSession(any(), any())
    }
}
