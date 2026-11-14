package com.rustyrazorblade.easydblab.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File

class NodeCapabilitiesTest {
    private val jackson = ObjectMapper()

    private fun state(capabilities: Set<NodeCapability>?) =
        ClusterState(name = "lab", versions = mutableMapOf(), capabilities = capabilities)

    @Test
    fun `AWS declares the pod-security and node SSH capabilities`() {
        assertThat(NodeCapabilities.AWS.present()).contains(
            NodeCapability.PRIVILEGED,
            NodeCapability.HOST_NETWORK,
            NodeCapability.HOST_PATH,
            NodeCapability.NODE_SSH,
        )
    }

    @Test
    fun `local Docker admits privileged, host network and host path pods but has no node SSH`() {
        assertThat(NodeCapabilities.LOCAL_DOCKER.present()).containsExactlyInAnyOrder(
            NodeCapability.PRIVILEGED,
            NodeCapability.HOST_NETWORK,
            NodeCapability.HOST_PATH,
        )
    }

    @Test
    fun `a recorded set is the cluster's capabilities`() {
        val recorded = NodeCapability.entries.toSet() - NodeCapability.PRIVILEGED

        val capabilities = NodeCapabilities.from(state(recorded))

        assertThat(NodeCapability.PRIVILEGED in capabilities).isFalse()
        assertThat(capabilities.present()).containsExactlyInAnyOrderElementsOf(recorded)
    }

    @Test
    fun `a state that records no set reads as AWS`() {
        assertThat(NodeCapabilities.from(state(null))).isEqualTo(NodeCapabilities.AWS)
    }

    @Test
    fun `present lists the capabilities in enum order`() {
        val capabilities = NodeCapabilities(setOf(NodeCapability.NODE_SSH, NodeCapability.S3, NodeCapability.SYSTEMD_JOURNAL))

        assertThat(capabilities.present())
            .containsExactly(NodeCapability.SYSTEMD_JOURNAL, NodeCapability.S3, NodeCapability.NODE_SSH)
    }

    @Test
    fun `the recorded set survives a save and load, stored by serial name`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)
        val recorded = setOf(NodeCapability.HOST_NETWORK, NodeCapability.NODE_SSH)

        manager.save(state(recorded))

        assertThat(stateFile.readText()).contains("\"host-network\"", "\"node-ssh\"")
        assertThat(manager.load().capabilities).isEqualTo(recorded)
    }

    /**
     * The serial name is written three times per entry (constructor, `@SerialName`, `@JsonProperty`);
     * a typo in any one splits `state.json`, `init --capability` and `cluster-config` apart.
     */
    @ParameterizedTest
    @EnumSource(NodeCapability::class)
    fun `every capability round-trips under its serial name in kotlinx and Jackson`(capability: NodeCapability) {
        val quoted = "\"${capability.serialName}\""

        assertThat(Json.encodeToString(capability)).isEqualTo(quoted)
        assertThat(Json.decodeFromString<NodeCapability>(quoted)).isEqualTo(capability)
        assertThat(jackson.writeValueAsString(capability)).isEqualTo(quoted)
        assertThat(jackson.readValue(quoted, NodeCapability::class.java)).isEqualTo(capability)
        assertThat(NodeCapability.fromSerialName(capability.serialName)).isEqualTo(capability)
    }

    @Test
    fun `a saved state without the field loads with no recorded set`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        stateFile.writeText("""{"name":"lab","versions":{}}""")

        assertThat(ClusterStateManager(stateFile).load().capabilities).isNull()
    }
}
