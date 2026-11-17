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
import org.junit.jupiter.params.provider.CsvSource
import java.io.File
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [NodeReplacer]'s decisions (design D6): the old instance is gone before the new one launches,
 * the new instance takes the old alias and the saved state keeps one host per alias, a refused check or launch makes no instance,
 * the budget is honoured, and no replacement starts while the control plane is unhealthy.
 */
class NodeReplacerTest {
    @TempDir
    lateinit var dir: File

    private val fleet = FakeInstanceFleet()
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
        return NodeReplacer(fleet, writer, budget, health, eventBus)
    }

    private val failure = NodeReplacer.Failure(PipelineStep.SETUP, "setup_instance.sh exited 1")

    @Test
    fun `the old instance is terminated before the new one launches`() {
        val outcome = replacer().replace(ServerType.Cassandra, host(1), failure)

        assertThat(fleet.calls).containsExactly("terminate i-old1", "verify db1", "launch db1")
        val replaced = outcome as NodeReplacer.Outcome.Replaced
        assertThat(replaced.host.alias).isEqualTo("db1")
        assertThat(replaced.host.availabilityZone).isEqualTo("us-west-2b")
        assertThat(replaced.replacement).isEqualTo(1)
    }

    @Test
    fun `the saved state holds the new instance under the old alias, one host per alias, in ordinal order`() {
        val replaced = replacer().replace(ServerType.Cassandra, host(1), failure) as NodeReplacer.Outcome.Replaced

        val saved = manager.load().hosts.getValue(ServerType.Cassandra)
        assertThat(saved.map { it.alias }).containsExactly("db0", "db1", "db2")
        assertThat(saved.map { it.instanceId }).containsExactly("i-old0", replaced.host.instanceId, "i-old2")
        assertThat(events.filterIsInstance<Event.Provision.NodeReplaced>().single())
            .isEqualTo(Event.Provision.NodeReplaced("db1", "i-old1", replaced.host.instanceId, replaced.host.privateIp))
    }

    /**
     * The old instance is terminated before the capacity check, so a refused check or launch
     * leaves the node with no instance: the outcome and the event say so, and both the step's
     * failure and the replacement's are reported.
     */
    @ParameterizedTest(name = "{0} refused at {1}")
    @CsvSource("verify, capacity-check", "launch, launch")
    fun `a refused capacity check or launch leaves the node with no instance and the state unchanged`(
        refused: String,
        phase: String,
    ) {
        fleet.failing += refused

        val outcome = replacer().replace(ServerType.Cassandra, host(1), failure) as NodeReplacer.Outcome.NotReplaced

        assertThat(outcome.oldInstanceKept).isFalse()
        assertThat(outcome.reason).contains("setup failed (setup_instance.sh exited 1)").contains("failed at $phase").contains("refused")
        assertThat(fleet.calls.filter { it.startsWith("launch") }).hasSize(if (refused == "launch") 1 else 0)
        assertThat(manager.exists()).isFalse()
        assertThat(events.filterIsInstance<Event.Provision.NodeReplaceFailed>().single())
            .extracting({ it.alias }, { it.oldInstanceId }, { it.phase }, { it.oldInstanceKept })
            .containsExactly("db1", "i-old1", phase, false)
    }

    @Test
    fun `a state write that fails after the launch names the launched instance`() {
        val state = ClusterState(name = "lab", versions = mutableMapOf())
        state.updateHosts(mapOf(ServerType.Cassandra to (0 until 3).map(::host)))
        // The state file's directory does not exist, so every save fails.
        val unwritable = ClusterStateWriter(state, ClusterStateManager(File(dir, "missing/state.json")))
        val replacer = NodeReplacer(fleet, unwritable, ReplacementBudget(3), health, eventBus)

        val outcome = replacer.replace(ServerType.Cassandra, host(1), failure) as NodeReplacer.Outcome.NotReplaced

        assertThat(fleet.calls).containsExactly("terminate i-old1", "verify db1", "launch db1")
        assertThat(outcome.oldInstanceKept).isFalse()
        assertThat(outcome.reason).contains("failed at record").contains("i-db1-1 runs but is not in the state")
        val event = events.filterIsInstance<Event.Provision.NodeReplaceFailed>().single()
        assertThat(event.phase).isEqualTo("record")
        assertThat(event.reason).contains("i-db1-1")
        assertThat(events.filterIsInstance<Event.Provision.NodeReplaced>()).isEmpty()
    }

    @Test
    fun `a failed terminate keeps the old instance, and nothing launches`() {
        fleet.failing += "terminate"

        val outcome = replacer().replace(ServerType.Cassandra, host(1), failure) as NodeReplacer.Outcome.NotReplaced

        assertThat(outcome.oldInstanceKept).isTrue()
        assertThat(fleet.calls).containsExactly("terminate i-old1")
        assertThat(events.filterIsInstance<Event.Provision.NodeReplaceFailed>().single().phase).isEqualTo("terminate")
    }

    @Test
    fun `an interrupt while waiting for a healthy control plane stops the replacement with nothing terminated`() {
        val replacer = replacer()
        health.markUnhealthy()
        val result = CompletableFuture<Any?>()

        val pipeline =
            Thread.ofVirtual().start {
                try {
                    result.complete(replacer.replace(ServerType.Cassandra, host(1), failure))
                } catch (e: InterruptedException) {
                    result.complete(e)
                }
            }
        Thread.sleep(UNHEALTHY_WINDOW_MS)
        pipeline.interrupt()

        assertThat(result.get(5, TimeUnit.SECONDS)).isInstanceOf(InterruptedException::class.java)
        assertThat(fleet.calls).isEmpty()
    }

    @Test
    fun `an interrupt while another replacement holds the lock stops the waiting one with nothing terminated`() {
        val replacer = replacer()
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        fleet.onTerminate = { id ->
            if (id == "i-old0") {
                holding.countDown()
                release.await()
            }
        }
        val first = onOwnThread { replacer.replace(ServerType.Cassandra, host(0), failure) }
        holding.await()
        val result = CompletableFuture<Any?>()

        val waiting =
            Thread.ofVirtual().start {
                try {
                    result.complete(replacer.replace(ServerType.Cassandra, host(1), failure))
                } catch (e: InterruptedException) {
                    result.complete(e)
                }
            }
        Thread.sleep(UNHEALTHY_WINDOW_MS)
        waiting.interrupt()

        assertThat(result.get(5, TimeUnit.SECONDS)).isInstanceOf(InterruptedException::class.java)
        release.countDown()
        assertThat(first.get(5, TimeUnit.SECONDS)).isInstanceOf(NodeReplacer.Outcome.Replaced::class.java)
        assertThat(fleet.calls.filter { it.startsWith("terminate") }).containsExactly("terminate i-old0")
    }

    @Test
    fun `a node that has had its replacements is not replaced, and no fleet call is made`() {
        val replacer = replacer(nodes = 100)
        repeat(2) { replacer.replace(ServerType.Cassandra, host(1), failure) }
        fleet.calls.clear()

        val outcome = replacer.replace(ServerType.Cassandra, host(1), failure)

        assertThat((outcome as NodeReplacer.Outcome.NotReplaced).reason).contains("setup failed").contains("2 replacements")
        assertThat(fleet.calls).isEmpty()
    }

    @Test
    fun `no replacement starts while the control plane is unhealthy`() {
        val replacer = replacer()
        health.markUnhealthy()

        val running = onOwnThread { replacer.replace(ServerType.Cassandra, host(1), failure) }
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
                    .map { executor.submit<NodeReplacer.Outcome> { replacer.replace(ServerType.Cassandra, host(it), failure) } }
                    .map { it.get() }
            }

        assertThat(fleet.maxInFlight).isEqualTo(1)
        assertThat(outcomes.filterIsInstance<NodeReplacer.Outcome.Replaced>()).hasSize(3)
        assertThat(outcomes.filterIsInstance<NodeReplacer.Outcome.NotReplaced>()).allMatch { "cluster made its 3" in it.reason }
        val saved = manager.load().hosts.getValue(ServerType.Cassandra)
        assertThat(saved).hasSize(nodes)
        assertThat(saved.count { it.instanceId.startsWith("i-db") }).isEqualTo(3)
    }

    /**
     * Runs [body] on its own virtual thread. The common ForkJoinPool that `supplyAsync` uses is
     * shared by every test in the fork, and a pool busy with another test's blocked work never
     * starts the body.
     */
    private fun <T> onOwnThread(body: () -> T): CompletableFuture<T> =
        CompletableFuture<T>().also { future ->
            Thread.ofVirtual().start {
                runCatching(body).fold(future::complete, future::completeExceptionally)
            }
        }

    private companion object {
        const val UNHEALTHY_WINDOW_MS = 100L
    }
}
