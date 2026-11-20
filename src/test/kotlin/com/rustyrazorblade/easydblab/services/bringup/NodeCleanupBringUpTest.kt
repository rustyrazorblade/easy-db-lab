package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.bringup.BringUpHarness.Companion.hang
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * When a node removes its alias's old Node and node password from the control plane: before the
 * first join of a replacement or of an instance launched in this `up`, and when a node that may
 * have an older instance's objects fails before it joins. A found-running node keeps them.
 */
class NodeCleanupBringUpTest {
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

    /**
     * A cleanup that fails (the control plane still holds the old Node) is a control-plane fault, not
     * an instance fault: replacing the healthy instance would burn a budget slot and wait on the
     * same Node, so the node fails on its instance, which is kept.
     */
    @Test
    fun `a failed cleanup of the old node fails the node with no replacement`() {
        val h = harness(dbs = 2)
        h.plane.cleanupFailures["db1"] = "Node db1 (uid u1) was still present: deletionTimestamp=t"

        val result = h.run()

        val end = result.nodes.getValue("db1")
        assertThat(end).isInstanceOf(NodeEnd.Failed::class.java)
        assertThat(end.replacements).isEqualTo(0)
        assertThat(h.fleet.calls).isEmpty()
        assertThat(result.nodes.getValue("db0")).isInstanceOf(NodeEnd.Ready::class.java)
        val failed = h.eventsOf<Event.Provision.NodeFailed>().single()
        assertThat(failed.step).isEqualTo("node-cleanup")
        assertThat(failed.reason).contains("db1").contains("deletionTimestamp=t")
    }

    /**
     * A node that ends failed before it joined, after a replacement, leaves the alias's old Node
     * (its first instance's, now terminated) in the cluster unless it is removed. That Node never
     * posts status again, and the DaemonSet pods bound to it hold the observability rollout past
     * its NotReady grace, so the post-steps fail on a node that is already reported failed. It is
     * removed when the node fails. A node found running that never reached a replacement keeps its
     * objects: they may be its own.
     */
    @Test
    fun `a node that fails before it joins, after a replacement, removes its alias's old cluster objects`() {
        val h = harness(dbs = 2, apps = 1)
        h.foundRunning = setOf("db0", "db1", "app0")
        failSetupOn(h, "app0") { true }

        val result = h.run()

        val end = result.nodes.getValue("app0")
        assertThat(end).isInstanceOf(NodeEnd.Failed::class.java)
        assertThat(end.replacements).isEqualTo(2)
        assertThat(h.plane.cleaned).containsExactly("app0")
        assertThat(h.steps.callsOf("join").map { it.substringBefore(" ") }).doesNotContain("app0")
    }

    @Test
    fun `a found-running node that fails with no replacement keeps its alias's cluster objects`() {
        val h = harness(dbs = 2)
        h.foundRunning = setOf("db0", "db1")
        h.plane.cleanupFailures["db1"] = "should not be called"
        val report = Event.Cilium.NodeImageMissingFixes(listOf("db1"), listOf("/etc/sysctl.d/99-cilium.conf"))
        h.steps.imageDefects["db1"] = ImageDefect("its image lacks 99-cilium.conf", report)

        val result = h.run()

        assertThat((result.nodes.getValue("db1") as NodeEnd.Failed).step).isEqualTo(PipelineStep.IMAGE_CHECK)
        assertThat(h.plane.cleaned).isEmpty()
    }

    /**
     * A first instance launched in this `up` may take its alias from an earlier `up`'s, so it
     * removes the old objects even with no replacement. The removal waits for the gate, but the
     * failure is reported the moment it happens, before the gate opens.
     */
    @Test
    fun `a launched node that fails before the gate is reported at once and removes its alias's old objects once the gate opens`() {
        val h = harness(dbs = 2)
        h.foundRunning = setOf("db0")
        h.plane.open.set(false)
        val report = Event.Cilium.NodeImageMissingFixes(listOf("db1"), listOf("/etc/sysctl.d/99-cilium.conf"))
        h.steps.imageDefects["db1"] = ImageDefect("its image lacks 99-cilium.conf", report)

        val result = h.runInBackground()
        h.waitFor("db1 reported failed") { h.eventsOf<Event.Provision.NodeFailed>().any { it.alias == "db1" } }
        assertThat(h.plane.cleaned).isEmpty()
        h.plane.open.set(true)

        val end = result.get(10, TimeUnit.SECONDS).nodes.getValue("db1")
        assertThat(end).isInstanceOf(NodeEnd.Failed::class.java)
        assertThat(end.replacements).isEqualTo(0)
        assertThat(h.plane.cleaned).containsExactly("db1")
    }

    @Test
    fun `a failed removal of a failed node's old objects is added to its reason in the summary`() {
        val h = harness(dbs = 2)
        h.plane.cleanupFailures["db1"] = "Node db1 (uid u1) was still present: deletionTimestamp=t"
        val report = Event.Cilium.NodeImageMissingFixes(listOf("db1"), listOf("/etc/sysctl.d/99-cilium.conf"))
        h.steps.imageDefects["db1"] = ImageDefect("its image lacks 99-cilium.conf", report)

        val result = h.run()

        val reason = checkNotNull(result.failureEvent()).nodes.single().reason
        assertThat(reason)
            .startsWith("its image lacks 99-cilium.conf")
            .contains("removing the old node db1 from the control plane failed: Node db1 (uid u1) was still present")
        assertThat(h.eventsOf<Event.Provision.NodeFailed>().single().reason).doesNotContain("removing the old node")
    }

    @Test
    fun `a cleanup that fails once and then succeeds ends Ready with no replacement`() {
        val h = harness(dbs = 2)
        h.plane.cleanupFlakes["db1"] = 1

        val result = h.run()

        assertThat(result.nodes.getValue("db1")).isInstanceOf(NodeEnd.Ready::class.java)
        assertThat(result.nodes.getValue("db1").replacements).isEqualTo(0)
        assertThat(h.fleet.calls).isEmpty()
        assertThat(h.plane.cleaned).contains("db1")
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
}
