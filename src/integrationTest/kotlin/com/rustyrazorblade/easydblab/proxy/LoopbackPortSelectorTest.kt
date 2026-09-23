package com.rustyrazorblade.easydblab.proxy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Integration-tier tests for [LoopbackPortSelector]: the fallback decision depends on a real bind
 * probe, so these hold real listening sockets. Every occupied port is OS-assigned via
 * `ServerSocket(0)` and handed to the selector as its preferred port, so no test binds a hardcoded
 * port (issue #750).
 */
class LoopbackPortSelectorTest {
    @Test
    fun `falls back to an OS-assigned port when the preferred port is bound`() {
        ServerSocket(0).use { occupied ->
            val occupiedPort = occupied.localPort

            // With the preferred port already bound, the loopback probe must detect the conflict
            // and return a different, OS-assigned port rather than the occupied one.
            val selected = LoopbackPortSelector(preferred = occupiedPort).select()

            assertThat(selected).isNotEqualTo(occupiedPort)
            assertThat(selected).isGreaterThan(0)
        }
    }

    @Test
    fun `falls back when the preferred port is bound only on the loopback interface (ssh -D style)`() {
        // Regression: ssh -D binds 127.0.0.1/::1 with SO_REUSEADDR, not the wildcard address. A
        // wildcard probe with SO_REUSEADDR (the old behavior) coexists with that listener and wrongly
        // reports the port as free, so the second datacenter kept retrying the busy port and ssh
        // failed with "bind [::1]:<port>: Address already in use". The selector must probe loopback
        // and fall back to a different port.
        val loopback = ServerSocket()
        loopback.reuseAddress = true
        loopback.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        loopback.use {
            val occupiedPort = it.localPort

            val selected = LoopbackPortSelector(preferred = occupiedPort).select()

            assertThat(selected).isNotEqualTo(occupiedPort)
            assertThat(selected).isGreaterThan(0)
        }
    }
}
