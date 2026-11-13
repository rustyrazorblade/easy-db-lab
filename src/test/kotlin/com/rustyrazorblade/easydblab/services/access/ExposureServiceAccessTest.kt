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
    fun `the implementation is chosen once and closed with the access`() {
        var reads = 0
        val access = accessFor { Exposure.PortForward.also { reads++ } }

        access.open(ClusterServices.LOKI)
        access.open(ClusterServices.MIMIR)
        access.close()

        assertThat(reads).isEqualTo(1)
        assertThat(built.single().closed).isTrue()
    }

    @Test
    fun `closing an access that opened nothing reads no state`() {
        accessFor { error("no state") }.close()

        assertThat(built).isEmpty()
    }
}
