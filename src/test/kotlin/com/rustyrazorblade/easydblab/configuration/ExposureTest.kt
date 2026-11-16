package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The exposure an AWS `init` records from how this machine reaches the new cluster. */
class ExposureTest {
    @Test
    fun `a cluster not reached by Tailscale goes through SOCKS`() {
        assertThat(Exposure.atInit(tailscaleActive = false)).isEqualTo(Exposure.Socks)
    }

    @Test
    fun `Tailscale reaches the nodes directly`() {
        assertThat(Exposure.atInit(tailscaleActive = true)).isEqualTo(Exposure.Direct)
    }
}
