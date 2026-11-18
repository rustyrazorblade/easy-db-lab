package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.time.Instant
import java.util.stream.Stream

class RolloutWaitReportTest {
    private val start = Instant.parse("2026-11-17T12:00:00Z")
    private val d = WorkloadRef(WorkloadKind.Deployment, "d")
    private val a = WorkloadRef(WorkloadKind.DaemonSet, "a")

    private fun pod(
        name: String,
        node: String?,
    ): Pod =
        PodBuilder()
            .withNewMetadata()
            .withName(name)
            .endMetadata()
            .withNewSpec()
            .withNodeName(node)
            .endSpec()
            .withNewStatus()
            .withPhase("Running")
            .addNewContainerStatus()
            .withName("main")
            .withRestartCount(2)
            .withNewState()
            .withNewWaiting()
            .withReason("ContainerCreating")
            .endWaiting()
            .endState()
            .endContainerStatus()
            .endStatus()
            .build()

    @Test
    fun `a timeout names each pending workload and each pod it waits on, with its node and state`() {
        val look =
            RolloutsLook.Pending(
                reasons = listOf("$d: 0 of 1 updated replicas are available", "$a: 1 of 2 updated pods are available"),
                workloads = listOf(d, a),
                unready = listOf(WaitingPod(d, pod("d-1", "db0")), WaitingPod(a, pod("a-1", null))),
                unreadyOmitted = 4,
            )

        val event = RolloutWaitReport.timedOut(8, look)

        assertThat(event.isError()).isTrue()
        assertThat(event.pending).containsExactly("$d", "$a")
        assertThat(event.unreadyPods.map { it.node }).containsExactly("db0", null)
        assertThat(event.toDisplayString())
            .startsWith("Timed out after 8s waiting for rollouts to complete: $d: 0 of 1 updated replicas are available; $a: ")
            .contains("Deployment/d pod d-1 on node db0 (phase=Running containers=[main=waiting(ContainerCreating)(restarts=2)])")
            .contains("DaemonSet/a pod a-1 on node unscheduled (phase=Running")
            .endsWith("(and 4 more pods)")
    }

    @Test
    fun `a timeout with no pod to name says only what each workload waits on`() {
        val look = RolloutsLook.Pending(reasons = listOf("$d not found"), workloads = listOf(d))

        assertThat(RolloutWaitReport.timedOut(8, look).toDisplayString())
            .isEqualTo("Timed out after 8s waiting for rollouts to complete: $d not found")
    }

    /**
     * One look of a wait: its time after the start, the workloads still pending, and whether a
     * progress event is due.
     */
    data class Step(
        val atSeconds: Long,
        val pending: List<String>,
        val emits: Boolean,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("progressCases")
    fun `progress is reported when the pending set changes, or once an interval passes without a report`(
        name: String,
        steps: List<Step>,
    ) {
        val progress = RolloutWaitReport.Progress(start, listOf("x", "y"), Duration.ofSeconds(INTERVAL))

        val emitted = steps.map { progress.next(start.plusSeconds(it.atSeconds), it.pending) }

        assertThat(emitted.map { it != null }).describedAs(name).isEqualTo(steps.map { it.emits })
        steps.zip(emitted).filter { it.second != null }.forEach { (step, event) ->
            assertThat(event?.elapsedSeconds).isEqualTo(step.atSeconds)
            assertThat(event?.pending).isEqualTo(step.pending)
        }
    }

    companion object {
        private const val INTERVAL = 15L
        private val xy = listOf("x", "y")
        private val y = listOf("y")

        @JvmStatic
        fun progressCases(): Stream<Arguments> =
            Stream.of(
                Arguments.of("nothing changed, inside the interval", listOf(Step(1, xy, false), Step(14, xy, false))),
                Arguments.of("nothing changed, at the interval", listOf(Step(14, xy, false), Step(15, xy, true), Step(29, xy, false))),
                Arguments.of("a workload finished", listOf(Step(1, xy, false), Step(2, y, true), Step(3, y, false))),
                Arguments.of("a change restarts the interval", listOf(Step(10, y, true), Step(24, y, false), Step(25, y, true))),
                Arguments.of("the same set in another order is no change", listOf(Step(1, listOf("y", "x"), false))),
                Arguments.of("every workload done is a change", listOf(Step(3, emptyList(), true))),
            )
    }
}
