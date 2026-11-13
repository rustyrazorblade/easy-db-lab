package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The exposure `init` records from how this machine reaches the new cluster. */
class ExposureTest {
    @Test
    fun `a cluster reached by neither Tailscale nor the direct network goes through SOCKS`() {
        assertThat(Exposure.atInit(tailscaleActive = false, directNetwork = false)).isEqualTo(Exposure.Socks)
    }

    @Test
    fun `Tailscale reaches the nodes directly`() {
        assertThat(Exposure.atInit(tailscaleActive = true, directNetwork = false)).isEqualTo(Exposure.Direct)
    }

    @Test
    fun `the direct network mode reaches the nodes directly`() {
        assertThat(Exposure.atInit(tailscaleActive = false, directNetwork = true)).isEqualTo(Exposure.Direct)
    }
}
