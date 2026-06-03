package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.services.KitEndpoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KitStatusCommandTest {
    private fun endpoint(
        type: KitEndpoint.EndpointType,
        port: Int = 8080,
        scheme: String = "",
        path: String = "",
    ) = KitEndpoint(
        name = "test",
        nodeType = "app",
        port = port,
        type = type,
        scheme = scheme,
        path = path,
    )

    @Test
    fun `http endpoint formats correctly`() {
        val url = endpoint(KitEndpoint.EndpointType.HTTP, port = 8080).formatUrl("10.0.1.5")
        assertThat(url).isEqualTo("http://10.0.1.5:8080")
    }

    @Test
    fun `http endpoint includes path when present`() {
        val url = endpoint(KitEndpoint.EndpointType.HTTP, port = 80, path = "/ui").formatUrl("10.0.1.5")
        assertThat(url).isEqualTo("http://10.0.1.5:80/ui")
    }

    @Test
    fun `https endpoint formats correctly`() {
        val url = endpoint(KitEndpoint.EndpointType.HTTPS, port = 443).formatUrl("10.0.1.5")
        assertThat(url).isEqualTo("https://10.0.1.5:443")
    }

    @Test
    fun `jdbc endpoint uses scheme and path`() {
        val url = endpoint(KitEndpoint.EndpointType.JDBC, port = 8080, scheme = "presto", path = "/cassandra").formatUrl("10.0.1.5")
        assertThat(url).isEqualTo("jdbc:presto://10.0.1.5:8080/cassandra")
    }

    @Test
    fun `native endpoint is ip colon port`() {
        val url = endpoint(KitEndpoint.EndpointType.NATIVE, port = 9000).formatUrl("10.0.2.3")
        assertThat(url).isEqualTo("10.0.2.3:9000")
    }

    @Test
    fun `cql endpoint is ip colon port`() {
        val url = endpoint(KitEndpoint.EndpointType.CQL, port = 9042).formatUrl("10.0.2.3")
        assertThat(url).isEqualTo("10.0.2.3:9042")
    }

    @Test
    fun `http endpoint with empty path omits trailing slash`() {
        val url = endpoint(KitEndpoint.EndpointType.HTTP, port = 8080, path = "").formatUrl("10.0.1.5")
        assertThat(url).isEqualTo("http://10.0.1.5:8080")
        assertThat(url).doesNotEndWith("/")
    }

    @Test
    fun `multiple IPs produce distinct URLs`() {
        val ep = endpoint(KitEndpoint.EndpointType.HTTP, port = 8080)
        val urls = listOf("10.0.1.1", "10.0.1.2", "10.0.1.3").map { ip -> ep.formatUrl(ip) }
        assertThat(urls).containsExactly(
            "http://10.0.1.1:8080",
            "http://10.0.1.2:8080",
            "http://10.0.1.3:8080",
        )
    }
}
