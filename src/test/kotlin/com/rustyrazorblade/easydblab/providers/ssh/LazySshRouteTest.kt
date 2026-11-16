package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.configuration.Host
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [LazySshRoute] exists so a route can be bound before the profile that decides its transport
 * exists. These tests pin that decision: nothing is resolved at construction, the first use
 * resolves once, and a resolution failure surfaces at the use, not at the binding.
 */
internal class LazySshRouteTest {
    private val host =
        Host(public = "54.1.2.3", private = "10.0.0.7", alias = "db0", availabilityZone = "us-west-2a", instanceId = "i-0abc")

    @Test
    fun `binding and closing a route never resolve it`() {
        val resolutions = AtomicInteger()
        val route =
            LazySshRoute {
                resolutions.incrementAndGet()
                DirectSshRoute(22)
            }

        route.close()

        assertThat(resolutions).hasValue(0)
    }

    @Test
    fun `the first use resolves the real route once and every member goes to it`() {
        val resolutions = AtomicInteger()
        val route =
            LazySshRoute {
                resolutions.incrementAndGet()
                DirectSshRoute(2222)
            }

        assertThat(route.endpoint(host)).isEqualTo(SshEndpoint("54.1.2.3", 2222))
        assertThat(route.proxyCommand(host)).isNull()
        assertThat(route.tunnelVerifyAttempts).isPositive()
        assertThat(route.authTimeout).isNull()

        assertThat(resolutions).hasValue(1)
    }

    @Test
    fun `a profile that cannot be read fails at the use, not at the binding`() {
        val route = LazySshRoute { error("User configuration file not found") }

        assertThatThrownBy { route.endpoint(host) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("User configuration file not found")
    }

    @Test
    fun `close reaches a route that was used`() {
        val real = ClosableRoute()
        val route = LazySshRoute { real }

        route.proxyCommand(host)
        route.close()

        assertThat(real.closed).hasValue(1)
    }

    /** A route that counts how often it was closed. */
    private class ClosableRoute : SshRoute {
        val closed = AtomicInteger()

        override fun endpoint(host: Host): SshEndpoint = SshEndpoint(host.public, 22)

        override fun proxyCommand(host: Host): String? = null

        override val tunnelVerifyAttempts: Int = 1

        override fun close() {
            closed.incrementAndGet()
        }
    }
}
