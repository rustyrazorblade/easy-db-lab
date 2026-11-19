package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.configuration.Host
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Tests that [DeferredSshRoute] picks the transport only when a host is actually reached. Picking
 * it reads the AWS profile, so a command that merely builds an SSH-capable service (in a local
 * workspace, where there is no profile) must never trigger it, not even when it shuts down.
 */
internal class DeferredSshRouteTest {
    private val host =
        Host(public = "54.1.2.3", private = "10.0.0.7", alias = "db0", availabilityZone = "us-west-2a", instanceId = "i-0abc")

    private var selections = 0
    private var closed = 0

    private val selected =
        object : SshRoute by DirectSshRoute(SSH_PORT) {
            override fun close() {
                closed++
            }
        }

    private val route =
        DeferredSshRoute {
            selections++
            selected
        }

    @Test
    fun `building the route selects no transport`() {
        assertThat(selections).isZero()
    }

    @Test
    fun `the first use selects the transport once and every use goes to it`() {
        assertThat(route.endpoint(host)).isEqualTo(SshEndpoint("54.1.2.3", SSH_PORT))
        assertThat(route.proxyCommand(host)).isNull()
        assertThat(route.tunnelVerifyAttempts).isEqualTo(selected.tunnelVerifyAttempts)

        assertThat(selections).isEqualTo(1)
    }

    @Test
    fun `a profile that cannot be read fails at the use, not at the binding`() {
        // `profile setup` builds the route before the profile it writes exists.
        val unreadable = DeferredSshRoute { error("User configuration file not found") }

        assertThatThrownBy { unreadable.endpoint(host) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("User configuration file not found")
    }

    @Test
    fun `closing a route that was never used selects nothing`() {
        route.close()

        assertThat(selections).isZero()
        assertThat(closed).isZero()
    }

    @Test
    fun `closing a used route closes the selected one`() {
        route.endpoint(host)

        route.close()

        assertThat(closed).isEqualTo(1)
    }

    private companion object {
        const val SSH_PORT = 22
    }
}
