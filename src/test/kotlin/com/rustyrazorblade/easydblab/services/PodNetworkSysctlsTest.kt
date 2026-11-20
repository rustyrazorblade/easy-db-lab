package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The pod network values come from the packaged host sysctl file (pod-network-tuning): every
 * `net.*` key of that file is classed as injected or host only, and the injected ones carry the
 * file's values.
 */
class PodNetworkSysctlsTest {
    private fun packagedNetworkKeys(): List<String> =
        PodNetworkSysctls.parse(PodNetworkSysctls.packagedFile()).keys.filter {
            it.startsWith("net.")
        }

    @Test
    fun `every network key of the host file is classed exactly once, as injected into pods or host only`() {
        // The concatenation holds a key twice when it is in both lists; the file's keys are distinct.
        val classed = Constants.NodeTuning.POD_NETWORK_SYSCTLS + Constants.NodeTuning.NOT_INJECTED_NETWORK_SYSCTLS
        assertThat(classed)
            .describedAs(
                "a net.* key in the packaged sysctl file is in neither or both of POD_NETWORK_SYSCTLS and NOT_INJECTED_NETWORK_SYSCTLS",
            ).containsExactlyInAnyOrderElementsOf(packagedNetworkKeys())
    }

    @Test
    fun `the pods get the host file's values for the injected keys, and the port range stays out`() {
        val values = PodNetworkSysctls.packaged()

        assertThat(values.keys).containsExactlyElementsOf(Constants.NodeTuning.POD_NETWORK_SYSCTLS)
        assertThat(values).containsEntry("net.core.somaxconn", "65535")
        assertThat(values).containsEntry("net.ipv4.tcp_rmem", "4096 131072 16777216")
        assertThat(values).containsEntry("net.ipv4.tcp_keepalive_time", "60")
        assertThat(values).doesNotContainKeys("net.ipv4.ip_local_port_range", "net.ipv4.ip_local_reserved_ports")
    }

    @Test
    fun `parsing keeps multi-word values and skips comments and blank lines`() {
        val parsed =
            PodNetworkSysctls.parse(
                """
                # a comment = with an equals sign
                net.ipv4.tcp_wmem =   4096 16384 16777216

                vm.swappiness=1
                """.trimIndent(),
            )

        assertThat(parsed).containsExactlyEntriesOf(mapOf("net.ipv4.tcp_wmem" to "4096 16384 16777216", "vm.swappiness" to "1"))
    }

    @Test
    fun `an injected key missing from the file fails and names the key`() {
        assertThatThrownBy {
            PodNetworkSysctls.select(mapOf("net.core.somaxconn" to "65535"), listOf("net.core.somaxconn", "net.ipv4.tcp_tw_reuse"))
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("[net.ipv4.tcp_tw_reuse]")
    }
}
