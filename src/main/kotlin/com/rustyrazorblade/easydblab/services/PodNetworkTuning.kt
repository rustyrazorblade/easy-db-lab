package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.PodNetworkSysctlPolicyBuilder
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.reason
import com.rustyrazorblade.easydblab.runCatchingInterruptibly
import com.rustyrazorblade.easydblab.services.bringup.pollUntilDeadline
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Puts the pod network sysctls in effect for every later pod, once per `up` (pod-network-tuning,
 * designs D1 and D5).
 *
 * It applies the cluster-wide admission policy, then asks the API server for a dry-run pod until
 * that pod carries every injected value. The API server loads a new policy asynchronously, so the
 * first pods after the apply can still be untuned; waiting here means no kit pod ever is. Past
 * [readyTimeout] `up` fails, naming the keys the pod still lacks. Each probe gets only the time left,
 * so a probe that never answers cannot stretch the wait past [readyTimeout].
 *
 * @param sysctls the keys and values every pod-network pod gets, from [PodNetworkSysctls]
 */
class PodNetworkTuning(
    private val manifests: K8sManifestOperations,
    private val probe: PodSysctlProbe,
    private val eventBus: EventBus,
    private val sysctls: Map<String, String> = PodNetworkSysctls.packaged(),
    private val readyTimeout: Duration = Duration.ofSeconds(Constants.NodeTuning.POD_SYSCTL_POLICY_READY_SECONDS),
    private val pollInterval: Duration = Duration.ofSeconds(Constants.NodeTuning.POD_SYSCTL_POLICY_POLL_SECONDS),
) {
    /**
     * Applies the policy and waits until it is in effect.
     *
     * @throws CommandFailedException when the policy cannot be applied, or a dry-run pod still lacks
     *   a value after [readyTimeout]
     */
    fun apply() {
        PodNetworkSysctlPolicyBuilder(sysctls).buildAll().forEach { resource ->
            manifests.applyResource(resource).getOrElse { failure ->
                val reason = failure.reason()
                eventBus.emit(Event.Platform.PodSysctlPolicyNotApplied(Constants.NodeTuning.POD_SYSCTL_POLICY_NAME, reason))
                throw CommandFailedException("The pod network sysctl policy could not be applied: $reason", failure)
            }
        }
        val last = pollProbe().getOrElse { probeFailed(it) }
        val missing = missing(last)
        if (missing.isNotEmpty()) notInEffect(missing, reasonOf(last))
        eventBus.emit(Event.Platform.PodSysctlPolicyReady(Constants.NodeTuning.POD_SYSCTL_POLICY_NAME, sysctls))
    }

    /**
     * Probes until a pod carries every value or [readyTimeout] has passed, and returns the last
     * result; a failure is what a probe threw. An interrupt propagates.
     */
    private fun pollProbe(): Result<ProbeResult> {
        // One deadline bounds both the poll and each probe, so the two cannot disagree on the time left.
        val deadline = Instant.now().plus(readyTimeout)
        val probes = Executors.newVirtualThreadPerTaskExecutor()
        // A probe cut off at the deadline adds nothing, so the last answer the server gave stands.
        var answered: ProbeResult? = null
        return try {
            runCatchingInterruptibly {
                pollUntilDeadline<ProbeResult>(
                    "pod-sysctl-policy",
                    pollInterval,
                    deadline,
                    done = { missing(it).isEmpty() },
                ) {
                    probeBefore(probes, deadline)?.also { answered = it }
                        ?: answered
                        ?: ProbeResult.Unreachable("the API server gave no answer before the ${readyTimeout.seconds}s deadline")
                }
            }
        } finally {
            // shutdownNow, not close: close would wait for a probe that never answers.
            probes.shutdownNow()
        }
    }

    /**
     * One probe, given only the time left before [deadline], so a probe that never answers (a silent
     * tunnel, an API server that accepts but does not reply) cannot hold `up` past [readyTimeout]: it
     * is interrupted and the result is null. What the probe throws is rethrown (see [probeFailure]).
     */
    private fun probeBefore(
        probes: ExecutorService,
        deadline: Instant,
    ): ProbeResult? {
        val left = Duration.between(Instant.now(), deadline).toNanos()
        if (left <= 0L) return null
        val answer = probes.submit<ProbeResult> { probe.probe() }
        return try {
            answer.get(left, TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            answer.cancel(true)
            null
        } catch (e: ExecutionException) {
            throw probeFailure(e)
        }
    }

    /**
     * What a probe threw, to rethrow on the caller's thread. An interrupt on the probe's own thread is
     * not the user stopping `up`, so it becomes a probe failure instead of an [InterruptedException].
     */
    private fun probeFailure(e: ExecutionException): Throwable =
        when (val cause = e.cause) {
            null -> e
            is InterruptedException -> IllegalStateException("the probe was interrupted on its own thread", cause)
            else -> cause
        }

    /** Reports that a new pod still lacks [missing], and why when no pod was admitted, then fails `up`. */
    private fun notInEffect(
        missing: List<String>,
        reason: String?,
    ): Nothing {
        eventBus.emit(
            Event.Platform.PodSysctlPolicyNotInEffect(Constants.NodeTuning.POD_SYSCTL_POLICY_NAME, missing, readyTimeout.seconds, reason),
        )
        throw CommandFailedException(
            "The pod network sysctl policy was not in effect after ${readyTimeout.seconds}s; a new pod lacks $missing" +
                reason?.let { " ($it)" }.orEmpty(),
        )
    }

    /** Reports a probe that threw (the poll does not retry an exception), then fails `up` with it as the cause. */
    private fun probeFailed(failure: Throwable): Nothing {
        val reason = failure.reason()
        eventBus.emit(Event.Platform.PodSysctlProbeFailed(Constants.NodeTuning.POD_SYSCTL_POLICY_NAME, reason))
        throw CommandFailedException("The pod network sysctl policy probe failed: $reason", failure)
    }

    /** The injected keys [result] does not carry with the injected value; every key when no pod was admitted. */
    private fun missing(result: ProbeResult): List<String> =
        when (result) {
            is ProbeResult.Refused, is ProbeResult.Unreachable -> sysctls.keys.toList()
            is ProbeResult.Admitted -> sysctls.filter { (key, value) -> result.sysctls[key] != value }.keys.toList()
        }

    /** Why the last probe admitted no pod, or null when it did. */
    private fun reasonOf(result: ProbeResult): String? =
        when (result) {
            is ProbeResult.Refused -> result.reason
            is ProbeResult.Unreachable -> result.reason
            is ProbeResult.Admitted -> null
        }
}
