package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.Node
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * [RolloutBlocker] decides when a pending rollout can no longer finish, so the rollout wait ends at
 * once instead of at its deadline. The invariants: every terminal container reason of
 * [K8sPodUtils] blocks any workload; a DaemonSet pod on a node whose Ready condition is not True
 * blocks; a state a slow rollout passes through never blocks; and the reason names the workload,
 * the pod, the node and why.
 */
class RolloutBlockerTest {
    private val daemonSet = WorkloadRef(WorkloadKind.DaemonSet, "otel-collector")
    private val deployment = WorkloadRef(WorkloadKind.Deployment, "tempo")

    private fun pod(
        name: String = "otel-collector-abcde",
        node: String? = "db0",
        waitingReason: String? = null,
        terminating: Boolean = false,
    ): Pod =
        PodBuilder()
            .withNewMetadata()
            .withName(name)
            .apply { if (terminating) withDeletionTimestamp("2026-11-17T00:00:00Z") }
            .endMetadata()
            .withNewSpec()
            .withNodeName(node)
            .endSpec()
            .withNewStatus()
            .withPhase("Pending")
            .addNewContainerStatus()
            .withName("main")
            .withNewState()
            .apply { if (waitingReason != null) withNewWaiting().withReason(waitingReason).endWaiting() }
            .endState()
            .endContainerStatus()
            .endStatus()
            .build()

    private fun node(
        name: String,
        ready: String?,
    ): Node =
        NodeBuilder()
            .withNewMetadata()
            .withName(name)
            .endMetadata()
            .withNewStatus()
            .apply {
                if (ready != null) {
                    addNewCondition()
                        .withType("Ready")
                        .withStatus(ready)
                        .withReason("KubeletNotReady")
                        .endCondition()
                }
            }.endStatus()
            .build()

    private val readyNodes = listOf(node("db0", "True"))

    @ParameterizedTest(name = "{0} blocks a {1}")
    @MethodSource("terminalReasonsByKind")
    fun `a container in a terminal state blocks the rollout and is named`(
        reason: String,
        kind: WorkloadKind,
    ) {
        val workload = WorkloadRef(kind, "agent")

        val blocker = RolloutBlocker.find(workload, listOf(pod(waitingReason = reason)), readyNodes)

        assertThat(blocker)
            .contains("$kind/agent")
            .contains("otel-collector-abcde")
            .contains("db0")
            .contains(reason)
    }

    @ParameterizedTest(name = "Ready={0}")
    @MethodSource("notReadyConditions")
    fun `a DaemonSet pod on a node that is not Ready blocks the rollout and is named`(ready: String?) {
        val nodes = listOf(node("db0", "True"), node("db7", ready))

        val blocker = RolloutBlocker.find(daemonSet, listOf(pod(), pod(name = "otel-collector-zzz", node = "db7")), nodes)

        assertThat(blocker)
            .contains("DaemonSet/otel-collector")
            .contains("otel-collector-zzz")
            .contains("db7")
            .contains("not Ready")
    }

    @ParameterizedTest(name = "waiting reason {0}")
    @MethodSource("slowStates")
    fun `a state a slow rollout passes through does not block`(waitingReason: String?) {
        val pods = listOf(pod(waitingReason = waitingReason), pod(name = "unscheduled", node = null))

        assertThat(RolloutBlocker.find(daemonSet, pods, readyNodes)).isNull()
        assertThat(RolloutBlocker.find(deployment, pods, readyNodes)).isNull()
    }

    @Test
    fun `a Deployment pod on a node that is not Ready does not block, since it can be rescheduled`() {
        val blocker = RolloutBlocker.find(deployment, listOf(pod(node = "db7")), listOf(node("db7", "False")))

        assertThat(blocker).isNull()
    }

    @Test
    fun `a terminating pod does not block, since it is on its way out`() {
        val pods = listOf(pod(waitingReason = "CrashLoopBackOff", node = "db7", terminating = true))

        assertThat(RolloutBlocker.find(daemonSet, pods, listOf(node("db7", "False")))).isNull()
    }

    @Test
    fun `a pod on a node the cluster no longer lists does not block`() {
        assertThat(RolloutBlocker.find(daemonSet, listOf(pod(node = "gone")), readyNodes)).isNull()
    }

    companion object {
        @JvmStatic
        fun terminalReasonsByKind(): Stream<Arguments> =
            K8sPodUtils.TERMINAL_FAILURE_REASONS
                .flatMap { reason -> WorkloadKind.entries.map { kind -> Arguments.of(reason, kind) } }
                .stream()

        @JvmStatic
        fun notReadyConditions(): Stream<Arguments> = Stream.of(Arguments.of("False"), Arguments.of("Unknown"), Arguments.of(null))

        @JvmStatic
        fun slowStates(): Stream<Arguments> =
            Stream.of(
                Arguments.of("ContainerCreating"),
                Arguments.of("PodInitializing"),
                Arguments.of(null),
            )
    }
}
