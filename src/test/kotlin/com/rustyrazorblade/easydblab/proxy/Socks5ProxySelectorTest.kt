package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * Unit tests for [Socks5ProxySelector]: it must return a SOCKS proxy on the published port when the
 * proxy is active, and NO_PROXY (direct) when it is not — the latter covering both "before the proxy
 * starts" and the Tailscale case (no proxy ever published).
 */
@ResourceLock(Constants.Proxy.PORT_PROPERTY)
class Socks5ProxySelectorTest {
    private val selector = Socks5ProxySelector()
    private val uri = URI("https://control0:8428")

    @AfterEach
    fun clearProp() {
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
    }

    @Test
    fun `returns SOCKS proxy to the published port when set`() {
        System.setProperty(Constants.Proxy.PORT_PROPERTY, "1080")

        val proxy = selector.select(uri).single()

        assertThat(proxy.type()).isEqualTo(Proxy.Type.SOCKS)
        val address = proxy.address() as InetSocketAddress
        assertThat(address.hostString).isEqualTo("127.0.0.1")
        assertThat(address.port).isEqualTo(1080)
    }

    @Test
    fun `returns NO_PROXY when no port is published (Tailscale or proxy not started)`() {
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)

        assertThat(selector.select(uri)).containsExactly(Proxy.NO_PROXY)
    }

    @Test
    fun `returns NO_PROXY when the published value is not a valid port`() {
        System.setProperty(Constants.Proxy.PORT_PROPERTY, "not-a-number")

        assertThat(selector.select(uri)).containsExactly(Proxy.NO_PROXY)
    }
}
