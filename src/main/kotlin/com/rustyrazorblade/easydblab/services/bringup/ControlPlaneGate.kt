package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import java.time.Clock
import java.time.Duration

/**
 * What the control-plane gate reads (design D3). A port, so the gate's decisions are tested with
 * a fake; [Fabric8ControlPlaneReader] reads a real API server.
 */
interface ControlPlaneReader {
    /** Whether `/readyz` answers 200. */
    fun apiServerReady(): Boolean

    /** Whether a Node named [alias] exists. */
    fun nodeRegistered(alias: String): Boolean

    /** The ready replicas of the Cilium operator Deployment, and how many it wants; null when it does not exist. */
    fun operatorReplicas(): Replicas?

    /** Which of [names] exist with the `Established` condition `True`. */
    fun establishedCrds(names: List<String>): Set<String>

    /** A Deployment's ready and desired replicas. */
    data class Replicas(
        val ready: Int,
        val desired: Int,
    )
}

/**
 * Holds every database and application node's join until the control plane can take it: the API
 * server is ready, the control node is registered, and under Cilium the operator is ready and
 * every CRD the agent waits for at start is `Established` (design D3). In #1134 the operator was
 * Running while its CRD creation timed out, so the CRDs are checked as well as the operator.
 *
 * [await] polls every [interval] until all hold, emits `Cilium.OperatorReady` (under Cilium) and
 * `K3s.ControlPlaneGateOpen`, and returns. Past [timeout] it emits `K3s.ControlPlaneGateTimedOut`
 * naming each condition still false, and throws: the control node has failed (D7).
 */
class ControlPlaneGate(
    private val reader: ControlPlaneReader,
    private val eventBus: EventBus,
    private val cni: CniMode,
    private val controlAlias: String,
    private val timeout: Duration = Duration.ofSeconds(Constants.Scale.GATE_TIMEOUT_SECONDS),
    private val interval: Duration = Duration.ofSeconds(Constants.Scale.GATE_POLL_INTERVAL_SECONDS),
    private val clock: Clock = Clock.systemUTC(),
) {
    /** One look at every condition: each name, with whether it holds. */
    data class Look(
        val conditions: Map<String, Boolean>,
        val operator: ControlPlaneReader.Replicas?,
    ) {
        val open: Boolean get() = conditions.values.all { it }
        val falseConditions: List<String> get() = conditions.filterValues { !it }.keys.toList()
    }

    /** Waits for the gate to open; throws [ControlPlaneGateTimedOutException] past the timeout. */
    fun await() {
        val began = clock.instant()
        val deadline = began.plus(timeout)
        val config =
            RetryConfig
                .custom<Look>()
                .maxAttempts(Int.MAX_VALUE)
                .intervalFunction { _ -> interval.toMillis() }
                .retryOnResult { !it.open && clock.instant().isBefore(deadline) }
                .build()
        val last = Retry.decorateSupplier(Retry.of("control-plane-gate", config), ::look).get()
        val waited = Duration.between(began, clock.instant()).toSeconds()
        if (!last.open) {
            eventBus.emit(Event.K3s.ControlPlaneGateTimedOut(waited, last.falseConditions))
            throw ControlPlaneGateTimedOutException(waited, last.falseConditions)
        }
        last.operator?.let { eventBus.emit(Event.Cilium.OperatorReady(it.ready, Constants.Cilium.REQUIRED_CRDS.size)) }
        eventBus.emit(Event.K3s.ControlPlaneGateOpen(waited))
    }

    /** Reads every condition once. */
    fun look(): Look {
        val apiReady = reader.apiServerReady()
        val base =
            mapOf(
                "API server ready" to apiReady,
                "$controlAlias registered" to (apiReady && reader.nodeRegistered(controlAlias)),
            )
        if (cni != CniMode.Cilium) return Look(base, operator = null)
        val operator = if (apiReady) reader.operatorReplicas() else null
        val established = if (apiReady) reader.establishedCrds(Constants.Cilium.REQUIRED_CRDS) else emptySet()
        val operatorReady = operator != null && operator.desired > 0 && operator.ready >= operator.desired
        val cilium =
            mapOf("${Constants.Cilium.OPERATOR_DEPLOYMENT} ready" to operatorReady) +
                Constants.Cilium.REQUIRED_CRDS.associate { "CRD $it established" to (it in established) }
        return Look(base + cilium, operator.takeIf { operatorReady })
    }
}

/** The control-plane gate did not open in time: the control node has failed. */
class ControlPlaneGateTimedOutException(
    val waitedSeconds: Long,
    val conditions: List<String>,
) : IllegalStateException(
        "Control plane not ready after ${waitedSeconds}s; still false: ${conditions.joinToString(", ")}",
    )
