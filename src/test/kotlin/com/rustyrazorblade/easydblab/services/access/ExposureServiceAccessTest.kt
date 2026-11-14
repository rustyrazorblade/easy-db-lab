package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.configuration.Exposure
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** The seam a command gets is the one its cluster's recorded exposure names. */
class ExposureServiceAccessTest {
    private class Named(
        val name: String,
    ) : ServiceAccess {
        var closed = false

        override fun open(target: ServiceTarget): ServiceEndpoint = ServiceEndpoint.Plain(name, target.servicePort)

        override fun close() {
            closed = true
        }
    }

    private val built = mutableListOf<Named>()

    private fun accessFor(exposure: () -> Exposure) =
        ExposureServiceAccess(
            exposure,
            mapOf(
                Exposure.Socks to { Named("socks").also { built.add(it) } },
                Exposure.Direct to { Named("direct").also { built.add(it) } },
                Exposure.PortForward to { Named("port-forward").also { built.add(it) } },
            ),
        )

    @ParameterizedTest
    @EnumSource(Exposure::class)
    fun `the recorded exposure selects its implementation`(exposure: Exposure) {
        val expected = mapOf(Exposure.Socks to "socks", Exposure.Direct to "direct", Exposure.PortForward to "port-forward")

        assertThat(accessFor { exposure }.open(ClusterServices.LOKI).host).isEqualTo(expected.getValue(exposure))
    }

    @Test
    fun `an unchanged exposure keeps one implementation, closed with the access`() {
        val access = accessFor { Exposure.PortForward }

        access.open(ClusterServices.LOKI)
        access.open(ClusterServices.MIMIR)
        access.close()

        assertThat(built.single().closed).isTrue()
    }

    @Test
    fun `an exposure changed since the last open closes the old implementation and builds the new one`() {
        // A REPL can run init between two commands, recording another exposure.
        var recorded = Exposure.Socks
        val access = accessFor { recorded }

        access.open(ClusterServices.LOKI)
        recorded = Exposure.PortForward
        val endpoint = access.open(ClusterServices.LOKI)

        assertThat(endpoint.host).isEqualTo("port-forward")
        assertThat(built.map { it.name }).containsExactly("socks", "port-forward")
        assertThat(built.first().closed).isTrue()
        assertThat(built.last().closed).isFalse()
    }

    @Test
    fun `closing an access that opened nothing reads no state`() {
        accessFor { error("no state") }.close()

        assertThat(built).isEmpty()
    }
}
