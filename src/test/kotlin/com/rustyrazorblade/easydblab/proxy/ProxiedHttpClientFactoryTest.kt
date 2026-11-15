package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import java.net.Proxy
import java.net.URI

@ResourceLock(Constants.Proxy.PORT_PROPERTY)
class ProxiedHttpClientFactoryTest {
    @AfterEach
    fun clearProp() {
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
    }

    @Test
    fun `createClient returns the same cached instance on multiple calls`() {
        val factory = ProxiedHttpClientFactory()
        val client1 = factory.createClient()
        val client2 = factory.createClient()
        assertThat(client1).isSameAs(client2)
        factory.close()
    }

    @Test
    fun `close releases the cached client`() {
        val factory = ProxiedHttpClientFactory()
        val first = factory.createClient()
        factory.close()

        assertThat(factory.createClient()).isNotSameAs(first)
        factory.close()
    }

    @Test
    fun `the shared client carries no proxy route, even while a tunnel port is published`() {
        System.setProperty(Constants.Proxy.PORT_PROPERTY, "1080")
        val factory = ProxiedHttpClientFactory()

        assertThat(factory.createClient().routeTo(URI("http://10.0.1.5:3100"))).containsExactly(Proxy.NO_PROXY)
        factory.close()
    }

    /** The proxies OkHttp would try for [uri]: the fixed proxy when one is set, otherwise the selector's. */
    private fun OkHttpClient.routeTo(uri: URI): List<Proxy> = proxy?.let { listOf(it) } ?: proxySelector.select(uri)
}
