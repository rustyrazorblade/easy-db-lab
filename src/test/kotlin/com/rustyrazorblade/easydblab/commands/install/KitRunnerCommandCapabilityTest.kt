package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.events.Event
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * `start` of a kit whose `kit.yaml` `requires` a node capability is checked against the
 * capabilities the cluster state records: an absent one stops the phase before any step runs.
 */
class KitRunnerCommandCapabilityTest : KitRunnerCommandTestBase() {
    private fun writeHostPathKit() =
        writeKitYaml(
            "mydb",
            """
            name: mydb
            requires:
              - host-path
            start:
              - type: shell
                script: echo start
            stop:
              - type: shell
                script: echo stop
            """.trimIndent(),
        )

    private fun recordCapabilities(vararg capabilities: NodeCapability) {
        whenever(mockClusterStateManager.load()).thenReturn(clusterState.copy(capabilities = capabilities.toSet()))
    }

    @Test
    fun `start of a kit requiring host-path fails before any step when the cluster lacks it`() {
        writeHostPathKit()
        recordCapabilities(NodeCapability.PRIVILEGED)

        var exitCode = 0
        val events = captureEvents { exitCode = command("mydb", "start").call() }

        assertThat(exitCode).isNotEqualTo(0)
        assertThat(events.filterIsInstance<Event.Kit.CapabilityMissing>().single())
            .isEqualTo(Event.Kit.CapabilityMissing(kit = "mydb", phase = "start", missing = listOf(NodeCapability.HOST_PATH)))
        verify(mockWorkloadStepExecutor, never()).execute(any(), any(), any())
        verify(mockClusterStateManager, never()).addRunningWorkload(any())
    }

    @Test
    fun `start of a kit requiring host-path runs when the cluster records it`() {
        writeHostPathKit()
        recordCapabilities(NodeCapability.HOST_PATH)

        val events = captureEvents { assertThat(command("mydb", "start").call()).isEqualTo(0) }

        assertThat(events.filterIsInstance<Event.Kit.CapabilityMissing>()).isEmpty()
        verify(mockWorkloadStepExecutor).execute(any(), any(), any())
    }

    @Test
    fun `stop is not refused for a missing capability, so a kit can always be taken down`() {
        writeHostPathKit()
        recordCapabilities()

        val events = captureEvents { assertThat(command("mydb", "stop").call()).isEqualTo(0) }

        assertThat(events.filterIsInstance<Event.Kit.CapabilityMissing>()).isEmpty()
    }
}
