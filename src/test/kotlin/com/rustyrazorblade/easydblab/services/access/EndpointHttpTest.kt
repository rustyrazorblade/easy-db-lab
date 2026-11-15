package com.rustyrazorblade.easydblab.services.access

import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.Proxy

/** Each HTTP client takes its proxy from the endpoint it dials, never from shared state. */
class EndpointHttpTest {
    private val base = OkHttpClient()

    @Test
    fun `a SOCKS endpoint gets its own SOCKS proxy on 127 0 0 1 at the endpoint's tunnel port`() {
        val client = base.forEndpoint(ServiceEndpoint.ViaSocks("10.0.1.5", 3100, 23456))

        assertThat(client.proxy).isEqualTo(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 23456)))
    }

    @Test
    fun `a plain endpoint gets no proxy`() {
        assertThat(base.forEndpoint(ServiceEndpoint.Plain("127.0.0.1", 21001)).proxy).isEqualTo(Proxy.NO_PROXY)
    }

    @Test
    fun `an endpoint's base URL names its host and port`() {
        assertThat(ServiceEndpoint.ViaSocks("10.0.1.5", 3000, 23456).baseUrl()).isEqualTo("http://10.0.1.5:3000")
        assertThat(ServiceEndpoint.Plain("127.0.0.1", 21001).baseUrl()).isEqualTo("http://127.0.0.1:21001")
    }
}
