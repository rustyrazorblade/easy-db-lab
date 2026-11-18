package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.output.RecordingNodeOutput
import java.io.File
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * One bring-up over fakes (design D11): a control node, [dbCount] database and [appCount]
 * application nodes, the fake provider, steps and control plane, and the real state writer on a
 * file in [dir]. Timeouts are short and intervals near zero, so a test runs in well under a second.
 */
class BringUpHarness(
    dir: File,
    val dbCount: Int,
    val appCount: Int = 0,
) {
    val plane = FakeControlPlane()
    val steps = FakeNodeSteps(plane)
    val tracker = FakeInstanceTracker()
    val fleet = FakeInstanceFleet()
    val events: MutableList<Event> = Collections.synchronizedList(mutableListOf())
    val stateFile = File(dir, "state.json")

    /** The real node output, recording: what the fake steps write here is each node's tail. */
    val nodeOutput = RecordingNodeOutput()
    private val state = ClusterState(name = "lab")
    val writer = ClusterStateWriter(state, ClusterStateManager(stateFile))

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

    val control = host(ServerType.Control, 0)
    val dbs = (0 until dbCount).map { host(ServerType.Cassandra, it) }
    val apps = (0 until appCount).map { host(ServerType.Stress, it) }

    var settings =
        BringUpSettings(
            nodeCount = dbCount + appCount,
            timing = FAST,
        )

    /** Aliases whose instance `up` found already running; every other node was launched in this run. */
    var foundRunning: Set<String> = emptySet()

    /** Runs after the first batch of nodes is handed over and before the next, as a slow later launch call would. */
    var afterFirstBatch: () -> Unit = {}

    /**
     * Records the hosts, as `up` does, then brings them up: the instances found running are
     * handed over first, then the ones launched.
     */
    fun run(): BringUpResult {
        writer.mergeHosts(ServerType.Control, listOf(control))
        writer.mergeHosts(ServerType.Cassandra, dbs)
        if (apps.isNotEmpty()) writer.mergeHosts(ServerType.Stress, apps)
        val ports = BringUpPortSet(tracker, fleet, steps, steps, writer)
        val handOvers =
            LaunchSink.Origin.entries.flatMap { origin ->
                listOf(ServerType.Cassandra to dbs, ServerType.Stress to apps).map { (serverType, hosts) ->
                    Triple(serverType, hosts.filter { originOf(it) == origin }, origin)
                }
            }
        return NodeBringUp(ports, settings, eventBus, nodeOutput).run { sink ->
            sink.launched(ServerType.Control, listOf(control), LaunchSink.Origin.LAUNCHED)
            handOvers
                .filter { (_, hosts, _) -> hosts.isNotEmpty() }
                .forEachIndexed { batch, (serverType, hosts, origin) ->
                    if (batch == 1) afterFirstBatch()
                    sink.launched(serverType, hosts, origin)
                }
        }
    }

    private fun originOf(host: ClusterHost): LaunchSink.Origin =
        if (host.alias in foundRunning) LaunchSink.Origin.FOUND_RUNNING else LaunchSink.Origin.LAUNCHED

    /** [run] on a thread of its own, for a test that acts while the bring-up runs. */
    fun runInBackground(): CompletableFuture<BringUpResult> {
        val result = CompletableFuture<BringUpResult>()
        Thread.ofVirtual().start {
            runCatching { run() }.fold(result::complete, result::completeExceptionally)
        }
        return result
    }

    /** The hosts of [serverType] in the saved state file. */
    fun saved(serverType: ServerType): List<ClusterHost> = ClusterStateManager(stateFile).load().hosts[serverType].orEmpty()

    /** Waits up to [WAIT] for [condition]. */
    fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + WAIT.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(1)
        }
    }

    /** The events of type [T]. */
    inline fun <reified T : Event> eventsOf(): List<T> = synchronized(events) { events.filterIsInstance<T>() }

    companion object {
        val WAIT: Duration = Duration.ofSeconds(10)
        private val SHORT = Duration.ofMillis(300)

        val FAST =
            BringUpTiming(
                running = SHORT,
                statusChecks = SHORT,
                sshTimeout = SHORT,
                sshInterval = Duration.ofMillis(1),
                retryInterval = Duration.ZERO,
                imageCheck = SHORT,
                setup = StepPolicy(2, Duration.ofSeconds(2), replaceable = true),
                registryTls = StepPolicy(3, Duration.ofSeconds(2), replaceable = true),
                join = StepPolicy(2, Duration.ofSeconds(2), replaceable = true, forgiveWhileUnhealthy = true),
                ready = StepPolicy(1, SHORT, replaceable = true, forgiveWhileUnhealthy = true),
                controlStep = Duration.ofSeconds(2),
                gateTimeout = Duration.ofSeconds(2),
                gateInterval = Duration.ofMillis(1),
                healthInterval = Duration.ofMillis(2),
                progressInterval = Duration.ofHours(1),
            )

        fun host(
            type: ServerType,
            ordinal: Int,
        ): ClusterHost {
            val alias = "${type.serverType}$ordinal"
            val net = type.ordinal
            return ClusterHost(
                publicIp = "",
                privateIp = "10.$net.0.$ordinal",
                alias = alias,
                availabilityZone = "us-west-2a",
                instanceId = "i-$alias",
            )
        }

        /** Blocks the calling step until it is interrupted. */
        fun hang(): Nothing {
            Thread.sleep(TimeUnit.MINUTES.toMillis(1))
            error("not interrupted")
        }
    }
}
