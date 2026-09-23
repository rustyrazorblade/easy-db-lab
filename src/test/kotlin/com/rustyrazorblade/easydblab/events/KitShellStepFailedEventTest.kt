package com.rustyrazorblade.easydblab.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** How a failed kit shell step reads to the user: the step, the exit code, and its last output. */
class KitShellStepFailedEventTest {
    @Test
    fun `names the step and exit code, then the tail of the output`() {
        val event =
            Event.Kit.ShellStepFailed(
                kit = "presto",
                phase = "stop",
                stepIndex = 1,
                exitCode = 1,
                outputTail = listOf("error: deployments.apps \"presto-worker\" not found", "exit status 1"),
            )

        assertThat(event.toDisplayString()).isEqualTo(
            """
            [presto] stop step 2 (shell) failed with exit code 1. Last output:
              error: deployments.apps "presto-worker" not found
              exit status 1
            """.trimIndent(),
        )
        assertThat(event.isError()).isTrue()
    }

    @Test
    fun `a step that printed nothing is reported without an empty output section`() {
        val event = Event.Kit.ShellStepFailed(kit = "presto", phase = "stop", stepIndex = 0, exitCode = 2, outputTail = emptyList())

        assertThat(event.toDisplayString()).isEqualTo("[presto] stop step 1 (shell) failed with exit code 2.")
    }
}
