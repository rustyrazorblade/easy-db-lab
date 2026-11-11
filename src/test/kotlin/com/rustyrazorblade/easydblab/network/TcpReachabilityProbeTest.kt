package com.rustyrazorblade.easydblab.network

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Tests for [SocketTcpReachabilityProbe], the probe `up` trusts when it decides whether this
 * machine has a route to the cluster's private network.
 *
 * Both cases use an OS-assigned loopback port, so nothing here depends on a fixed port or on the
 * host's wider network being in any particular state.
 */
class TcpReachabilityProbeTest {
    private val probe = SocketTcpReachabilityProbe(connectTimeoutMs = 500)

    @Test
    fun `reports reachable when something is listening`() {
        ServerSocket(0).use { listener ->
            assertThat(probe.isReachable("127.0.0.1", listener.localPort)).isTrue()
        }
    }

    @Test
    fun `reports unreachable when nothing is listening`() {
        // The port stays bound, but not listening, for the whole assertion: a port closed first could be
        // taken by another process's listener before the probe connects. Linux refuses the connect;
        // macOS drops it, so the probe's own timeout ends it. Either way nothing is reachable there.
        Socket().use { bound ->
            bound.bind(InetSocketAddress("127.0.0.1", 0))

            assertThat(probe.isReachable("127.0.0.1", bound.localPort)).isFalse()
        }
    }
}
