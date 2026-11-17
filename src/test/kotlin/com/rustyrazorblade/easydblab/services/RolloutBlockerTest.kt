package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.Node
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.util.stream.Stream

/**
 * [RolloutBlocker] decides when a pending rollout can no longer finish, so the rollout wait ends at
 * once instead of at its deadline. The invariants: every terminal container reason of
 * [K8sPodUtils] blocks any workload at once; a DaemonSet pod that is not Ready blocks once its node's
 * Ready condition has been False or Unknown for longer than
 * [Constants.K8s.ROLLOUT_NODE_NOT_READY_GRACE_SECONDS]; a state a slow rollout passes through never blocks; nodes
 * are read only for unready pods, once each and within a bound; and the reason names the workload,
 * the pod, the node and why.
 */
class RolloutBlockerTest {
    private val daemonSet = WorkloadRef(WorkloadKind.DaemonSet, "otel-collector")
    private val deployment = WorkloadRef(WorkloadKind.Deployment, "tempo")

    /** A lastTransitionTime [seconds] before [NOW]. */
    private fun ago(seconds: Long): String = NOW.minusSeconds(seconds).toString()

    private fun pod(
        name: String = "otel-collector-abcde",
        node: String? = "db0",
        waitingReason: String? = null,
        terminating: Boolean = false,
        ready: Boolean = false,
        restarts: Int = 0,
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
            .withPhase(if (ready) "Running" else "Pending")
            .apply { if (ready) addNewCondition().withType("Ready").withStatus("True").endCondition() }
            .addNewContainerStatus()
            .withName("main")
            .withRestartCount(restarts)
            .withNewState()
            .apply { if (waitingReason != null) withNewWaiting().withReason(waitingReason).endWaiting() }
            .endState()
            .endContainerStatus()
            .endStatus()
            .build()

    /** A node whose Ready condition is [ready] (none when null), last changed at [transitioned]. */
    private fun node(
        name: String,
        ready: String?,
        transitioned: String? = ago(GRACE + 1),
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
                        .withLastTransitionTime(transitioned)
                        .endCondition()
                }
            }.endStatus()
            .build()

    private val readyNodes = listOf(node("db0", "True"))

    /** Reads a node by name from [nodes], recording each name it was asked for in [reads]. */
    private fun reader(
        nodes: List<Node>,
        reads: MutableList<String> = mutableListOf(),
    ): (String) -> Node? =
        { name ->
            reads += name
            nodes.firstOrNull { it.metadata.name == name }
        }

    @ParameterizedTest(name = "{0} blocks a {1}")
    @MethodSource("terminalReasonsByKind")
    fun `a container in a terminal state blocks the rollout and is named`(
        reason: String,
        kind: WorkloadKind,
    ) {
        val workload = WorkloadRef(kind, "agent")

        val pods = listOf(pod(waitingReason = reason, restarts = Constants.K8s.ROLLOUT_CRASH_LOOP_MIN_RESTARTS))
        val blocker = RolloutBlocker.find(NOW, workload, pods, reader(readyNodes))

        assertThat(blocker)
            .contains("$kind/agent")
            .contains("otel-collector-abcde")
            .contains("db0")
            .contains(reason)
    }

    @ParameterizedTest(name = "Ready={0}")
    @MethodSource("notReadyConditions")
    fun `a DaemonSet pod on a node not Ready past the grace period blocks the rollout and is named`(ready: String) {
        val nodes = listOf(node("db0", "True"), node("db7", ready, transitioned = ago(GRACE + 1)))

        val blocker = RolloutBlocker.find(NOW, daemonSet, listOf(pod(), pod(name = "otel-collector-zzz", node = "db7")), reader(nodes))

        assertThat(blocker)
            .contains("DaemonSet/otel-collector")
            .contains("otel-collector-zzz")
            .contains("db7")
            .contains("not Ready")
            .contains("${GRACE + 1}s")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("withinGrace")
    fun `a node that is not Ready, but not for longer than the grace period, does not block`(
        case: String,
        ready: String?,
        transitioned: String?,
    ) {
        val nodes = listOf(node("db7", ready, transitioned))

        assertThat(RolloutBlocker.find(NOW, daemonSet, listOf(pod(node = "db7")), reader(nodes))).describedAs(case).isNull()
    }

    @ParameterizedTest(name = "{0} after {1} restarts blocks: {2}")
    @MethodSource("restartThreshold")
    fun `a crash loop blocks only after the restart threshold, any other terminal reason at once`(
        reason: String,
        restarts: Int,
        blocks: Boolean,
    ) {
        for (workload in listOf(daemonSet, deployment)) {
            val blocker = RolloutBlocker.find(NOW, workload, listOf(pod(waitingReason = reason, restarts = restarts)), reader(readyNodes))

            when {
                blocks -> assertThat(blocker).describedAs("$workload").contains(reason)
                else -> assertThat(blocker).describedAs("$workload").isNull()
            }
        }
    }

    @Test
    fun `a crash loop at the threshold names its restart count`() {
        val pods = listOf(pod(waitingReason = "CrashLoopBackOff", restarts = 3))

        assertThat(RolloutBlocker.find(NOW, daemonSet, pods, reader(readyNodes))).contains("after 3 restarts")
    }

    @Test
    fun `an early crash loop in one container does not hide an image pull failure in another`() {
        val pod =
            PodBuilder(pod(waitingReason = "CrashLoopBackOff", restarts = 1))
                .editStatus()
                .addNewContainerStatus()
                .withName("sidecar")
                .withRestartCount(0)
                .withNewState()
                .withNewWaiting()
                .withReason("ImagePullBackOff")
                .endWaiting()
                .endState()
                .endContainerStatus()
                .endStatus()
                .build()

        assertThat(RolloutBlocker.find(NOW, daemonSet, listOf(pod), reader(readyNodes)))
            .contains("sidecar")
            .contains("ImagePullBackOff")
    }

    @Test
    fun `a crash loop past the threshold blocks at once, even on a node that just went NotReady`() {
        val nodes = listOf(node("db7", "False", transitioned = ago(1)))
        val pods = listOf(pod(node = "db7", waitingReason = "CrashLoopBackOff", restarts = 3))

        assertThat(RolloutBlocker.find(NOW, daemonSet, pods, reader(nodes))).contains("CrashLoopBackOff")
    }

    @ParameterizedTest(name = "waiting reason {0}")
    @MethodSource("slowStates")
    fun `a state a slow rollout passes through does not block`(waitingReason: String?) {
        val pods = listOf(pod(waitingReason = waitingReason), pod(name = "unscheduled", node = null))

        assertThat(RolloutBlocker.find(NOW, daemonSet, pods, reader(readyNodes))).isNull()
        assertThat(RolloutBlocker.find(NOW, deployment, pods, reader(readyNodes))).isNull()
    }

    @Test
    fun `a Deployment pod on a node that is not Ready does not block, since it can be rescheduled`() {
        val blocker = RolloutBlocker.find(NOW, deployment, listOf(pod(node = "db7")), reader(listOf(node("db7", "False"))))

        assertThat(blocker).isNull()
    }

    @Test
    fun `a terminating pod does not block, since it is on its way out`() {
        val pods = listOf(pod(waitingReason = "CrashLoopBackOff", node = "db7", terminating = true))

        assertThat(RolloutBlocker.find(NOW, daemonSet, pods, reader(listOf(node("db7", "False"))))).isNull()
    }

    @Test
    fun `a pod on a node the cluster no longer lists does not block`() {
        assertThat(RolloutBlocker.find(NOW, daemonSet, listOf(pod(node = "gone")), reader(readyNodes))).isNull()
    }

    @Test
    fun `a Ready DaemonSet pod on a node that is not Ready does not block, since it already counts`() {
        val pods = listOf(pod(node = "db7", ready = true))

        assertThat(RolloutBlocker.find(NOW, daemonSet, pods, reader(listOf(node("db7", "Unknown"))))).isNull()
    }

    @Test
    fun `a fully Ready pod set reads no node`() {
        val reads = mutableListOf<String>()
        val pods = (0 until 50).map { pod(name = "p$it", node = "db$it", ready = true) }

        RolloutBlocker.find(NOW, daemonSet, pods, reader(listOf(node("db0", "False")), reads))

        assertThat(reads).isEmpty()
    }

    @Test
    fun `unready pods read each of their nodes once, up to the per-look bound`() {
        val reads = mutableListOf<String>()
        val nodes = (0 until 50).map { node("db$it", "True") }
        val pods = (0 until 100).map { pod(name = "p$it", node = "db${it % 50}") }

        assertThat(RolloutBlocker.find(NOW, daemonSet, pods, reader(nodes, reads))).isNull()

        assertThat(reads).doesNotHaveDuplicates().hasSize(Constants.K8s.ROLLOUT_NODE_CHECKS_PER_LOOK)
    }

    companion object {
        @JvmStatic
        fun terminalReasonsByKind(): Stream<Arguments> =
            K8sPodUtils.TERMINAL_FAILURE_REASONS
                .flatMap { reason -> WorkloadKind.entries.map { kind -> Arguments.of(reason, kind) } }
                .stream()

        @JvmStatic
        fun restartThreshold(): Stream<Arguments> =
            Stream.of(
                Arguments.of("CrashLoopBackOff", 1, false),
                Arguments.of("CrashLoopBackOff", 2, false),
                Arguments.of("CrashLoopBackOff", 3, true),
                Arguments.of("CrashLoopBackOff", 10, true),
                Arguments.of("ImagePullBackOff", 0, true),
                Arguments.of("ErrImagePull", 0, true),
                Arguments.of("Error", 0, true),
            )

        @JvmStatic
        fun notReadyConditions(): Stream<Arguments> = Stream.of(Arguments.of("False"), Arguments.of("Unknown"))

        private val NOW = Instant.parse("2026-11-17T12:00:00Z")
        private val GRACE = Constants.K8s.ROLLOUT_NODE_NOT_READY_GRACE_SECONDS

        @JvmStatic
        fun withinGrace(): Stream<Arguments> =
            Stream.of(
                Arguments.of("False exactly at the grace period", "False", NOW.minusSeconds(GRACE).toString()),
                Arguments.of("False under the grace period", "False", NOW.minusSeconds(GRACE - 1).toString()),
                Arguments.of("Unknown under the grace period", "Unknown", NOW.minusSeconds(1).toString()),
                Arguments.of("transition time in the future", "False", NOW.plusSeconds(GRACE).toString()),
                Arguments.of("no lastTransitionTime counts as just transitioned", "False", null),
                Arguments.of("an unparseable lastTransitionTime counts as just transitioned", "Unknown", "yesterday"),
                Arguments.of("no Ready condition counts as just transitioned", null, null),
                Arguments.of("Ready=True long ago", "True", NOW.minusSeconds(GRACE * 60).toString()),
            )

        @JvmStatic
        fun slowStates(): Stream<Arguments> =
            Stream.of(
                Arguments.of("ContainerCreating"),
                Arguments.of("PodInitializing"),
                Arguments.of(null),
            )
    }
}
