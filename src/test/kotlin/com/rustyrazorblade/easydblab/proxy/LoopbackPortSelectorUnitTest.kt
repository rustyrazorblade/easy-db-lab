package com.rustyrazorblade.easydblab.proxy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetAddress

/**
 * [LoopbackPortSelector]'s choice of which loopback addresses a port must be free on, with the bind
 * probe replaced, so no real socket is bound. The tunnel listens on 127.0.0.1 only, so a host that
 * cannot bind `::1` at all (IPv6 off by sysctl, many containers, some WSL setups) must still get a port.
 */
internal class LoopbackPortSelectorUnitTest {
    @Test
    fun `a host that cannot bind the IPv6 loopback still gets a port`() {
        val probed = mutableListOf<InetAddress>()
        val ipv4Only: (InetAddress, Int) -> Boolean = { address, _ -> address.also { probed += it } is Inet4Address }

        val selected = LoopbackPortSelector(candidates = { PORT }, bindsCleanly = ipv4Only).select()

        assertThat(selected).isEqualTo(PORT)
        assertThat(probed).containsExactly(InetAddress.getByName("127.0.0.1"))
    }

    @Test
    fun `a port taken on 127 0 0 1 is passed over for the next candidate`() {
        val candidates = ArrayDeque(listOf(PORT, PORT + 1))
        val takenOnIpv4: (InetAddress, Int) -> Boolean = { _, port -> port != PORT }

        val selected = LoopbackPortSelector(candidates = { candidates.removeFirst() }, bindsCleanly = takenOnIpv4).select()

        assertThat(selected).isEqualTo(PORT + 1)
    }

    private companion object {
        const val PORT = 25000
    }
}
