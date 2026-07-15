package com.rustyrazorblade.easydblab.services

import com.datastax.oss.driver.api.core.CqlSession
import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.driver.CqlSessionFactory
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

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
        whenever(mockSessionFactory.createDirectSession(any(), any())).thenReturn(mockSession)
        whenever(mockSessionFactory.createSession(any(), any(), any())).thenReturn(mockSession)
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
}
