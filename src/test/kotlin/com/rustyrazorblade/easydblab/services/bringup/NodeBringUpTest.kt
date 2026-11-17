package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.bringup.BringUpHarness.Companion.hang
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The coordinator's decisions (design D11), each one over fakes: when a node may go on without
 * the others, when a step is retried, when a node is replaced and how, when it fails for good,
 * what the control plane's gate and health hold back, and what a control node failure stops.
 */
class NodeBringUpTest {
    @TempDir
    lateinit var dir: File

    private fun harness(
        dbs: Int,
        apps: Int = 0,
    ) = BringUpHarness(dir, dbs, apps)

    private fun failSetupOn(
        h: BringUpHarness,
        alias: String,
        instanceIds: (String) -> Boolean,
    ) {
        h.steps.scripts["$alias setup"] = { host, _ -> check(!instanceIds(host.instanceId)) { "setup_instance.sh exited 1" } }
    }

    @Test
    fun `a slow node does not hold back the others`() {
        val h = harness(dbs = 3)
        val release = CountDownLatch(1)
        h.steps.scripts["db1 setup"] = { _, _ -> release.await() }

        val result = h.runInBackground()
        h.waitFor("db0 and db2 Ready") { h.eventsOf<Event.Provision.NodeReady>().map { it.alias }.containsAll(listOf("db0", "db2")) }

        assertThat(h.eventsOf<Event.Provision.NodeReady>().map { it.alias }).doesNotContain("db1")
        assertThat(h.steps.callsOf("join").map { it.substringBefore(" ") }).doesNotContain("db1")
        release.countDown()
        assertThat(result.get(10, TimeUnit.SECONDS).nodes.values).allMatch { it is NodeEnd.Ready }
    }

    /** Setup installs the workspace's environment file, which names db0: no node sets up before it is written. */
    @Test
    fun `the progress line shows a slow node in its step and the others Ready`() {
        val h = harness(dbs = 3)
        h.settings = h.settings.copy(timing = h.settings.timing.copy(progressInterval = Duration.ofMillis(5)))
        val release = CountDownLatch(1)
        h.steps.scripts["db1 setup"] = { _, _ -> release.await() }

        val result = h.runInBackground()
        val shown = { p: Event.Provision.BringUpProgress -> p.ready == 2 && p.byStep == mapOf("setup" to 1) }
        h.waitFor("a progress line with db1 in setup") { h.eventsOf<Event.Provision.BringUpProgress>().any(shown) }
        release.countDown()

        assertThat(result.get(10, TimeUnit.SECONDS).nodes.values).allMatch { it is NodeEnd.Ready }
        assertThat(h.eventsOf<Event.Provision.BringUpProgress>().first(shown).total).isEqualTo(3)
    }

    @Test
    fun `no node sets up before the workspace files are written`() {
        val h = harness(dbs = 2, apps = 1)
        val release = CountDownLatch(1)
        h.steps.scripts["control0 ssh"] = { _, _ -> release.await() }

        val result = h.runInBackground()
        h.waitFor("every node answers over SSH") { h.steps.callsOf("ssh").count { !it.startsWith("control0") } == 3 }
        Thread.sleep(SETTLE_MS)

        assertThat(h.steps.callsOf("setup")).isEmpty()
        release.countDown()
        assertThat(result.get(10, TimeUnit.SECONDS).nodes.values).allMatch { it is NodeEnd.Ready }
        val calls = synchronized(h.steps.calls) { h.steps.calls.toList() }
        assertThat(calls.indexOf("control workspace -")).isNotNegative().isLessThan(calls.indexOfFirst { " setup " in it })
    }

    @Test
    fun `a step that fails once is retried and the node needs no replacement`() {
        val h = harness(dbs = 2)
        h.steps.scripts["db0 registry-tls"] = { _, attempt -> check(attempt > 1) { "curl: connection refused" } }

        val result = h.run()

        assertThat(result.nodes["db0"]).isEqualTo(NodeEnd.Ready(result.nodes.getValue("db0").host, 0))
        assertThat(h.steps.callsOf("registry-tls").filter { it.startsWith("db0") }).hasSize(2)
        assertThat(h.fleet.calls).isEmpty()
    }

    @Test
    fun `a node whose setup fails every attempt is replaced under its alias, and the state holds the new instance once`() {
        val h = harness(dbs = 3)
        failSetupOn(h, "db1") { it == "i-db1" }

        val result = h.run()

        assertThat(h.steps.callsOf("setup").filter { it.startsWith("db1") }).containsExactly("db1 i-db1", "db1 i-db1", "db1 i-db1-1")
        assertThat(h.fleet.calls).containsExactly("terminate i-db1", "verify db1", "launch db1")
        val end = result.nodes.getValue("db1")
        assertThat(end).isInstanceOf(NodeEnd.Ready::class.java)
        assertThat(end.replacements).isEqualTo(1)
        assertThat(end.host.instanceId).isEqualTo("i-db1-1")
        val saved = h.saved(ServerType.Cassandra)
        assertThat(saved.map { it.alias }).containsExactly("db0", "db1", "db2")
        assertThat(saved.single { it.alias == "db1" }.instanceId).isEqualTo("i-db1-1")
        assertThat(saved.single { it.alias == "db1" }.publicIp).isEqualTo("pub-i-db1-1")
        assertThat(h.eventsOf<Event.Provision.NodeReplacing>().single().step).isEqualTo("setup")
    }

    /**
     * Whatever step the first instance stopped at, the alias may hold a Node and a node password,
     * left by this run or by an earlier `up` (which would make K3s refuse the replacement), so the
     * replacement removes them once, before its first join. The first instances here were found
     * running, so they may own those objects and remove nothing.
     */
    @ParameterizedTest
    @ValueSource(strings = ["setup", "registry-tls", "join", "readiness", "ended"])
    fun `a replacement removes its alias's old cluster objects once, before its first join`(stoppedAt: String) {
        val h = harness(dbs = 2)
        h.foundRunning = setOf("db0", "db1")
        val first = { host: ClusterHost -> host.instanceId == "i-db1" }
        when (stoppedAt) {
            "readiness" -> h.plane.neverReady += h.dbs[1].privateIp
            "ended" ->
                h.steps.scripts["db1 setup"] = { host, _ ->
                    if (first(host)) {
                        h.tracker.end(host.instanceId)
                        hang()
                    }
                }
            else -> h.steps.scripts["db1 $stoppedAt"] = { host, _ -> check(!first(host)) { "$stoppedAt failed" } }
        }
        val cleanedAtJoin = Collections.synchronizedList(mutableListOf<String>())
        val joinScript = h.steps.scripts["db1 join"]
        h.steps.scripts["db1 join"] = { host, attempt ->
            cleanedAtJoin += "${host.instanceId} ${h.plane.cleaned}"
            joinScript?.invoke(host, attempt)
        }

        val result = h.run()

        assertThat(result.nodes.values).allMatch { it is NodeEnd.Ready }
        assertThat(
            result.nodes
                .getValue("db1")
                .host.instanceId,
        ).isEqualTo("i-db1-1")
        assertThat(h.plane.cleaned).containsExactly("db1")
        assertThat(cleanedAtJoin.filter { it.startsWith("i-db1 ") }).allMatch { it == "i-db1 []" }
        assertThat(cleanedAtJoin.filter { it.startsWith("i-db1-1 ") }).containsExactly("i-db1-1 [db1]")
    }

    /**
     * An instance launched in this `up` takes its alias from one an earlier `up` started, which may
     * have registered and gone (terminated outside `up`), so it removes the alias's objects once,
     * before its first join, or K3s refuses it ("Node password rejected"). An instance found
     * running may be the one that owns them, so it removes nothing. Each row mixes the two.
     */
    @ParameterizedTest
    @CsvSource(
        "'', 'db0,db1,app0'",
        "'db0,db1,app0', ''",
        "'db1', 'db0,app0'",
        "'db0,app0', 'db1'",
    )
    fun `a first instance launched in this up removes its alias's old cluster objects once, before its first join`(
        foundRunning: String,
        launched: String,
    ) {
        val h = harness(dbs = 2, apps = 1)
        h.foundRunning = foundRunning.split(",").filter { it.isNotEmpty() }.toSet()
        val cleanedAtJoin = Collections.synchronizedList(mutableListOf<String>())
        (h.dbs + h.apps).forEach { node ->
            h.steps.scripts["${node.alias} join"] = { host, _ -> cleanedAtJoin += "${host.alias} ${host.alias in h.plane.cleaned}" }
        }

        val result = h.run()

        assertThat(result.nodes.values).allMatch { it is NodeEnd.Ready }
        val expected = launched.split(",").filter { it.isNotEmpty() }
        assertThat(h.plane.cleaned).containsExactlyInAnyOrderElementsOf(expected)
        assertThat(cleanedAtJoin).containsExactlyInAnyOrderElementsOf(
            (h.dbs + h.apps).map { "${it.alias} ${it.alias in expected}" },
        )
    }

    @Test
    fun `a registered node that never becomes Ready is removed from the cluster before its replacement joins`() {
        val h = harness(dbs = 2)
        h.plane.neverReady += h.dbs[1].privateIp

        val result = h.run()

        assertThat(h.plane.registered.filter { it == "db1" }).hasSize(2)
        assertThat(result.nodes.getValue("db1").replacements).isEqualTo(1)
        assertThat(h.eventsOf<Event.Provision.NodeReplacing>().single().step).isEqualTo("readiness")
    }

    @Test
    fun `an instance that ends mid-setup is replaced at once, with no retry`() {
        val h = harness(dbs = 3)
        h.steps.scripts["db2 setup"] = { host, _ ->
            if (host.instanceId == "i-db2") {
                h.tracker.end(host.instanceId)
                hang()
            }
        }

        val result = h.run()

        assertThat(h.steps.callsOf("setup").filter { it == "db2 i-db2" }).hasSize(1)
        assertThat(h.fleet.calls).containsExactly("terminate i-db2", "verify db2", "launch db2")
        assertThat(result.nodes.values).allMatch { it is NodeEnd.Ready }
        assertThat(h.eventsOf<Event.Provision.NodeReplacing>().single().reason).contains("terminated")
    }

    @Test
    fun `a node out of replacements fails, keeps its last instance, and the others finish`() {
        val h = harness(dbs = 3)
        failSetupOn(h, "db0") { true }

        val result = h.run()

        val failed = result.nodes.getValue("db0") as NodeEnd.Failed
        assertThat(failed.step).isEqualTo(PipelineStep.SETUP)
        assertThat(failed.replacements).isEqualTo(2)
        assertThat(failed.reason).contains("2 replacements")
        // Two old instances terminated; the third, last one is kept for debugging.
        assertThat(h.fleet.calls.filter { it.startsWith("terminate") }).containsExactly("terminate i-db0", "terminate i-db0-1")
        assertThat(failed.host.instanceId).isEqualTo("i-db0-2")
        assertThat(h.saved(ServerType.Cassandra).single { it.alias == "db0" }.instanceId).isEqualTo("i-db0-2")
        assertThat(result.nodes.filterKeys { it != "db0" }.values).allMatch { it is NodeEnd.Ready }
        val event = result.failureEvent()
        assertThat(event?.nodes).containsExactly(Event.Provision.FailedNode("db0", "setup", failed.reason, "i-db0-2", instanceKept = true))
        // Reported the moment it failed, not only in the summary once the others finish.
        assertThat(h.eventsOf<Event.Provision.NodeFailed>().map { it.alias to it.instanceId }).containsExactly("db0" to "i-db0-2")
    }

    /** The old instance is gone before the launch, so a refused launch leaves the node with none, and no second try. */
    @Test
    fun `a fault outside any step fails the node on the instance it is on, and the others finish`() {
        val h = harness(dbs = 2)
        failSetupOn(h, "db1") { it == "i-db1" }
        h.plane.failWatch = { alias -> check(alias != "db1") { "watch refused" } }

        val result = h.run()

        val end = result.nodes.getValue("db1")
        assertThat(end).isInstanceOf(NodeEnd.Failed::class.java)
        assertThat(end.host.instanceId).isEqualTo("i-db1-1")
        assertThat(end.replacements).isEqualTo(1)
        assertThat(result.nodes.getValue("db0")).isInstanceOf(NodeEnd.Ready::class.java)
        assertThat(h.eventsOf<Event.Provision.NodeFailed>().single().instanceId).isEqualTo("i-db1-1")
    }

    @Test
    fun `a refused replacement launch fails the node with no instance left and no further replacement`() {
        val h = harness(dbs = 3)
        failSetupOn(h, "db1") { true }
        h.fleet.failing += "launch"

        val result = h.run()

        val failed = result.nodes.getValue("db1") as NodeEnd.Failed
        assertThat(failed.instanceKept).isFalse()
        assertThat(failed.reason).contains("setup failed").contains("failed at launch")
        assertThat(h.fleet.calls).containsExactly("terminate i-db1", "verify db1", "launch db1")
        assertThat(h.eventsOf<Event.Provision.NodeReplaceFailed>().single().oldInstanceKept).isFalse()
        assertThat(h.eventsOf<Event.Provision.NodeFailed>().single())
            .isEqualTo(Event.Provision.NodeFailed("db1", "setup", failed.reason, "i-db1", instanceKept = false))
        assertThat(
            result
                .failureEvent()
                ?.nodes
                ?.single()
                ?.instanceKept,
        ).isFalse()
        assertThat(result.nodes.filterKeys { it != "db1" }.values).allMatch { it is NodeEnd.Ready }
    }

    @Test
    fun `a fault every node has stops at the cluster limit`() {
        val h = harness(dbs = 3)
        h.dbs.forEach { failSetupOn(h, it.alias) { true } }

        val result = h.run()

        assertThat(result.failed).hasSize(3)
        assertThat(h.fleet.calls.filter { it.startsWith("launch") }).hasSize(ReplacementBudget(3).clusterLimit)
        assertThat(result.failed.map { it.reason }).anyMatch { it.contains("the cluster made its 3 replacements") }
    }

    @Test
    fun `an image that lacks what the cluster needs fails the node with no replacement`() {
        val h = harness(dbs = 2)
        val report = Event.Cilium.NodeImageMissingFixes(listOf("db1"), listOf("/etc/sysctl.d/99-cilium.conf"))
        h.steps.imageDefects["db1"] = ImageDefect("its image lacks 99-cilium.conf", report)

        val result = h.run()

        val failed = result.nodes.getValue("db1") as NodeEnd.Failed
        assertThat(failed.step).isEqualTo(PipelineStep.IMAGE_CHECK)
        assertThat(failed.reason).isEqualTo("its image lacks 99-cilium.conf")
        assertThat(h.fleet.calls).isEmpty()
        assertThat(h.eventsOf<Event.Cilium.NodeImageMissingFixes>()).containsExactly(report)
        assertThat(result.nodes.getValue("db0")).isInstanceOf(NodeEnd.Ready::class.java)
    }

    /**
     * Only an image that lacks the fixes rules out a replacement. A check that could not run (its
     * SSH command died, as when the instance is going away) says nothing about the image, so it is
     * retried and then replaced like any other step.
     */
    @Test
    fun `an image check that cannot run is retried, then the node is replaced`() {
        val h = harness(dbs = 2)
        h.steps.scripts["db1 image"] = { host, _ -> check(host.instanceId != "i-db1") { "No exit status returned for command" } }

        val result = h.run()

        assertThat(h.steps.callsOf("image").filter { it.startsWith("db1") }).containsExactly("db1 i-db1", "db1 i-db1", "db1 i-db1-1")
        assertThat(result.nodes.getValue("db1")).isEqualTo(NodeEnd.Ready(result.nodes.getValue("db1").host, 1))
        assertThat(h.eventsOf<Event.Provision.NodeReplacing>().single().step).isEqualTo("image-check")
        assertThat(h.eventsOf<Event.Cilium.NodeImageMissingFixes>()).isEmpty()
    }

    @Test
    fun `no node joins before the gate opens`() {
        val h = harness(dbs = 2)
        h.plane.open.set(false)

        val result = h.runInBackground()
        h.waitFor("every registry TLS done") { h.steps.callsOf("registry-tls").size == 2 }
        Thread.sleep(SETTLE_MS)

        assertThat(h.steps.callsOf("join")).isEmpty()
        h.plane.open.set(true)
        assertThat(result.get(10, TimeUnit.SECONDS).nodes.values).allMatch { it is NodeEnd.Ready }
        assertThat(h.eventsOf<Event.Provision.ControlPlaneGateOpen>()).hasSize(1)
    }

    @Test
    fun `a gate that never opens fails the control node, naming what is still false`() {
        val h = harness(dbs = 1)
        h.plane.open.set(false)

        assertThatThrownBy { h.run() }
            .isInstanceOfSatisfying(ControlNodeFailedException::class.java) {
                assertThat(it.step).isEqualTo("gate")
                assertThat(it.reason).contains("API server ready")
            }
        assertThat(h.steps.callsOf("join")).isEmpty()
        assertThat(h.fleet.calls).isEmpty()
        val reported = h.eventsOf<Event.Provision.ControlNodeFailed>().single()
        assertThat(reported.step).isEqualTo("gate")
        assertThat(reported.reason).contains("API server ready")
        assertThat(reported.isError()).isTrue()
    }

    @Test
    fun `nodes that reach their join together fill the join limit and never pass it`() {
        val h = harness(dbs = 20)
        h.settings = h.settings.copy(joinLimit = 3)
        // No node registers until 3 are joining, so the limit is reached however the threads are scheduled.
        h.plane.holdRegistration.set(true)

        val result = h.runInBackground()
        h.waitFor("the join limit filled") { h.plane.joiningNow == 3 }
        Thread.sleep(SETTLE_MS)
        assertThat(h.plane.joiningNow).isEqualTo(3)
        h.plane.holdRegistration.set(false)

        assertThat(result.get(10, TimeUnit.SECONDS).nodes.values).hasSize(20).allMatch { it is NodeEnd.Ready }
        assertThat(h.plane.maxJoining).isEqualTo(3)
    }

    @Test
    fun `no more SSH sessions than the limit are open at once across every pipeline`() {
        val h = harness(dbs = 20, apps = 4)
        h.settings = h.settings.copy(sshSessions = 3)
        h.steps.sshHoldMs = 2

        val result = h.run()

        assertThat(result.nodes.values).hasSize(24).allMatch { it is NodeEnd.Ready }
        assertThat(h.steps.sshPeak).isEqualTo(3)
    }

    @Test
    fun `a join waiting for its node to register holds no SSH session`() {
        val h = harness(dbs = 2)
        h.settings = h.settings.copy(sshSessions = 1)
        h.plane.holdRegistration.set(true)

        val result = h.runInBackground()
        h.waitFor("both agents started") { h.steps.callsOf("join").size == 2 }

        assertThat(h.steps.sshInFlight).isZero()
        h.plane.holdRegistration.set(false)
        assertThat(result.get(10, TimeUnit.SECONDS).nodes.values).allMatch { it is NodeEnd.Ready }
    }

    @Test
    fun `no node configures registry TLS before the registry certificate is uploaded`() {
        val h = harness(dbs = 3)
        val release = CountDownLatch(1)
        h.steps.scripts["control0 registry-cert"] = { _, _ -> release.await() }

        val result = h.runInBackground()
        h.waitFor("every image checked") { h.steps.callsOf("image").count { !it.startsWith("control0") } == 3 }
        Thread.sleep(SETTLE_MS)

        assertThat(h.steps.callsOf("registry-tls")).isEmpty()
        release.countDown()
        assertThat(result.get(10, TimeUnit.SECONDS).nodes.values).allMatch { it is NodeEnd.Ready }
    }

    @Test
    fun `a node whose launch call returned starts while a later launch call is still running`() {
        val h = harness(dbs = 2, apps = 2)
        val laterLaunch = CountDownLatch(1)
        h.afterFirstBatch = { laterLaunch.await() }

        val result = h.runInBackground()
        h.waitFor("the first batch past SSH") { h.steps.callsOf("ssh").count { it.startsWith("db") } == 2 }

        assertThat(synchronized(h.steps.calls) { h.steps.calls.toList() }).noneMatch { it.startsWith("app") }
        laterLaunch.countDown()
        assertThat(result.get(10, TimeUnit.SECONDS).nodes.values).hasSize(4).allMatch { it is NodeEnd.Ready }
    }

    /**
     * A failed join's reason says what it waited for and carries the agent's log, for every
     * combination of whether the agent started and whether its log could be read.
     */
    @ParameterizedTest(name = "agent started {0}, log {1}")
    @CsvSource(
        "true, text, 'level=error msg=\"token rejected\"'",
        "true, empty, (empty)",
        "true, unreadable, '(unreadable: ssh closed)'",
        "false, text, 'level=error msg=\"token rejected\"'",
    )
    fun `a failed join names what it waited for and ends with the agent's log`(
        started: Boolean,
        log: String,
        tail: String,
    ) {
        val h = harness(dbs = 1)
        h.settings = h.settings.copy(timing = h.settings.timing.copy(join = StepPolicy(1, Duration.ofMillis(100), true)))
        if (started) h.plane.neverRegister += "db0" else h.steps.scripts["db0 join"] = { _, _ -> error(JOIN_SCRIPT_FAILED) }
        when (log) {
            "text" -> h.steps.agentLogs["db0"] = "level=error msg=\"token rejected\"\n"
            "unreadable" -> h.steps.scripts["db0 join-log"] = { _, _ -> error("ssh closed") }
        }

        h.run()

        val waited =
            if (started) "its agent started, but no node db0 at 10.0.0.0 registered: timed out after 100ms" else JOIN_SCRIPT_FAILED
        assertThat(h.eventsOf<Event.Provision.NodeReplacing>().first().reason)
            .isEqualTo("$waited\nlast lines of the agent log:\n$tail")
    }

    @Test
    fun `a join permit is released at registration, not at Ready`() {
        val h = harness(dbs = 2)
        h.settings = h.settings.copy(joinLimit = 1, timing = h.settings.timing.copy(ready = StepPolicy(1, Duration.ofSeconds(5), true)))
        h.plane.autoReady = false

        val result = h.runInBackground()
        h.waitFor("both registered while neither is Ready") { h.plane.registered.containsAll(listOf("db0", "db1")) }

        assertThat(h.eventsOf<Event.Provision.NodeReady>()).isEmpty()
        h.plane.makeReady("db0")
        h.plane.makeReady("db1")
        assertThat(result.get(10, TimeUnit.SECONDS).nodes.values).allMatch { it is NodeEnd.Ready }
    }

    @Test
    fun `while the control plane is unhealthy no join permit is given and no replacement starts`() {
        val h = harness(dbs = 2)
        h.plane.open.set(false)
        h.plane.unhealthy.set(true)
        val unhealthy = { h.eventsOf<Event.Provision.ControlPlaneUnhealthy>().isNotEmpty() }
        h.steps.scripts["db1 setup"] = { host, _ ->
            if (host.instanceId == "i-db1") {
                h.waitFor("unhealthy", unhealthy)
                error("setup_instance.sh exited 1")
            }
        }

        val result = h.runInBackground()
        h.waitFor("unhealthy", unhealthy)
        h.plane.open.set(true)
        h.waitFor("the gate open") { h.eventsOf<Event.Provision.ControlPlaneGateOpen>().isNotEmpty() }
        h.waitFor("db1 due for replacement") { h.eventsOf<Event.Provision.NodeReplacing>().isNotEmpty() }
        Thread.sleep(SETTLE_MS)

        assertThat(h.steps.callsOf("join")).isEmpty()
        assertThat(h.fleet.calls).isEmpty()
        h.plane.unhealthy.set(false)
        assertThat(result.get(10, TimeUnit.SECONDS).nodes.values).allMatch { it is NodeEnd.Ready }
        assertThat(h.eventsOf<Event.Provision.ControlPlaneUnhealthy>()).hasSize(1)
        assertThat(h.eventsOf<Event.Provision.ControlPlaneHealthy>()).hasSize(1)
        assertThat(h.fleet.calls).containsExactly("terminate i-db1", "verify db1", "launch db1")
    }

    @Test
    fun `a join that times out while the control plane is unhealthy is run again without counting against the node`() {
        val h = harness(dbs = 1)
        h.settings =
            h.settings.copy(
                timing =
                    h.settings.timing.copy(
                        join = StepPolicy(1, Duration.ofMillis(JOIN_TIMEOUT_MS), true, forgiveWhileUnhealthy = true),
                    ),
            )
        h.plane.holdRegistration.set(true)
        // The first attempt turns the control plane unhealthy and goes on only once that is
        // reported, so it times out while unhealthy however slowly the health sampler runs.
        h.steps.scripts["db0 join"] = { _, attempt ->
            if (attempt == 1) {
                h.plane.unhealthy.set(true)
                h.waitFor("unhealthy") { h.eventsOf<Event.Provision.ControlPlaneUnhealthy>().isNotEmpty() }
            }
        }

        val result = h.runInBackground()
        h.waitFor("unhealthy") { h.eventsOf<Event.Provision.ControlPlaneUnhealthy>().isNotEmpty() }
        // The attempt counts as forgiven even if it times out after recovery: it saw an unhealthy period.
        Thread.sleep(JOIN_TIMEOUT_MS * 2)
        h.plane.holdRegistration.set(false)
        h.plane.unhealthy.set(false)

        val end = result.get(10, TimeUnit.SECONDS).nodes.getValue("db0")
        assertThat(end).isEqualTo(NodeEnd.Ready(end.host, 0))
        assertThat(h.steps.callsOf("join")).hasSizeGreaterThanOrEqualTo(2)
        assertThat(h.eventsOf<Event.Provision.NodeStepFailed>().filter { it.step == "join" }).isEmpty()
        assertThat(h.fleet.calls).isEmpty()
    }

    @Test
    fun `a control plane unhealthy past its deadline fails up, naming the last sample`() {
        val h = harness(dbs = 2)
        h.settings = h.settings.copy(timing = h.settings.timing.copy(unhealthyTimeout = Duration.ofMillis(SETTLE_MS)))
        h.plane.unhealthy.set(true)
        // Hold the node until the monitor has seen the control plane unhealthy, so it cannot join first.
        h.steps.scripts["db0 registry-tls"] = { _, _ ->
            h.waitFor("unhealthy") { h.eventsOf<Event.Provision.ControlPlaneUnhealthy>().isNotEmpty() }
        }

        assertThatThrownBy { h.run() }
            .isInstanceOfSatisfying(ControlNodeFailedException::class.java) {
                assertThat(it.step).isEqualTo("control-plane-health")
                assertThat(it.reason).contains("60000ms").endsWith(". ${FakeControlPlane.RECOVERY}")
            }
        assertThat(h.fleet.calls).isEmpty()
    }

    @Test
    fun `a control step that fails after its retries stops every pipeline and replaces nothing`() {
        val h = harness(dbs = 3)
        h.steps.scripts["control0 control-plane"] = { _, _ -> error("k3s.service failed") }
        h.steps.scripts["db1 setup"] = { _, _ -> hang() }

        assertThatThrownBy { h.run() }
            .isInstanceOfSatisfying(ControlNodeFailedException::class.java) {
                assertThat(it.alias).isEqualTo("control0")
                assertThat(it.step).isEqualTo("control-plane")
                assertThat(it.reason).contains("k3s.service failed")
            }
        assertThat(h.steps.callsOf("control-plane")).hasSize(2)
        assertThat(h.steps.callsOf("join")).isEmpty()
        assertThat(h.fleet.calls).isEmpty()
        assertThat(h.saved(ServerType.Cassandra)).hasSize(3)
    }

    @Test
    fun `--no-setup ends every pipeline after SSH`() {
        val h = harness(dbs = 2, apps = 1)
        h.settings = h.settings.copy(noSetup = true)

        val result = h.run()

        assertThat(result.nodes.values).hasSize(3).allMatch { it is NodeEnd.Provisioned }
        assertThat(
            h.steps.calls
                .map { it.split(" ")[1] }
                .distinct(),
        ).containsExactlyInAnyOrder("ssh", "workspace", "connect")
    }

    @Test
    fun `--hosts runs only the named node pipelines, and the control node's`() {
        val h = harness(dbs = 3, apps = 1)
        h.settings = h.settings.copy(hostFilter = "db1")

        val result = h.run()

        assertThat(result.nodes.keys).containsExactly("db1")
        assertThat(
            h.steps.calls
                .map { it.substringBefore(" ") }
                .distinct(),
        ).containsExactlyInAnyOrder("control0", "control", "db1")
    }

    @Test
    fun `the database versions file is fetched once, by a database node`() {
        val h = harness(dbs = 5, apps = 2)

        h.run()

        assertThat(h.steps.callsOf("versions")).hasSize(1).allMatch { it.startsWith("db") }
    }

    private companion object {
        const val SETTLE_MS = 100L
        const val JOIN_TIMEOUT_MS = 150L
        const val JOIN_SCRIPT_FAILED = "start-k3s-agent.sh exited 1"
    }
}
