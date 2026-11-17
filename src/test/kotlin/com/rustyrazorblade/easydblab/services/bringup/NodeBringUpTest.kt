package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.bringup.BringUpHarness.Companion.hang
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
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
     * replacement removes them once, before its first join. A first instance removes nothing.
     */
    @ParameterizedTest
    @ValueSource(strings = ["setup", "registry-tls", "join", "readiness", "ended"])
    fun `a replacement removes its alias's old cluster objects once, before its first join`(stoppedAt: String) {
        val h = harness(dbs = 2)
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
        assertThat(event?.nodes).containsExactly(Event.Provision.FailedNode("db0", "setup", failed.reason, "i-db0-2"))
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
    fun `an image missing the Cilium node fixes fails the node with no replacement`() {
        val h = harness(dbs = 2)
        h.settings = h.settings.copy(cni = CniMode.Cilium)
        h.steps.missingFixes["db1"] = listOf("/etc/sysctl.d/99-cilium.conf")

        val result = h.run()

        val failed = result.nodes.getValue("db1") as NodeEnd.Failed
        assertThat(failed.step).isEqualTo(PipelineStep.IMAGE_CHECK)
        assertThat(failed.reason).contains("99-cilium.conf")
        assertThat(h.fleet.calls).isEmpty()
        assertThat(h.eventsOf<Event.Cilium.NodeImageMissingFixes>().single().nodes).containsExactly("db1")
        assertThat(result.nodes.getValue("db0")).isInstanceOf(NodeEnd.Ready::class.java)
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
        assertThat(h.eventsOf<Event.K3s.ControlPlaneGateOpen>()).hasSize(1)
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
    }

    @Test
    fun `at most the join limit of 40 nodes are joining at once`() {
        val h = harness(dbs = 40)
        h.plane.registrationDelayMs = 10

        val result = h.run()

        assertThat(result.nodes.values).hasSize(40).allMatch { it is NodeEnd.Ready }
        assertThat(h.plane.maxJoining).isLessThanOrEqualTo(h.settings.joinLimit).isGreaterThan(1)
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
        h.waitFor("the gate open") { h.eventsOf<Event.K3s.ControlPlaneGateOpen>().isNotEmpty() }
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
        h.steps.scripts["db0 join"] = { _, attempt -> if (attempt == 1) h.plane.unhealthy.set(true) }

        val result = h.runInBackground()
        h.waitFor("unhealthy") { h.eventsOf<Event.Provision.ControlPlaneUnhealthy>().isNotEmpty() }
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
                assertThat(it.reason).contains("60000ms").contains("systemctl restart k3s")
            }
        assertThat(h.fleet.calls).isEmpty()
    }

    @Test
    fun `a control step that fails after its retries stops every pipeline and replaces nothing`() {
        val h = harness(dbs = 3)
        h.steps.scripts["control0 k3s-server"] = { _, _ -> error("k3s.service failed") }
        h.steps.scripts["db1 setup"] = { _, _ -> hang() }

        assertThatThrownBy { h.run() }
            .isInstanceOfSatisfying(ControlNodeFailedException::class.java) {
                assertThat(it.alias).isEqualTo("control0")
                assertThat(it.step).isEqualTo("k3s-server")
                assertThat(it.reason).contains("k3s.service failed")
            }
        assertThat(h.steps.callsOf("k3s-server")).hasSize(2)
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
    }
}
