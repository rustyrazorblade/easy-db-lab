package com.rustyrazorblade.easydblab.services.bringup

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

/**
 * The gate's decisions over a reader the test sets up by hand: it opens only when the API server,
 * the control node and every network condition hold, reads nothing else while the API server is
 * down, and names the conditions still false when it times out.
 */
class ControlPlaneGateTest {
    /** A control plane the test sets up by hand; [network] is the plugin's conditions. */
    private class FakeReader : ControlPlaneReader {
        var apiReady = true
        val nodes = mutableSetOf("control0")
        val network = NETWORK.associateWith { true }.toMutableMap()
        var looks = 0
        var networkReads = 0

        override fun apiServerReady(): Boolean = apiReady.also { looks++ }

        override fun nodeRegistered(alias: String) = alias in nodes

        override fun networkConditions(): Map<String, Boolean> = network.toMap().also { networkReads++ }
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
        r: ControlPlaneReader = reader,
        timeout: Duration = Duration.ofSeconds(5),
    ) = ControlPlaneGate(r, eventBus, "control0", timeout, interval = Duration.ZERO)

    @Test
    fun `the gate opens only when every condition holds`() {
        // Every single false condition keeps the gate shut; seeded random subsets of them as well.
        val breakers: List<(FakeReader) -> Unit> =
            listOf<(FakeReader) -> Unit>({ it.apiReady = false }, { it.nodes.clear() }) +
                NETWORK.map { name -> { r: FakeReader -> r.network[name] = false } }
        breakers.forEach { breaker ->
            assertThat(gate(FakeReader().also(breaker)).look().open).isFalse()
        }
        repeat(100) { seed ->
            val random = Random(seed)
            val chosen = breakers.filter { random.nextInt(4) == 0 }
            val r = FakeReader().also { fake -> chosen.forEach { it(fake) } }
            assertThat(gate(r).look().open).isEqualTo(chosen.isEmpty())
        }
        assertThat(gate().look().open).isTrue()
    }

    @Test
    fun `a network plugin with no conditions needs only the API server and the control node`() {
        reader.network.clear()

        val look = gate().look()

        assertThat(look.open).isTrue()
        assertThat(look.conditions.keys).containsExactly("API server ready", "control0 registered")
    }

    @Test
    fun `while the API server is not ready no other condition is read, and each reads false`() {
        reader.apiReady = false

        val look = gate().look()

        assertThat(reader.networkReads).isZero()
        assertThat(look.falseConditions).containsExactly("API server ready", "control0 registered")
    }

    @Test
    fun `an open gate emits one event naming every condition that held`() {
        gate().await()

        assertThat(events).containsExactly(
            Event.Provision.ControlPlaneGateOpen(0, listOf("API server ready", "control0 registered") + NETWORK),
        )
    }

    @Test
    fun `the gate waits through looks that are not ready yet`() {
        val last = NETWORK.last()
        reader.network[last] = false
        val slow =
            object : ControlPlaneReader by reader {
                override fun networkConditions(): Map<String, Boolean> {
                    if (reader.looks >= 3) reader.network[last] = true
                    return reader.networkConditions()
                }
            }

        gate(slow).await()

        assertThat(reader.looks).isGreaterThanOrEqualTo(3)
        assertThat(events.single()).isInstanceOf(Event.Provision.ControlPlaneGateOpen::class.java)
    }

    @Test
    fun `a gate that never opens times out naming only the conditions still false`() {
        reader.network[NETWORK[0]] = false
        reader.network[NETWORK[2]] = false

        assertThatThrownBy { gate(timeout = Duration.ofMillis(200)).await() }
            .isInstanceOf(ControlPlaneGateTimedOutException::class.java)
            .hasMessageContaining(NETWORK[0])
            .hasMessageContaining(NETWORK[2])
            .hasMessageNotContaining("API server")
            .hasMessageNotContaining(NETWORK[1])

        val timedOut = events.filterIsInstance<Event.Provision.ControlPlaneGateTimedOut>().single()
        assertThat(timedOut.conditions).containsExactly(NETWORK[0], NETWORK[2])
        assertThat(events).noneMatch { it is Event.Provision.ControlPlaneGateOpen }
    }

    private companion object {
        val NETWORK = listOf("operator ready", "CRD a established", "CRD b established")
    }
}
