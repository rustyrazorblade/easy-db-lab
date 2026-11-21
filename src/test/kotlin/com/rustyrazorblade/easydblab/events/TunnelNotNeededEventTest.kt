package com.rustyrazorblade.easydblab.events

import com.rustyrazorblade.easydblab.configuration.NetworkMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `socks start` on a cluster whose network mode needs no tunnel says why, per mode. A mode the event
 * has no text for must not be described as Tailscale or direct.
 */
class TunnelNotNeededEventTest {
    @Test
    fun `the Tailscale mode says Tailscale`() {
        assertThat(Event.Proxy.TunnelNotNeeded(NetworkMode.Tailscale.id).toDisplayString())
            .contains("uses Tailscale")
            .doesNotContain("reachable from this machine")
    }

    @Test
    fun `the direct mode says the cluster is reachable directly`() {
        assertThat(Event.Proxy.TunnelNotNeeded(NetworkMode.Direct.id).toDisplayString())
            .contains("reachable from this machine")
            .doesNotContain("Tailscale")
    }

    @Test
    fun `any other mode is named and described as neither`() {
        assertThat(Event.Proxy.TunnelNotNeeded(NetworkMode.Socks.id).toDisplayString())
            .contains("'socks'")
            .doesNotContain("Tailscale")
            .doesNotContain("reachable from this machine")
    }
}
