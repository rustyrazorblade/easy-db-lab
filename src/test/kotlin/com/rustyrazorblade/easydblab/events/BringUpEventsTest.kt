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

    /** The node's own NodeFailed already printed the whole reason; at 80 nodes the list must stay one line each. */
    @Test
    fun `the failure list shows only the first line of a reason that has several`() {
        val event =
            Event.Provision.NodesFailed(
                listOf(
                    Event.Provision.FailedNode(
                        "db3",
                        "join",
                        "no node db3 registered\nlast lines of the agent log:\nline 1\nline 2",
                        "i-0abc",
                        instanceKept = true,
                    ),
                ),
            )

        assertThat(event.toDisplayString()).isEqualTo(
            """
            1 node(s) could not be brought up:
              db3 (i-0abc, kept for debugging): join: no node db3 registered
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

    /** An attempt left running is an error: it goes to stderr, where `up --quiet` users look. */
    @Test
    fun `an abandoned attempt is an error naming the node, step, attempt and grace`() {
        val event = Event.Provision.AttemptAbandoned("db3", "setup", 2, 5000)

        assertThat(event.isError()).isTrue()
        assertThat(event.toDisplayString())
            .isEqualTo("db3: setup attempt 2 did not stop within 5000ms of its interrupt and is still running")
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

    @Test
    fun `a failed node shows its last output indented under the failure line`() {
        val event =
            Event.Provision.NodeFailed(
                "db7",
                "setup",
                "exit 1",
                "i-0abc",
                instanceKept = false,
                outputTail = listOf("Installing packages", "E: Unable to locate package foo"),
            )

        assertThat(event.toDisplayString()).isEqualTo(
            """
            db7 failed at setup: exit 1 (i-0abc terminated)
            last output of db7:
                Installing packages
                E: Unable to locate package foo
            """.trimIndent(),
        )
    }

    @Test
    fun `a failed control node and a failed instance setup show their last output`() {
        val control = Event.Provision.ControlNodeFailed("control0", "k3s", "exit 1", outputTail = listOf("k3s: bad flag"))
        val setup = Event.Provision.InstanceSetupFailed("db0", "exit 2", outputTail = listOf("disk not found"))

        assertThat(control.toDisplayString()).endsWith("fixing the cause.\nlast output of control0:\n    k3s: bad flag")
        assertThat(setup.toDisplayString()).isEqualTo("Instance setup failed on db0: exit 2\nlast output of db0:\n    disk not found")
    }

    @Test
    fun `the output tail survives a round trip through JSON`() {
        val envelope =
            EventEnvelope(
                event = Event.Provision.NodeFailed("db1", "setup", "exit 1", "i-1", true, listOf("a", "b")),
                timestamp = "2026-01-01T00:00:00.000Z",
                commandName = "up",
            )

        assertThat(EventEnvelope.fromJson(EventEnvelope.toJson(envelope))).isEqualTo(envelope)
    }
}
