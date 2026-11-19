package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.output.NodeStream
import com.rustyrazorblade.easydblab.services.bringup.BringUpHarness.Companion.hang
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * What a control node failure does to the bring-up (design D7): it is reported once with the
 * control node's output, every node pipeline is stopped and none is replaced, and `run` does not
 * return while a stopped pipeline is still finishing.
 */
class ControlNodeFailureBringUpTest {
    @TempDir
    lateinit var dir: File

    private fun harness(dbs: Int) = BringUpHarness(dir, dbs)

    /**
     * A stopped pipeline's future is done as soon as it is cancelled, but its thread may still be
     * finishing its step, writing the state among other things. `run` waits for that thread, so
     * nothing the bring-up does lands after it returned (a test would see its directory written
     * while JUnit deletes it; `up` would go on while a node still writes the state).
     */
    @Test
    fun `a control node failure returns only after every stopped pipeline has finished`() {
        val h = harness(dbs = 2)
        val setupStarted = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        h.steps.scripts["db1 setup"] = { _, _ ->
            setupStarted.countDown()
            try {
                hang()
            } finally {
                // A step that takes a moment to stop after its interrupt, as an SSH call can.
                val until = System.nanoTime() + STOP_TAKES.toNanos()
                while (System.nanoTime() < until) Thread.onSpinWait()
                finished.set(true)
            }
        }
        h.steps.scripts["control0 control-plane"] = { _, _ ->
            setupStarted.await()
            error("k3s.service failed")
        }

        assertThatThrownBy { h.run() }.isInstanceOf(ControlNodeFailedException::class.java)

        assertThat(finished).isTrue()
    }

    /**
     * Work outside a step, a replacement's terminate here, has no step grace. A pipeline that
     * ignores its interrupt there is named by its node once the pipeline grace has passed, and
     * `run` returns without it.
     */
    @Test
    fun `a pipeline still running after the stop grace is reported by its node, and run returns`() {
        val h = harness(dbs = 2)
        h.settings = h.settings.copy(timing = h.settings.timing.copy(pipelineStopGrace = Duration.ofMillis(SETTLE_MS)))
        val terminating = CountDownLatch(1)
        val release = AtomicBoolean(false)
        val stuck = AtomicReference<Thread>()
        h.steps.scripts["db1 setup"] = { _, _ -> error("disk full") }
        h.fleet.onTerminate = {
            stuck.set(Thread.currentThread())
            terminating.countDown()
            // A provider call that ignores its interrupt.
            while (!release.get()) Thread.onSpinWait()
        }
        h.steps.scripts["control0 control-plane"] = { _, _ ->
            check(terminating.await(BringUpHarness.WAIT.toMillis(), TimeUnit.MILLISECONDS)) { "db1 was never terminated" }
            error("k3s.service failed")
        }

        try {
            assertThatThrownBy { h.run() }.isInstanceOf(ControlNodeFailedException::class.java)

            assertThat(h.eventsOf<Event.Provision.PipelinesAbandoned>())
                .containsExactly(Event.Provision.PipelinesAbandoned(listOf("db1"), SETTLE_MS))
        } finally {
            // Let it end before JUnit deletes the directory it saves the state in.
            release.set(true)
            stuck.get()?.join()
        }
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
    fun `a failed control node carries the control node's last output`() {
        val h = harness(dbs = 2)
        h.steps.scripts["db1 setup"] = { host, _ -> h.nodeOutput.line(host.alias, NodeStream.Stdout, "db1 is fine") }
        h.steps.scripts["control0 control-plane"] = { host, _ ->
            h.nodeOutput.line(host.alias, NodeStream.Stderr, "k3s: bad flag")
            error("k3s.service failed")
        }

        assertThatThrownBy { h.run() }.isInstanceOf(ControlNodeFailedException::class.java)

        assertThat(h.eventsOf<Event.Provision.ControlNodeFailed>().single().outputTail)
            .containsExactly("stderr: k3s: bad flag", "control-plane attempt 2", "stderr: k3s: bad flag")
    }

    private companion object {
        const val SETTLE_MS = 100L
        val STOP_TAKES: Duration = Duration.ofMillis(300)
    }
}
