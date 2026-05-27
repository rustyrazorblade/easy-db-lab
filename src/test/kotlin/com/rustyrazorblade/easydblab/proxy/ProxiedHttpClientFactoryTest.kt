package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ProxiedHttpClientFactoryTest {
    private val mockSocksProxy: SocksProxyService = mock()
    private val mockClusterStateManager: ClusterStateManager = mock()

    private fun stateWith(tailscaleActive: Boolean) =
        ClusterState(name = "test", versions = mutableMapOf(), tailscaleActive = tailscaleActive)

    @Test
    fun `createClient returns client with no proxy when tailscaleActive is true`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWith(tailscaleActive = true))

        val factory =
            ProxiedHttpClientFactory(
                socksProxyService = mockSocksProxy,
                clusterStateManager = mockClusterStateManager,
            )
        val client = factory.createClient()

        assertThat(client.proxy).isNull()
        factory.close()
    }

    @Test
    fun `createClient returns SOCKS-proxied client when tailscaleActive is false`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWith(tailscaleActive = false))
        whenever(mockSocksProxy.getLocalPort()).thenReturn(1080)

        val factory =
            ProxiedHttpClientFactory(
                socksProxyService = mockSocksProxy,
                clusterStateManager = mockClusterStateManager,
            )
        val client = factory.createClient()

        assertThat(client.proxy).isNotNull()
        factory.close()
    }
}
