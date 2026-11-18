package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.events.Event
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Where every node is, for the progress line `up` prints every `PROGRESS_INTERVAL_SECONDS`. Each
 * pipeline reports the step it enters, and its end as Ready or failed; [snapshot] counts them.
 * A node in [PipelineStep.JOIN], or in [PipelineStep.NODE_CLEANUP] (it holds a join slot too), is counted as joining, against the join limit, not in `byStep`.
 * The control node is not one of the [total]; its current step is shown on its own.
 *
 * Thread-safe: every pipeline reports from its own thread.
 */
class BringUpProgress(
    private val total: Int,
    private val joinLimit: Int,
    private val clock: Clock = Clock.systemUTC(),
    private val startedAt: Instant = clock.instant(),
) {
    private val lock = ReentrantLock()
    private val current = mutableMapOf<String, State>()
    private var replacements = 0
    private var controlPlane = Event.Provision.ControlPlaneState.STARTING
    private var controlStep: PipelineStep? = null

    /** A node's place: in a step, or at its end. */
    private sealed interface State {
        data class In(
            val step: PipelineStep,
        ) : State

        data object Ready : State

        data object Failed : State
    }

    /** [alias] entered [step]. */
    fun enter(
        alias: String,
        step: PipelineStep,
    ) = lock.withLock { current[alias] = State.In(step) }

    /** [alias] is Ready. */
    fun ready(alias: String) = lock.withLock { current[alias] = State.Ready }

    /** [alias] failed for good. */
    fun failed(alias: String) = lock.withLock { current[alias] = State.Failed }

    /** A node was replaced. */
    fun replaced() = lock.withLock { replacements++ }

    /** The control node entered [step], or finished its pipeline when [step] is null. */
    fun controlEntered(step: PipelineStep?) = lock.withLock { controlStep = step }

    /** The control plane's state changed to [state]. */
    fun controlPlane(state: Event.Provision.ControlPlaneState) = lock.withLock { controlPlane = state }

    /** The progress event for now. */
    fun snapshot(): Event.Provision.BringUpProgress =
        lock.withLock {
            val states = current.values
            val steps = states.filterIsInstance<State.In>().map { it.step }
            Event.Provision.BringUpProgress(
                elapsedSeconds = Duration.between(startedAt, clock.instant()).toSeconds(),
                total = total,
                byStep =
                    PipelineStep.entries
                        .filter { it !in HOLDING_A_JOIN_SLOT }
                        .associate { step -> step.wireName to steps.count { it == step } }
                        .filterValues { it > 0 },
                joining = steps.count { it in HOLDING_A_JOIN_SLOT },
                joinLimit = joinLimit,
                ready = states.count { it == State.Ready },
                replacements = replacements,
                failed = states.count { it == State.Failed },
                controlPlane = controlPlane,
                controlStep = controlStep?.wireName,
            )
        }

    private companion object {
        /** The steps a node runs while it holds one of the join slots. */
        val HOLDING_A_JOIN_SLOT = setOf(PipelineStep.NODE_CLEANUP, PipelineStep.JOIN)
    }
}
