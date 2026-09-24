package com.rustyrazorblade.easydblab.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * How a failed kit shell step reads to the user: the step and the exit code. The step's output
 * was already streamed to the console as it ran, so repeating its tail printed every failure
 * message twice (neo4j's version error, once live and again under "Last output"). The tail stays
 * on the event for structured consumers.
 */
class KitShellStepFailedEventTest {
    @Test
    fun `names the step and exit code without repeating output that was already streamed`() {
        val event =
            Event.Kit.ShellStepFailed(
                kit = "neo4j",
                phase = "start",
                stepIndex = 1,
                exitCode = 1,
                outputTail = listOf("Error: neo4j version 4.4 is not supported", "exit status 1"),
            )

        assertThat(event.toDisplayString()).isEqualTo("[neo4j] start step 2 (shell) failed with exit code 1.")
        assertThat(event.outputTail).containsExactly("Error: neo4j version 4.4 is not supported", "exit status 1")
        assertThat(event.isError()).isTrue()
    }
}
