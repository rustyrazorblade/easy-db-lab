package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import java.time.Clock
import java.time.Duration

/**
 * What the control-plane gate reads (design D3). A port, so the gate's decisions are tested with
 * a fake; the K3s adapter reads a real API server and knows what its network plugin needs.
 */
interface ControlPlaneReader {
    /** Whether the API server reports itself ready. */
    fun apiServerReady(): Boolean

    /** Whether a node named [alias] is registered. */
    fun nodeRegistered(alias: String): Boolean

    /**
     * Each condition the network plugin needs before a node may join, by name, with whether it
     * holds now; empty when the plugin needs none. Read only once the API server is ready.
     */
    fun networkConditions(): Map<String, Boolean>
}

/**
 * Holds every database and application node's join until the control plane can take it: the API
 * server is ready, the control node is registered, and every condition the network plugin names
 * holds (design D3). Under Cilium those are the operator and every CRD its agents wait for at
 * start; in #1134 the operator was Running while its CRD creation timed out.
 *
 * [await] polls every [interval] until all hold, emits `Provision.ControlPlaneGateOpen`, and
 * returns. Past [timeout] it emits `Provision.ControlPlaneGateTimedOut` naming each condition still
 * false, and throws: the control node has failed (D7).
 */
class ControlPlaneGate(
    private val reader: ControlPlaneReader,
    private val eventBus: EventBus,
    private val controlAlias: String,
    private val timeout: Duration = Duration.ofSeconds(Constants.Scale.GATE_TIMEOUT_SECONDS),
    private val interval: Duration = Duration.ofSeconds(Constants.Scale.GATE_POLL_INTERVAL_SECONDS),
    private val clock: Clock = Clock.systemUTC(),
) {
    /** One look at every condition: each name, with whether it holds. */
    data class Look(
        val conditions: Map<String, Boolean>,
    ) {
        val open: Boolean get() = conditions.values.all { it }
        val falseConditions: List<String> get() = conditions.filterValues { !it }.keys.toList()
    }

    /** Waits for the gate to open; throws [ControlPlaneGateTimedOutException] past the timeout. */
    fun await() {
        val began = clock.instant()
        val last = pollUntil("control-plane-gate", interval, timeout, clock, done = { it.open }, look = ::look)
        val waited = Duration.between(began, clock.instant()).toSeconds()
        if (!last.open) {
            eventBus.emit(Event.Provision.ControlPlaneGateTimedOut(waited, last.falseConditions))
            throw ControlPlaneGateTimedOutException(waited, last.falseConditions)
        }
        eventBus.emit(Event.Provision.ControlPlaneGateOpen(waited, last.conditions.keys.toList()))
    }

    /** Reads every condition once; while the API server is not ready, every other one reads false. */
    fun look(): Look {
        val apiReady = reader.apiServerReady()
        val registered = "$controlAlias registered" to (apiReady && reader.nodeRegistered(controlAlias))
        val network = if (apiReady) reader.networkConditions() else emptyMap()
        return Look(mapOf(API_READY to apiReady, registered) + network)
    }

    private companion object {
        const val API_READY = "API server ready"
    }
}

/** The control-plane gate did not open in time: the control node has failed. */
class ControlPlaneGateTimedOutException(
    val waitedSeconds: Long,
    val conditions: List<String>,
) : IllegalStateException(
        "Control plane not ready after ${waitedSeconds}s; still false: ${conditions.joinToString(", ")}",
    )
