package com.rustyrazorblade.easydblab.proxy

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * What [SocksTunnelReachabilityProbe] does with input that is not a network failure. A port that no
 * socket address can hold is a corrupt recorded value, not an unreachable tunnel, so the probe fails
 * fast instead of reporting the tunnel as down and being retried for the whole verify window.
 */
class SocksTunnelReachabilityProbeTest {
    @Test
    fun `a target port outside the valid range throws instead of reporting the tunnel unreachable`() {
        assertThatThrownBy { SocksTunnelReachabilityProbe(attemptTimeoutMs = 100).isReachable(1080, "127.0.0.1", 70000) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
