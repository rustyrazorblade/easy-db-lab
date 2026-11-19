package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.Node
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.apps.ControllerRevision
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * [RolloutLooker] decides what one look reads and how it combines the workloads. Its reads go through
 * an in-memory [RolloutReader] that counts them, so the bounds are asserted directly: one pod list per
 * look however many workloads are pending, none when nothing is pending, and each node at most once.
 */
class RolloutLookerTest {
    private companion object {
        val NOW: Instant = Instant.parse("2026-11-17T12:00:00Z")
        const val SEED = 1198L
        const val PROPERTY_TRIALS = 500
    }

    /** An in-memory namespace that counts every read. */
    private class FakeReader(
        val deployments: Map<String, Deployment> = emptyMap(),
        val daemonSets: Map<String, DaemonSet> = emptyMap(),
        var pods: List<Pod> = emptyList(),
        val replicaSets: List<ReplicaSet> = emptyList(),
        val nodes: Map<String, Node> = emptyMap(),
        val controllerRevisions: List<ControllerRevision> = emptyList(),
    ) : RolloutReader {
        var podLists = 0
        var replicaSetLists = 0
        var controllerRevisionLists = 0
        val nodeReads = mutableListOf<String>()

        override fun deployment(name: String): Deployment? = deployments[name]

        override fun daemonSet(name: String): DaemonSet? = daemonSets[name]

        override fun pods(): List<Pod> = pods.also { podLists++ }

        override fun replicaSets(): List<ReplicaSet> = replicaSets.also { replicaSetLists++ }

        override fun node(name: String): Node? = nodes[name].also { nodeReads += name }

        override fun controllerRevisions(): List<ControllerRevision> = controllerRevisions.also { controllerRevisionLists++ }
    }

    private fun daemonSet(
        name: String,
        desired: Int,
        available: Int,
        generation: Long = 1,
    ): DaemonSet =
        DaemonSetBuilder()
            .withNewMetadata()
            .withName(name)
            .withUid("$name-uid")
            .withGeneration(generation)
            .endMetadata()
            .withNewSpec()
            .withNewSelector()
            .addToMatchLabels("app", name)
            .endSelector()
            .endSpec()
            .withNewStatus()
            .withObservedGeneration(generation)
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
        generation: Long = 1,
        terminating: Boolean = false,
        ownerUid: String? = null,
    ): Pod =
        PodBuilder()
            .withNewMetadata()
            .withName("$app-$node-${replicaSet ?: "ds"}-g$generation")
            .addToLabels("app", app)
            .addToLabels("pod-template-generation", generation.toString())
            .apply { if (terminating) withDeletionTimestamp("2026-11-17T11:00:00Z") }
            .apply {
                when (replicaSet) {
                    null ->
                        addNewOwnerReference()
                            .withKind("DaemonSet")
                            .withName(app)
                            .withUid(ownerUid ?: "$app-uid")
                            .endOwnerReference()
                    else ->
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
        assertThat((look as RolloutsLook.Blocked).block.describe()).startsWith("DaemonSet/a:").contains("CrashLoopBackOff")
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
        assertThat((look as RolloutsLook.Blocked).block.describe())
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

        assertThat((look as RolloutsLook.Pending).reasons).containsExactly("Deployment/d: 0 of 1 updated replicas are available")
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
        assertThat(reader.controllerRevisionLists).isEqualTo(1)
        // Two DaemonSets ask about the same two nodes; each is read once.
        assertThat(reader.nodeReads).containsExactlyInAnyOrder("db0", "db1")
    }

    @Test
    fun `a complete Deployment beside a pending DaemonSet lists no ReplicaSets`() {
        val complete =
            DeploymentBuilder(deployment("d"))
                .editStatus()
                .withAvailableReplicas(1)
                .endStatus()
                .build()
        val reader =
            FakeReader(
                deployments = mapOf("d" to complete),
                daemonSets = mapOf("a" to daemonSet("a", 1, 0)),
                pods = listOf(pod("a", "db0")),
                nodes = mapOf("db0" to readyNode("db0")),
            )

        RolloutLooker(reader).look(listOf(WorkloadRef(WorkloadKind.Deployment, "d"), a))

        assertThat(reader.replicaSetLists).isZero()
        assertThat(reader.podLists).isEqualTo(1)
    }

    @Test
    fun `a crash-looping pod another DaemonSet owns does not block, though the selector matches it`() {
        val reader =
            FakeReader(
                daemonSets = mapOf("a" to daemonSet("a", 1, 0)),
                pods = listOf(pod("a", "db0"), pod("a", "db1", crashing = true, ownerUid = "someone-else")),
                nodes = mapOf("db0" to readyNode("db0")),
            )

        assertThat(RolloutLooker(reader).look(listOf(a))).isInstanceOf(RolloutsLook.Pending::class.java)
    }

    @Test
    fun `a pending look lists each workload's unready current and stranded pods, capped per workload`() {
        val cap = Constants.K8s.ROLLOUT_UNREADY_PODS_LISTED_PER_WORKLOAD
        val names = (0 until cap + 3).map { "db%02d".format(it) }
        val reader =
            FakeReader(
                daemonSets = mapOf("a" to daemonSet("a", names.size, 0, generation = 2), "b" to daemonSet("b", 1, 0)),
                pods = names.map { pod("a", it, generation = 2) } + pod("b", "db00") + pod("a", "dbzz", generation = 1, terminating = true),
                nodes = (names + "dbzz").associateWith(::readyNode),
            )

        val look = RolloutLooker(reader).look(listOf(a, b)) as RolloutsLook.Pending

        assertThat(look.unready.filter { it.workload == a }).hasSize(cap)
        assertThat(look.unready.filter { it.workload == b }.map { it.pod.metadata.name }).containsExactly("b-db00-ds-g1")
        assertThat(look.unreadyOmitted).isEqualTo(names.size + 1 - cap)
        assertThat(look.workloads).containsExactly(a, b)
    }

    @Test
    fun `each DaemonSet's nodes are read within ceil(its nodes over bound) looks, beside another`() {
        val bound = Constants.K8s.ROLLOUT_NODE_CHECKS_PER_LOOK
        val aNodes = (0 until 45).map { "n%02d".format(it) }
        val bNodes = (0 until 30).map { "m%02d".format(it) }
        val reader =
            FakeReader(
                daemonSets = mapOf("a" to daemonSet("a", aNodes.size, 0), "b" to daemonSet("b", bNodes.size, 0)),
                pods = aNodes.map { pod("a", it) } + bNodes.map { pod("b", it) },
                nodes = (aNodes + bNodes).associateWith(::readyNode),
            )
        val looker = RolloutLooker(reader)

        repeat((bNodes.size + bound - 1) / bound) { looker.look(listOf(a, b)) }
        assertThat(reader.nodeReads.toSet()).containsAll(bNodes)
        repeat((aNodes.size + bound - 1) / bound - (bNodes.size + bound - 1) / bound) { looker.look(listOf(a, b)) }
        assertThat(reader.nodeReads.toSet()).containsAll(aNodes)
    }

    @Test
    fun `nothing pending reads no pods, ReplicaSets or nodes`() {
        val reader = FakeReader(daemonSets = mapOf("a" to daemonSet("a", 2, 2)), pods = listOf(pod("a", "db0")))

        val look = RolloutLooker(reader).look(listOf(a))

        assertThat(look.isFinal()).isTrue()
        assertThat(reader.podLists).isZero()
        assertThat(reader.replicaSetLists).isZero()
        assertThat(reader.controllerRevisionLists).isZero()
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

    @ParameterizedTest(name = "node {0} for {1}s blocks: {2}")
    @CsvSource(
        "False, 61, true",
        "Unknown, 61, true",
        "False, 30, false",
        "True, 600, false",
    )
    fun `a node holding only a terminating old pod of a pending DaemonSet blocks once NotReady past the grace`(
        ready: String,
        seconds: Long,
        blocks: Boolean,
    ) {
        // A template update deleted the generation-1 pod on db7; with db7 unreachable it never goes,
        // and generation 2 is never created there. db0 has rolled.
        val reader =
            FakeReader(
                daemonSets = mapOf("a" to daemonSet("a", desired = 2, available = 1, generation = 2)),
                pods = listOf(pod("a", "db0", generation = 2), pod("a", "db7", generation = 1, terminating = true)),
                nodes = mapOf("db0" to readyNode("db0"), "db7" to node("db7", ready, NOW.minusSeconds(seconds))),
            )

        val look = RolloutLooker(reader, Clock.fixed(NOW, ZoneOffset.UTC)).look(listOf(a))

        when {
            blocks -> {
                assertThat(look).isInstanceOf(RolloutsLook.Blocked::class.java)
                assertThat((look as RolloutsLook.Blocked).block.describe())
                    .startsWith("DaemonSet/a:")
                    .contains("node db7")
                    .contains("not Ready")
                    .contains("a-db7-ds-g1")
            }
            else -> assertThat(look).isInstanceOf(RolloutsLook.Pending::class.java)
        }
    }

    @Test
    fun `a dead node past the per-look bound is read within ceil(candidates over bound) looks`() {
        // A new DaemonSet's 25 pods share one creation second; db23 is dead.
        val names = (0 until 25).map { "db%02d".format(it) }
        val nodes = names.associateWith { if (it == "db23") node(it, "False", NOW.minusSeconds(61)) else readyNode(it) }
        val reader =
            FakeReader(
                daemonSets = mapOf("a" to daemonSet("a", desired = 25, available = 0)),
                pods = names.map { pod("a", it) },
                nodes = nodes,
            )
        val looker = RolloutLooker(reader, Clock.fixed(NOW, ZoneOffset.UTC))
        val bound = Constants.K8s.ROLLOUT_NODE_CHECKS_PER_LOOK
        val looksNeeded = (names.size + bound - 1) / bound

        val looks = (1..looksNeeded).map { looker.look(listOf(a)) }

        assertThat(looks.first()).isInstanceOf(RolloutsLook.Pending::class.java)
        assertThat(looks.last()).isInstanceOf(RolloutsLook.Blocked::class.java)
        assertThat((looks.last() as RolloutsLook.Blocked).block.describe()).contains("node db23")
        assertThat(reader.nodeReads.toSet()).containsAll(names.filter { it <= "db23" })
    }

    @Test
    fun `each look reads at most the bound of nodes, and every node across enough looks`() {
        val names = (0 until 45).map { "db%02d".format(it) }
        val reader =
            FakeReader(
                daemonSets = mapOf("a" to daemonSet("a", desired = 45, available = 0)),
                pods = names.map { pod("a", it) },
                nodes = names.associateWith(::readyNode),
            )
        val looker = RolloutLooker(reader, Clock.fixed(NOW, ZoneOffset.UTC))
        val bound = Constants.K8s.ROLLOUT_NODE_CHECKS_PER_LOOK

        val perLook =
            (1..3).map {
                val before = reader.nodeReads.size
                looker.look(listOf(a))
                reader.nodeReads.size - before
            }

        assertThat(perLook).containsOnly(bound)
        assertThat(reader.nodeReads.toSet()).containsExactlyInAnyOrderElementsOf(names)
    }

    @Test
    fun `a dead node is read within the bound while pods become Ready between looks`() {
        // 41 candidates, so ceil(41 / 20) = 3 looks. n00 becomes Ready after the first look and n01 to
        // n10 after the second; a window placed by look number alone never reaches n20.
        val names = (0 until 41).map { "n%02d".format(it) }
        val reader = shrinkingReader(names, dead = "n20")
        val looker = RolloutLooker(reader, Clock.fixed(NOW, ZoneOffset.UTC))

        val readyBeforeLook = listOf(emptySet(), setOf("n00"), (0..10).map { "n%02d".format(it) }.toSet())

        val blocked =
            readyBeforeLook.firstNotNullOfOrNull { ready ->
                reader.pods = names.filterNot(ready::contains).map { pod("a", it) }
                looker.look(listOf(a)) as? RolloutsLook.Blocked
            }

        assertThat(blocked?.block?.describe()).contains("node n20")
    }

    @Test
    fun `a node that stays unready is read within ceil(nodes seen over bound) looks, however the others change`() {
        val random = Random(SEED)
        val bound = Constants.K8s.ROLLOUT_NODE_CHECKS_PER_LOOK
        repeat(PROPERTY_TRIALS) { trial ->
            val all = (0 until 120).map { "n%03d".format(it) }.shuffled(random)
            val unready = all.take(random.nextInt(1, 90)).toMutableSet()
            val joining = all.drop(unready.size).take(random.nextInt(0, 25))
            val dead = unready.random(random)
            val reader = shrinkingReader(all, dead)
            val looker = RolloutLooker(reader, Clock.fixed(NOW, ZoneOffset.UTC))
            val seen = unready.toMutableSet()
            var looks = 0
            do {
                reader.pods = unready.sorted().map { pod("a", it) }
                looks++
                val look = looker.look(listOf(a))
                // Some healthy pods become Ready; now and then new nodes join the rollout.
                unready.removeAll(unready.filter { it != dead && random.nextBoolean() }.toSet())
                if (looks == 1 && random.nextBoolean()) unready += joining.also { seen += it }
            } while (look !is RolloutsLook.Blocked && looks <= all.size)

            assertThat(looks).describedAs("trial %d", trial).isLessThanOrEqualTo((seen.size + bound - 1) / bound)
        }
    }

    /** A pending DaemonSet over [names] in which only [dead] is NotReady past the grace. */
    private fun shrinkingReader(
        names: List<String>,
        dead: String,
    ): FakeReader =
        FakeReader(
            daemonSets = mapOf("a" to daemonSet("a", desired = names.size, available = 0)),
            nodes = names.associateWith { if (it == dead) node(it, "False", NOW.minusSeconds(61)) else readyNode(it) },
        )

    @Test
    fun `a node with a current pod beside a terminating old one is judged by the current pod alone`() {
        val reader =
            FakeReader(
                daemonSets = mapOf("a" to daemonSet("a", desired = 1, available = 0, generation = 2)),
                pods = listOf(pod("a", "db7", generation = 2), pod("a", "db7", generation = 1, terminating = true)),
                nodes = mapOf("db7" to node("db7", "False", NOW.minusSeconds(61))),
            )

        val look = RolloutLooker(reader, Clock.fixed(NOW, ZoneOffset.UTC)).look(listOf(a))

        assertThat((look as RolloutsLook.Blocked).block.describe()).contains("a-db7-ds-g2").doesNotContain("a-db7-ds-g1")
        assertThat(reader.nodeReads).containsExactly("db7")
    }

    private fun node(
        name: String,
        ready: String,
        since: Instant,
    ): Node =
        NodeBuilder()
            .withNewMetadata()
            .withName(name)
            .endMetadata()
            .withNewStatus()
            .addNewCondition()
            .withType("Ready")
            .withStatus(ready)
            .withReason("NodeStatusUnknown")
            .withLastTransitionTime(since.toString())
            .endCondition()
            .endStatus()
            .build()

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
