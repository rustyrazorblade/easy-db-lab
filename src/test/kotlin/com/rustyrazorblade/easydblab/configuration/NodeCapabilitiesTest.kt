package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NodeCapabilitiesTest {
    @Test
    fun `AWS provides every capability`() {
        NodeCapability.entries.forEach { assertThat(it in NodeCapabilities.AWS).isTrue() }
    }

    @Test
    fun `local Docker provides none`() {
        NodeCapability.entries.forEach { assertThat(it in NodeCapabilities.LOCAL_DOCKER).isFalse() }
    }

    @Test
    fun `an environment declaring only CloudWatch provides exactly that capability`() {
        val capabilities = NodeCapabilities(setOf(NodeCapability.CLOUDWATCH))

        assertThat(NodeCapability.CLOUDWATCH in capabilities).isTrue()
        assertThat(NodeCapability.SYSTEMD_JOURNAL in capabilities).isFalse()
        assertThat(NodeCapability.S3 in capabilities).isFalse()
    }

    @Test
    fun `a cluster with no recorded environment is treated as AWS`() {
        val state = ClusterState(name = "c", versions = mutableMapOf())

        assertThat(NodeCapabilities.from(state)).isEqualTo(NodeCapabilities.AWS)
    }

    @Test
    fun `every capability explains its absence`() {
        NodeCapability.entries.forEach { assertThat(it.absentReason).isNotBlank() }
    }
}
