package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

class ControlPlaneGateTest {
    /** A control plane the test sets up by hand. */
    private class FakeReader : ControlPlaneReader {
        var apiReady = true
        val nodes = mutableSetOf("control0")
        var operator: ControlPlaneReader.Replicas? = ControlPlaneReader.Replicas(1, 1)
        val established = Constants.Cilium.REQUIRED_CRDS.toMutableSet()
        var looks = 0

        override fun apiServerReady(): Boolean = apiReady.also { looks++ }

        override fun nodeRegistered(alias: String) = alias in nodes

        override fun operatorReplicas() = operator

        override fun establishedCrds(names: List<String>) = names.filter { it in established }.toSet()
    }

    private val reader = FakeReader()
    private val events = CopyOnWriteArrayList<Event>()
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

    private fun gate(
        cni: CniMode = CniMode.Cilium,
        timeout: Duration = Duration.ofSeconds(5),
    ) = ControlPlaneGate(reader, eventBus, cni, "control0", timeout, interval = Duration.ZERO)

    @Test
    fun `under Cilium the gate opens only when every condition holds`() {
        val crds = Constants.Cilium.REQUIRED_CRDS
        // Every single missing condition keeps the gate shut; seeded random subsets of conditions as well.
        val breakers: List<(FakeReader) -> Unit> =
            listOf<(FakeReader) -> Unit>(
                { it.apiReady = false },
                { it.nodes.clear() },
                { it.operator = null },
                { it.operator = ControlPlaneReader.Replicas(0, 1) },
                { it.operator = ControlPlaneReader.Replicas(1, 2) },
                { it.operator = ControlPlaneReader.Replicas(0, 0) },
            ) + crds.map { crd -> { r: FakeReader -> r.established.remove(crd) } }
        breakers.forEach { breaker ->
            val r = FakeReader().also(breaker)
            assertThat(ControlPlaneGate(r, eventBus, CniMode.Cilium, "control0").look().open).isFalse()
        }
        repeat(100) { seed ->
            val random = Random(seed)
            val chosen = breakers.filter { random.nextInt(4) == 0 }
            val r = FakeReader().also { fake -> chosen.forEach { it(fake) } }
            assertThat(ControlPlaneGate(r, eventBus, CniMode.Cilium, "control0").look().open).isEqualTo(chosen.isEmpty())
        }
        assertThat(gate().look().open).isTrue()
    }

    @Test
    fun `under Flannel only the API server and the control node count`() {
        reader.operator = null
        reader.established.clear()

        val look = gate(CniMode.Flannel).look()

        assertThat(look.open).isTrue()
        assertThat(look.conditions.keys).containsExactly("API server ready", "control0 registered")
    }

    @Test
    fun `an open gate emits the operator and the gate events once`() {
        gate().await()

        assertThat(events).containsExactly(
            Event.Cilium.OperatorReady(1, Constants.Cilium.REQUIRED_CRDS.size),
            Event.K3s.ControlPlaneGateOpen(0),
        )
    }

    @Test
    fun `the gate waits through looks that are not ready yet`() {
        val last = Constants.Cilium.REQUIRED_CRDS.last()
        reader.established.remove(last)
        val slow =
            object : ControlPlaneReader by reader {
                override fun establishedCrds(names: List<String>): Set<String> {
                    if (reader.looks >= 3) reader.established += last
                    return reader.establishedCrds(names)
                }
            }

        ControlPlaneGate(slow, eventBus, CniMode.Cilium, "control0", Duration.ofSeconds(5), Duration.ZERO).await()

        assertThat(reader.looks).isGreaterThanOrEqualTo(3)
        assertThat(events.last()).isInstanceOf(Event.K3s.ControlPlaneGateOpen::class.java)
    }

    @Test
    fun `a gate that never opens times out naming only the conditions still false`() {
        reader.operator = ControlPlaneReader.Replicas(0, 1)
        reader.established.remove("ciliumnodes.cilium.io")

        assertThatThrownBy { gate(timeout = Duration.ofMillis(200)).await() }
            .isInstanceOf(ControlPlaneGateTimedOutException::class.java)
            .hasMessageContaining("cilium-operator ready")
            .hasMessageContaining("CRD ciliumnodes.cilium.io established")
            .hasMessageNotContaining("API server")
            .hasMessageNotContaining("ciliumidentities")

        val timedOut = events.filterIsInstance<Event.K3s.ControlPlaneGateTimedOut>().single()
        assertThat(timedOut.conditions).containsExactly("cilium-operator ready", "CRD ciliumnodes.cilium.io established")
        assertThat(events).noneMatch { it is Event.K3s.ControlPlaneGateOpen }
    }
}
