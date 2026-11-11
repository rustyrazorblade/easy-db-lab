package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.net.URL

/**
 * Tests the order and fallback decisions of [DefaultExternalIpService] with an injected fetch, so
 * no test reaches the network.
 */
class ExternalIpServiceTest {
    private val primary = URI("https://primary.example/").toURL()
    private val fallback = URI("https://fallback.example/").toURL()

    /** A fetch that answers from [answers] by URL; a [Throwable] value is thrown instead. */
    private fun fetchFrom(
        answers: Map<URL, Any>,
        calls: MutableList<URL> = mutableListOf(),
    ): (URL) -> String =
        { url ->
            calls.add(url)
            when (val answer = answers.getValue(url)) {
                is Throwable -> throw answer
                else -> answer.toString()
            }
        }

    @Test
    fun `the default order asks checkip first and ipify second`() {
        val calls = mutableListOf<URL>()
        val service =
            DefaultExternalIpService(
                fetch = { url ->
                    calls.add(url)
                    throw IOException("down")
                },
            )

        assertThatThrownBy { service.getExternalIpAddress() }.isInstanceOf(IOException::class.java)
        assertThat(calls.map { it.toString() })
            .containsExactly(Constants.Network.EXTERNAL_IP_URL, Constants.Network.EXTERNAL_IP_FALLBACK_URL)
        assertThat(Constants.Network.EXTERNAL_IP_URL).isEqualTo("https://checkip.amazonaws.com/")
    }

    @Test
    fun `the first answer is trimmed of its trailing newline and the fallback is not asked`() {
        val calls = mutableListOf<URL>()
        val service =
            DefaultExternalIpService(
                urls = listOf(primary, fallback),
                fetch = fetchFrom(mapOf(primary to "203.0.113.10\n", fallback to "198.51.100.1"), calls),
            )

        assertThat(service.getExternalIpAddress()).isEqualTo("203.0.113.10")
        assertThat(calls).containsExactly(primary)
    }

    @Test
    fun `a failing first service falls back to the second`() {
        val service =
            DefaultExternalIpService(
                urls = listOf(primary, fallback),
                fetch = fetchFrom(mapOf(primary to IOException("refused"), fallback to "198.51.100.1")),
            )

        assertThat(service.getExternalIpAddress()).isEqualTo("198.51.100.1")
    }

    @Test
    fun `an answer that is not an IPv4 address falls back to the second`() {
        listOf("<html>error</html>", "", "2001:db8::1", "203.0.113.0/24", "300.1.1.1").forEach { bad ->
            val service =
                DefaultExternalIpService(
                    urls = listOf(primary, fallback),
                    fetch = fetchFrom(mapOf(primary to bad, fallback to "198.51.100.1")),
                )

            assertThat(service.getExternalIpAddress()).describedAs("first answer '%s'", bad).isEqualTo("198.51.100.1")
        }
    }

    @Test
    fun `when both services fail the last failure is thrown with the first attached`() {
        val first = IOException("refused")
        val service =
            DefaultExternalIpService(
                urls = listOf(primary, fallback),
                fetch = fetchFrom(mapOf(primary to first, fallback to "not an ip")),
            )

        assertThatThrownBy { service.getExternalIpAddress() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(fallback.toString())
            .hasMessageContaining("not an ip")
            .satisfies({ assertThat(it.suppressed).containsExactly(first) })
    }
}
