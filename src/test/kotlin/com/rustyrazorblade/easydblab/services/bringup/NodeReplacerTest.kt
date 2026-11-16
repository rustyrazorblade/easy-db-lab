package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [NodeReplacer]'s decisions (design D6): the old instance is gone before the new one launches,
 * the node's K8s objects are deleted only when it had registered, the new instance takes the old
 * alias and the saved state keeps one host per alias, a refused check or launch makes no instance,
 * the budget is honoured, and no replacement starts while the control plane is unhealthy.
 */
class NodeReplacerTest {
    @TempDir
    lateinit var dir: File

    private val fleet = FakeInstanceFleet()
    private val cleaned: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val health = HealthLatch()
    private val events: MutableList<Event> = Collections.synchronizedList(mutableListOf())
    private val eventBus =
        EventBus().also {
            it.addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        events += envelope.event
                    }

                    override fun close() = Unit
                },
            )
        }

    private fun host(ordinal: Int) =
        ClusterHost(
            publicIp = "54.0.0.$ordinal",
            privateIp = "10.0.0.$ordinal",
            alias = "db$ordinal",
            availabilityZone = "us-west-2a",
            instanceId = "i-old$ordinal",
        )

    private val manager by lazy { ClusterStateManager(File(dir, "state.json")) }

    private fun replacer(
        nodes: Int = 3,
        budget: ReplacementBudget = ReplacementBudget(nodes),
    ): NodeReplacer {
        val state = ClusterState(name = "lab", versions = mutableMapOf())
        state.updateHosts(mapOf(ServerType.Cassandra to (0 until nodes).map(::host)))
        val writer = ClusterStateWriter(state, manager)
        return NodeReplacer(fleet, { cleaned += it }, writer, budget, health, eventBus)
    }

    private val failure = NodeReplacer.Failure(PipelineStep.SETUP, "setup_instance.sh exited 1")

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `the old instance is terminated before the new one launches, and K8s cleanup runs only for a registered node`(registered: Boolean) {
        val outcome = replacer().replace(ServerType.Cassandra, host(1), registered, failure)

        assertThat(fleet.calls).containsExactly("terminate i-old1", "verify db1", "launch db1")
        assertThat(cleaned).isEqualTo(if (registered) listOf("db1") else emptyList<String>())
        val replaced = outcome as NodeReplacer.Outcome.Replaced
        assertThat(replaced.host.alias).isEqualTo("db1")
        assertThat(replaced.host.availabilityZone).isEqualTo("us-west-2b")
        assertThat(replaced.replacement).isEqualTo(1)
    }

    @Test
    fun `the saved state holds the new instance under the old alias, one host per alias, in ordinal order`() {
        val replaced = replacer().replace(ServerType.Cassandra, host(1), registered = true, failure) as NodeReplacer.Outcome.Replaced

        val saved = manager.load().hosts.getValue(ServerType.Cassandra)
        assertThat(saved.map { it.alias }).containsExactly("db0", "db1", "db2")
        assertThat(saved.map { it.instanceId }).containsExactly("i-old0", replaced.host.instanceId, "i-old2")
        assertThat(events.filterIsInstance<Event.Provision.NodeReplaced>().single())
            .isEqualTo(Event.Provision.NodeReplaced("db1", "i-old1", replaced.host.instanceId, replaced.host.privateIp))
    }

    @ParameterizedTest
    @ValueSource(strings = ["verify", "launch"])
    fun `a refused capacity check or launch leaves the node with no instance and the state unchanged`(refused: String) {
        fleet.failing += refused

        val outcome = replacer().replace(ServerType.Cassandra, host(1), registered = true, failure)

        assertThat(outcome).isInstanceOf(NodeReplacer.Outcome.NotReplaced::class.java)
        assertThat((outcome as NodeReplacer.Outcome.NotReplaced).reason).contains("refused")
        assertThat(fleet.calls.filter { it.startsWith("launch") }).hasSize(if (refused == "launch") 1 else 0)
        assertThat(manager.exists()).isFalse()
    }

    @Test
    fun `a node that has had its replacements is not replaced, and no fleet call is made`() {
        val replacer = replacer(nodes = 100)
        repeat(2) { replacer.replace(ServerType.Cassandra, host(1), registered = false, failure) }
        fleet.calls.clear()

        val outcome = replacer.replace(ServerType.Cassandra, host(1), registered = false, failure)

        assertThat((outcome as NodeReplacer.Outcome.NotReplaced).reason).contains("setup failed").contains("2 replacements")
        assertThat(fleet.calls).isEmpty()
    }

    @Test
    fun `no replacement starts while the control plane is unhealthy`() {
        val replacer = replacer()
        health.markUnhealthy()

        val running = CompletableFuture.supplyAsync { replacer.replace(ServerType.Cassandra, host(1), registered = false, failure) }
        Thread.sleep(UNHEALTHY_WINDOW_MS)
        assertThat(fleet.calls).isEmpty()

        health.markHealthy()
        assertThat(running.get(5, TimeUnit.SECONDS)).isInstanceOf(NodeReplacer.Outcome.Replaced::class.java)
    }

    @Test
    fun `replacements run one at a time and every one lands in the state`() {
        val nodes = 40
        val replacer =
            replacer(nodes = nodes, budget = ReplacementBudget(nodes, perNodeLimit = 1).also { assertThat(it.clusterLimit).isEqualTo(3) })
        val outcomes =
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                (0 until nodes)
                    .map { executor.submit<NodeReplacer.Outcome> { replacer.replace(ServerType.Cassandra, host(it), false, failure) } }
                    .map { it.get() }
            }

        assertThat(fleet.maxInFlight).isEqualTo(1)
        assertThat(outcomes.filterIsInstance<NodeReplacer.Outcome.Replaced>()).hasSize(3)
        assertThat(outcomes.filterIsInstance<NodeReplacer.Outcome.NotReplaced>()).allMatch { "cluster made its 3" in it.reason }
        val saved = manager.load().hosts.getValue(ServerType.Cassandra)
        assertThat(saved).hasSize(nodes)
        assertThat(saved.count { it.instanceId.startsWith("i-db") }).isEqualTo(3)
    }

    private companion object {
        const val UNHEALTHY_WINDOW_MS = 100L
    }
}
