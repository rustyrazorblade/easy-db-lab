package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Integration-tier tests for [LoopbackPortSelector]: whether a port counts as free depends on a real
 * bind probe, so these hold real listening sockets. Every occupied port is OS-assigned via
 * `ServerSocket(0)` and handed to the selector as a candidate, so no test binds a hardcoded port
 * (issue #750).
 */
class LoopbackPortSelectorTest {
    @Test
    fun `picks random ports from the range and prefers none`() {
        val selector = LoopbackPortSelector()

        val picks = List(PICKS) { selector.select() }

        assertThat(picks).allMatch { it in Constants.Proxy.LOCAL_PORT_RANGE }
        assertThat(picks.toSet()).withFailMessage("the same port every time: $picks").hasSizeGreaterThan(1)
    }

    @Test
    fun `skips a bound candidate for the next free one`() {
        val free = freePort()
        ServerSocket(0).use { occupied ->
            val candidates = ArrayDeque(listOf(occupied.localPort, free))

            val selected = LoopbackPortSelector(candidates = { candidates.removeFirst() }).select()

            assertThat(selected).isEqualTo(free)
        }
    }

    @Test
    fun `skips a candidate bound only on the loopback interface (ssh -D style)`() {
        // Regression: ssh -D binds 127.0.0.1/::1 with SO_REUSEADDR, not the wildcard address. A
        // wildcard probe with SO_REUSEADDR (the old behavior) coexists with that listener and wrongly
        // reports the port as free, so the second datacenter kept retrying the busy port and ssh
        // failed with "bind [::1]:<port>: Address already in use". The selector must probe loopback.
        val free = freePort()
        val loopback = ServerSocket()
        loopback.reuseAddress = true
        loopback.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        loopback.use {
            val candidates = ArrayDeque(listOf(it.localPort, free))

            val selected = LoopbackPortSelector(candidates = { candidates.removeFirst() }).select()

            assertThat(selected).isEqualTo(free)
        }
    }

    @Test
    fun `fails naming the range when every candidate is bound, rather than taking an ephemeral port`() {
        ServerSocket(0).use { occupied ->
            assertThatThrownBy { LoopbackPortSelector(candidates = { occupied.localPort }, attempts = 3).select() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("${Constants.Proxy.LOCAL_PORT_RANGE.first}-${Constants.Proxy.LOCAL_PORT_RANGE.last}")
                .hasMessageContaining("3 random ports")
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val PICKS = 10
    }
}
