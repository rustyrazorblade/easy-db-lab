package com.rustyrazorblade.easydblab.proxy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProxiedHttpClientFactoryTest {
    @Test
    fun `createClient returns a non-null OkHttpClient`() {
        val factory = ProxiedHttpClientFactory()
        val client = factory.createClient()
        assertThat(client).isNotNull()
        factory.close()
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
        factory.createClient()
        factory.close()
        // After close, a new call returns a fresh client
        val newClient = factory.createClient()
        assertThat(newClient).isNotNull()
        factory.close()
    }
}
