package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.events.Event
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class BringUpProgressTest {
    private val start = Instant.parse("2026-11-16T00:00:00Z")

    @Test
    fun `counts every node in its current step after a scripted sequence`() {
        val clock = Clock.fixed(start.plusSeconds(270), ZoneOffset.UTC)
        val progress = BringUpProgress(total = 6, joinLimit = 16, clock = clock, startedAt = start)

        progress.enter("db0", PipelineStep.SSH)
        progress.enter("db0", PipelineStep.SETUP)
        progress.enter("db1", PipelineStep.SETUP)
        progress.enter("db2", PipelineStep.JOIN_WAIT)
        progress.enter("db3", PipelineStep.JOIN)
        progress.enter("db4", PipelineStep.JOIN)
        progress.ready("db4")
        progress.enter("app0", PipelineStep.SETUP)
        progress.replaced()
        progress.enter("app0", PipelineStep.RUNNING)
        progress.failed("db1")
        progress.controlPlane(Event.Provision.ControlPlaneState.HEALTHY)

        assertThat(progress.snapshot()).isEqualTo(
            Event.Provision.BringUpProgress(
                elapsedSeconds = 270,
                total = 6,
                byStep = linkedMapOf("running" to 1, "setup" to 1, "join-wait" to 1),
                joining = 1,
                joinLimit = 16,
                ready = 1,
                replacements = 1,
                failed = 1,
                controlPlane = Event.Provision.ControlPlaneState.HEALTHY,
            ),
        )
    }

    @Test
    fun `the steps are listed in pipeline order whatever order nodes entered them`() {
        val progress = BringUpProgress(total = 3, joinLimit = 16, clock = Clock.fixed(start, ZoneOffset.UTC), startedAt = start)

        progress.enter("db0", PipelineStep.READINESS)
        progress.enter("db1", PipelineStep.SSH)
        progress.enter("db2", PipelineStep.REGISTRY_TLS)

        assertThat(progress.snapshot().byStep.keys).containsExactly("ssh", "registry-tls", "readiness")
    }

    @Test
    fun `the control node's step is shown on its own, and goes once its pipeline ends`() {
        val progress = BringUpProgress(total = 2, joinLimit = 16, clock = Clock.fixed(start, ZoneOffset.UTC), startedAt = start)

        progress.controlEntered(PipelineStep.CONTROL_PLANE)
        progress.enter("db0", PipelineStep.SETUP)
        val during = progress.snapshot()
        progress.controlEntered(null)

        assertThat(during.controlStep).isEqualTo("control-plane")
        assertThat(during.byStep).containsExactly(entry("setup", 1))
        assertThat(during.toDisplayString()).startsWith("[0s] 2 nodes: control control-plane, setup 1, join 0/16")
        assertThat(progress.snapshot().controlStep).isNull()
    }

    /** The progress total is the nodes `up` runs pipelines for: the ones `--hosts` names, never the control node. */
    @ParameterizedTest(name = "--hosts \"{0}\" of {1} nodes runs {2}")
    @CsvSource(
        "'', 10, 10",
        "'db1', 10, 1",
        "'db1,db2, app0', 10, 3",
        "'db1,db1', 10, 1",
        "'control0,db1', 10, 1",
        "'db1,db2,db3', 2, 2",
    )
    fun `the run count is the db and app nodes --hosts names, or all of them`(
        hosts: String,
        nodes: Int,
        expected: Int,
    ) {
        assertThat(BringUpSettings(nodeCount = nodes, hostFilter = hosts).runCount).isEqualTo(expected)
    }
}
