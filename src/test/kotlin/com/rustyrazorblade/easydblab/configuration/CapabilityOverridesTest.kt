package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CapabilityOverridesTest {
    private val validNames = NodeCapability.entries.map { it.serialName }.toTypedArray()

    @Test
    fun `an override to false removes the capability and keeps the rest`() {
        val effective = CapabilityOverrides.parse(listOf("privileged=false")).applyTo(NodeCapabilities.AWS)

        assertThat(effective.present())
            .containsExactlyElementsOf(NodeCapability.entries - NodeCapability.PRIVILEGED)
    }

    @Test
    fun `an override to true adds a capability the declared or probed set lacks`() {
        val probed = NodeCapabilities(setOf(NodeCapability.PRIVILEGED))

        val effective = CapabilityOverrides.parse(listOf("host-path=true")).applyTo(probed)

        assertThat(effective.present()).containsExactly(NodeCapability.PRIVILEGED, NodeCapability.HOST_PATH)
    }

    @Test
    fun `no overrides leave the set as it is`() {
        assertThat(CapabilityOverrides.parse(emptyList()).applyTo(NodeCapabilities.LOCAL_DOCKER))
            .isEqualTo(NodeCapabilities.LOCAL_DOCKER)
    }

    @Test
    fun `the same override given twice is accepted`() {
        val effective = CapabilityOverrides.parse(listOf("s3=false", "s3=false")).applyTo(NodeCapabilities.AWS)

        assertThat(NodeCapability.S3 in effective).isFalse()
    }

    @Test
    fun `an unknown name fails naming it and listing the valid names`() {
        assertThatThrownBy { CapabilityOverrides.parse(listOf("gpu=true")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("'gpu'")
            .hasMessageContainingAll(*validNames)
    }

    @Test
    fun `a value other than true or false fails`() {
        assertThatThrownBy { CapabilityOverrides.parse(listOf("privileged=yes")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("privileged=yes")
            .hasMessageContainingAll(*validNames)
    }

    @Test
    fun `an override without a value fails`() {
        assertThatThrownBy { CapabilityOverrides.parse(listOf("privileged")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("'privileged'")
    }

    @Test
    fun `one name given two different values fails naming it`() {
        assertThatThrownBy { CapabilityOverrides.parse(listOf("privileged=true", "privileged=false")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("privileged")
            .hasMessageContaining("both")
    }
}
