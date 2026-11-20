package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.services.PodNetworkSysctls
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The checks [PodNetworkSysctlPolicyBuilder] makes before it writes values into CEL, and the
 * `desired` variable it renders. What the API server does with the policy is proven in
 * `PodNetworkTuningIntegrationTest`.
 */
class PodNetworkSysctlPolicyBuilderTest {
    private fun desiredExpression(sysctls: Map<String, String>): String =
        PodNetworkSysctlPolicyBuilder(sysctls)
            .buildPolicy()
            .spec.variables
            .single { it.name == "desired" }
            .expression

    @Test
    fun `the desired variable holds every packaged sysctl as a name and value map`() {
        val sysctls = PodNetworkSysctls.packaged()

        val expression = desiredExpression(sysctls)

        sysctls.forEach { (key, value) ->
            assertThat(expression).contains("{\"name\": \"$key\", \"value\": \"$value\"}")
        }
    }

    @Test
    fun `a value with a quote or a backslash is refused`() {
        listOf("4096\"", "4096\\").forEach { value ->
            assertThatThrownBy { PodNetworkSysctlPolicyBuilder(mapOf("net.core.somaxconn" to value)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("net.core.somaxconn")
        }
    }

    @Test
    fun `a key that would break the CEL literal is refused`() {
        assertThatThrownBy { PodNetworkSysctlPolicyBuilder(mapOf("net.core\"} || true" to "1")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an empty set of sysctls is refused`() {
        assertThatThrownBy { PodNetworkSysctlPolicyBuilder(emptyMap()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
