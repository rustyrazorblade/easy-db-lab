package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.Constants
import java.time.Duration
import java.time.Instant

/**
 * The control plane's health, decided from one sample at a time. A sample past either limit, or
 * one that could not reach the API server, makes it unhealthy; [recoverySamples] healthy samples
 * in a row make it healthy again. Unhealthy for [unhealthyTimeout] is a control node failure.
 *
 * Pure: it holds no thread and reads no clock. `ControlPlaneHealthMonitor` feeds it samples and
 * acts on the [Transition] each one returns. It is used from one thread only.
 */
class ControlPlaneHealth(
    val latencyLimitMs: Long = Constants.Scale.HEALTH_LATENCY_LIMIT_MS,
    val rejectionLimit: Long = Constants.Scale.HEALTH_REJECTION_LIMIT,
    private val recoverySamples: Int = Constants.Scale.HEALTH_RECOVERY_SAMPLES,
    private val unhealthyTimeout: Duration = Duration.ofSeconds(Constants.Scale.HEALTH_UNHEALTHY_TIMEOUT_SECONDS),
) {
    /** One look at the API server. */
    sealed interface Sample {
        /** A minimal `get nodes` took [latencyMs], and [rejections] requests were rejected since the last sample. */
        data class Reading(
            val latencyMs: Long,
            val rejections: Long,
        ) : Sample

        /** The API server could not be reached. */
        data class Unreachable(
            val reason: String,
        ) : Sample
    }

    /** What one sample changed. */
    sealed interface Transition {
        /** Nothing to report. */
        data object None : Transition

        /** The control plane was healthy and [sample] is not. */
        data class BecameUnhealthy(
            val sample: Sample,
        ) : Transition

        /** The control plane is healthy again after [unhealthyFor]. */
        data class BecameHealthy(
            val unhealthyFor: Duration,
        ) : Transition

        /** Unhealthy for the whole timeout; [sample] is the last one. */
        data class TimedOut(
            val sample: Sample,
        ) : Transition
    }

    /** Whether the control plane is healthy now. */
    var healthy: Boolean = true
        private set

    private var unhealthySince: Instant? = null
    private var healthyRun = 0

    /** Records [sample], taken at [now], and returns what it changed. */
    fun record(
        sample: Sample,
        now: Instant,
    ): Transition {
        val since = unhealthySince
        return when {
            isBad(sample) -> {
                healthyRun = 0
                when {
                    since == null -> {
                        healthy = false
                        unhealthySince = now
                        Transition.BecameUnhealthy(sample)
                    }
                    Duration.between(since, now) >= unhealthyTimeout -> Transition.TimedOut(sample)
                    else -> Transition.None
                }
            }
            since == null -> Transition.None
            ++healthyRun >= recoverySamples -> {
                healthy = true
                unhealthySince = null
                healthyRun = 0
                Transition.BecameHealthy(Duration.between(since, now))
            }
            else -> Transition.None
        }
    }

    private fun isBad(sample: Sample): Boolean =
        when (sample) {
            is Sample.Unreachable -> true
            is Sample.Reading -> sample.latencyMs > latencyLimitMs || sample.rejections > rejectionLimit
        }
}
