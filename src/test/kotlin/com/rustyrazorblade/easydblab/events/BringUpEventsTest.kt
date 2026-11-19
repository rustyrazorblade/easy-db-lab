package com.rustyrazorblade.easydblab.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class BringUpEventsTest {
    @Test
    fun `the progress line names every step with nodes in it, in pipeline order, and the totals`() {
        val event =
            Event.Provision.BringUpProgress(
                elapsedSeconds = 270,
                total = 80,
                byStep = linkedMapOf("ssh" to 2, "setup" to 11, "registry-tls" to 0),
                joining = 16,
                joinLimit = 16,
                ready = 49,
                replacements = 1,
                failed = 0,
                controlPlane = Event.Provision.ControlPlaneState.HEALTHY,
            )

        assertThat(event.toDisplayString())
            .isEqualTo("[4m30s] 80 nodes: ssh 2, setup 11, join 16/16, ready 49, replaced 1, failed 0, control plane healthy")
    }

    @ParameterizedTest(name = "{0}s reads as {1}")
    @CsvSource("0, 0s", "59, 59s", "60, 1m00s", "270, 4m30s", "3599, 59m59s", "3600, 1h00m00s", "3725, 1h02m05s")
    fun `the progress line's elapsed time`(
        seconds: Long,
        expected: String,
    ) {
        val event =
            Event.Provision.BringUpProgress(
                elapsedSeconds = seconds,
                total = 1,
                byStep = emptyMap(),
                joining = 0,
                joinLimit = 16,
                ready = 0,
                replacements = 0,
                failed = 0,
                controlPlane = Event.Provision.ControlPlaneState.STARTING,
            )

        assertThat(event.toDisplayString())
            .isEqualTo("[$expected] 1 nodes: join 0/16, ready 0, replaced 0, failed 0, control plane starting")
    }

    @Test
    fun `nodes that failed for good are each named with their step, reason, last instance and whether it is kept`() {
        val event =
            Event.Provision.NodesFailed(
                listOf(
                    Event.Provision.FailedNode("db3", "setup", "exit 1", "i-0abc", instanceKept = true),
                    Event.Provision.FailedNode("app0", "ready", "not Ready after 600s", "i-0def", instanceKept = true),
                    Event.Provision.FailedNode(
                        "db5",
                        "setup",
                        "the replacement failed at launch: no capacity",
                        "i-0fed",
                        instanceKept = false,
                    ),
                ),
            )

        assertThat(event.isError()).isTrue()
        assertThat(event.toDisplayString()).isEqualTo(
            """
            3 node(s) could not be brought up:
              db3 (i-0abc, kept for debugging): setup: exit 1
              app0 (i-0def, kept for debugging): ready: not Ready after 600s
              db5 (i-0fed, terminated, no instance left): setup: the replacement failed at launch: no capacity
            """.trimIndent(),
        )
    }

    @Test
    fun `the progress line names the control node's step first while its pipeline runs`() {
        val event =
            Event.Provision.BringUpProgress(
                elapsedSeconds = 30,
                total = 3,
                byStep = linkedMapOf("join-wait" to 3),
                joining = 0,
                joinLimit = 16,
                ready = 0,
                replacements = 0,
                failed = 0,
                controlPlane = Event.Provision.ControlPlaneState.STARTING,
                controlStep = "control-plane",
            )

        assertThat(event.toDisplayString()).startsWith("[30s] 3 nodes: control control-plane, join-wait 3, join 0/16")
    }

    @ParameterizedTest(name = "kept {3}")
    @CsvSource(
        "db1, setup, exit 1, true, 'db1 failed at setup: exit 1'",
        "db1, setup, exit 1, false, 'db1 failed at setup: exit 1 (i-0abc terminated)'",
    )
    fun `a failed node is reported when it fails, with its instance named only when it is gone`(
        alias: String,
        step: String,
        reason: String,
        kept: Boolean,
        expected: String,
    ) {
        val event = Event.Provision.NodeFailed(alias, step, reason, "i-0abc", kept)

        assertThat(event.isError()).isTrue()
        assertThat(event.toDisplayString()).isEqualTo(expected)
    }

    @Test
    fun `a failed control node says every instance is kept`() {
        val event = Event.Provision.ControlNodeFailed("control0", "gate", "the API server was not ready after 600s")

        assertThat(event.isError()).isTrue()
        assertThat(event.toDisplayString()).isEqualTo(
            "Control node control0 failed at gate: the API server was not ready after 600s. " +
                "Every instance is kept; run up again after fixing the cause.",
        )
    }
}
