package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.Node
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [RolloutLooker] decides what one look reads and how it combines the workloads. Its reads go through
 * an in-memory [RolloutReader] that counts them, so the bounds are asserted directly: one pod list per
 * look however many workloads are pending, none when nothing is pending, and each node at most once.
 */
class RolloutLookerTest {
    /** An in-memory namespace that counts every read. */
    private class FakeReader(
        val deployments: Map<String, Deployment> = emptyMap(),
        val daemonSets: Map<String, DaemonSet> = emptyMap(),
        val pods: List<Pod> = emptyList(),
        val replicaSets: List<ReplicaSet> = emptyList(),
        val nodes: Map<String, Node> = emptyMap(),
    ) : RolloutReader {
        var podLists = 0
        var replicaSetLists = 0
        val nodeReads = mutableListOf<String>()

        override fun deployment(name: String): Deployment? = deployments[name]

        override fun daemonSet(name: String): DaemonSet? = daemonSets[name]

        override fun pods(): List<Pod> = pods.also { podLists++ }

        override fun replicaSets(): List<ReplicaSet> = replicaSets.also { replicaSetLists++ }

        override fun node(name: String): Node? = nodes[name].also { nodeReads += name }
    }

    private fun daemonSet(
        name: String,
        desired: Int,
        available: Int,
    ): DaemonSet =
        DaemonSetBuilder()
            .withNewMetadata()
            .withName(name)
            .withGeneration(1)
            .endMetadata()
            .withNewSpec()
            .withNewSelector()
            .addToMatchLabels("app", name)
            .endSelector()
            .endSpec()
            .withNewStatus()
            .withObservedGeneration(1)
            .withDesiredNumberScheduled(desired)
            .withUpdatedNumberScheduled(desired)
            .withNumberAvailable(available)
            .endStatus()
            .build()

    /** A Deployment at revision [revision] whose one replica is not available yet. */
    private fun deployment(
        name: String,
        revision: String = "2",
    ): Deployment =
        DeploymentBuilder()
            .withNewMetadata()
            .withName(name)
            .withUid("$name-uid")
            .withGeneration(1)
            .addToAnnotations("deployment.kubernetes.io/revision", revision)
            .endMetadata()
            .withNewSpec()
            .withReplicas(1)
            .withNewSelector()
            .addToMatchLabels("app", name)
            .endSelector()
            .endSpec()
            .withNewStatus()
            .withObservedGeneration(1)
            .withReplicas(1)
            .withUpdatedReplicas(1)
            .withAvailableReplicas(0)
            .endStatus()
            .build()

    /** A ReplicaSet of Deployment [deployment] at [revision], with the uid `<deployment>-rs<revision>`. */
    private fun replicaSet(
        deployment: String,
        revision: String,
    ): ReplicaSet =
        ReplicaSetBuilder()
            .withNewMetadata()
            .withName("$deployment-rs$revision")
            .withUid("$deployment-rs$revision")
            .addToLabels("app", deployment)
            .addToAnnotations("deployment.kubernetes.io/revision", revision)
            .addNewOwnerReference()
            .withKind("Deployment")
            .withName(deployment)
            .withUid("$deployment-uid")
            .endOwnerReference()
            .endMetadata()
            .build()

    /**
     * A pod labelled for [app] on [node], optionally crash-looping. It carries DaemonSet generation 1,
     * and when [replicaSet] is set it is owned by that ReplicaSet's uid.
     */
    private fun pod(
        app: String,
        node: String,
        crashing: Boolean = false,
        replicaSet: String? = null,
    ): Pod =
        PodBuilder()
            .withNewMetadata()
            .withName("$app-$node-${replicaSet ?: "ds"}")
            .addToLabels("app", app)
            .addToLabels("pod-template-generation", "1")
            .apply {
                if (replicaSet != null) {
                    addNewOwnerReference()
                        .withKind("ReplicaSet")
                        .withName(replicaSet)
                        .withUid(replicaSet)
                        .endOwnerReference()
                }
            }.endMetadata()
            .withNewSpec()
            .withNodeName(node)
            .endSpec()
            .withNewStatus()
            .addNewContainerStatus()
            .withName("main")
            .withRestartCount(if (crashing) Constants.K8s.ROLLOUT_CRASH_LOOP_MIN_RESTARTS else 0)
            .withNewState()
            .apply { if (crashing) withNewWaiting().withReason("CrashLoopBackOff").endWaiting() }
            .endState()
            .endContainerStatus()
            .endStatus()
            .build()

    private val a = WorkloadRef(WorkloadKind.DaemonSet, "a")
    private val b = WorkloadRef(WorkloadKind.DaemonSet, "b")
    private val c = WorkloadRef(WorkloadKind.DaemonSet, "c")

    @Test
    fun `collects every pending workload's reason, in order`() {
        val reader =
            FakeReader(
                daemonSets = mapOf("a" to daemonSet("a", 2, 1), "b" to daemonSet("b", 2, 2), "c" to daemonSet("c", 3, 0)),
            )

        val look = RolloutLooker(reader).look(listOf(a, b, c, WorkloadRef(WorkloadKind.Deployment, "gone")))

        assertThat(look).isInstanceOf(RolloutsLook.Pending::class.java)
        assertThat((look as RolloutsLook.Pending).reasons)
            .containsExactly(
                "DaemonSet/a: 1 of 2 updated pods are available",
                "DaemonSet/c: 0 of 3 updated pods are available",
                "Deployment/gone not found",
            )
    }

    @Test
    fun `the first blocked workload ends the look, and later workloads are not read`() {
        val reader =
            FakeReader(
                daemonSets = mapOf("a" to daemonSet("a", 1, 0), "b" to daemonSet("b", 1, 0)),
                pods = listOf(pod("a", "db0", crashing = true), pod("b", "db0", crashing = true)),
            )
        var asked = 0
        val counting =
            object : RolloutReader by reader {
                override fun daemonSet(name: String): DaemonSet? = reader.daemonSet(name).also { asked++ }
            }

        val look = RolloutLooker(counting).look(listOf(a, b))

        assertThat(look).isInstanceOf(RolloutsLook.Blocked::class.java)
        assertThat((look as RolloutsLook.Blocked).reason).startsWith("DaemonSet/a:").contains("CrashLoopBackOff")
        assertThat(asked).isEqualTo(1)
    }

    @Test
    fun `a crash loop in a pending Deployment's current ReplicaSet blocks the rollout`() {
        val reader =
            FakeReader(
                deployments = mapOf("d" to deployment("d", revision = "2")),
                replicaSets = listOf(replicaSet("d", "1"), replicaSet("d", "2")),
                pods = listOf(pod("d", "app0", replicaSet = "d-rs1"), pod("d", "app0", crashing = true, replicaSet = "d-rs2")),
            )

        val look = RolloutLooker(reader).look(listOf(WorkloadRef(WorkloadKind.Deployment, "d")))

        assertThat(look).isInstanceOf(RolloutsLook.Blocked::class.java)
        assertThat((look as RolloutsLook.Blocked).reason)
            .startsWith("Deployment/d:")
            .contains("d-app0-d-rs2")
            .contains("CrashLoopBackOff")
    }

    @Test
    fun `a crash loop only in an old ReplicaSet leaves the Deployment pending`() {
        val reader =
            FakeReader(
                deployments = mapOf("d" to deployment("d", revision = "2")),
                replicaSets = listOf(replicaSet("d", "1"), replicaSet("d", "2")),
                pods = listOf(pod("d", "app0", crashing = true, replicaSet = "d-rs1"), pod("d", "app0", replicaSet = "d-rs2")),
            )

        val look = RolloutLooker(reader).look(listOf(WorkloadRef(WorkloadKind.Deployment, "d")))

        assertThat(look).isEqualTo(RolloutsLook.Pending(listOf("Deployment/d: 0 of 1 updated replicas are available")))
    }

    @Test
    fun `one pod list per look across several pending workloads`() {
        val reader =
            FakeReader(
                daemonSets = mapOf("a" to daemonSet("a", 2, 0), "b" to daemonSet("b", 2, 0)),
                deployments = mapOf("d" to deployment("d")),
                pods = listOf(pod("a", "db0"), pod("a", "db1"), pod("b", "db0"), pod("b", "db1")),
                nodes = mapOf("db0" to readyNode("db0"), "db1" to readyNode("db1")),
            )

        RolloutLooker(reader).look(listOf(a, b, WorkloadRef(WorkloadKind.Deployment, "d")))

        assertThat(reader.podLists).isEqualTo(1)
        assertThat(reader.replicaSetLists).isEqualTo(1)
        // Two DaemonSets ask about the same two nodes; each is read once.
        assertThat(reader.nodeReads).containsExactlyInAnyOrder("db0", "db1")
    }

    @Test
    fun `nothing pending reads no pods, ReplicaSets or nodes`() {
        val reader = FakeReader(daemonSets = mapOf("a" to daemonSet("a", 2, 2)), pods = listOf(pod("a", "db0")))

        val look = RolloutLooker(reader).look(listOf(a))

        assertThat(look.isFinal()).isTrue()
        assertThat(reader.podLists).isZero()
        assertThat(reader.replicaSetLists).isZero()
        assertThat(reader.nodeReads).isEmpty()
    }

    @Test
    fun `a node that is gone is read once per look`() {
        val reader =
            FakeReader(
                daemonSets = mapOf("a" to daemonSet("a", 1, 0), "b" to daemonSet("b", 1, 0)),
                pods = listOf(pod("a", "gone"), pod("b", "gone")),
            )

        RolloutLooker(reader).look(listOf(a, b))

        assertThat(reader.nodeReads).containsExactly("gone")
    }

    @Test
    fun `each look reads afresh`() {
        val reader = FakeReader(daemonSets = mapOf("a" to daemonSet("a", 1, 0)), pods = listOf(pod("a", "db0")))
        val looker = RolloutLooker(reader)

        looker.look(listOf(a))
        looker.look(listOf(a))

        assertThat(reader.podLists).isEqualTo(2)
        assertThat(reader.nodeReads).containsExactly("db0", "db0")
    }

    private fun readyNode(name: String): Node =
        NodeBuilder()
            .withNewMetadata()
            .withName(name)
            .endMetadata()
            .withNewStatus()
            .addNewCondition()
            .withType("Ready")
            .withStatus("True")
            .endCondition()
            .endStatus()
            .build()
}
