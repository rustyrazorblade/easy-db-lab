package com.rustyrazorblade.easydblab.services.metrics

/**
 * Whether each streamed metric is failing, so the stream reports a failure once and a recovery
 * once instead of every cycle.  Every metric starts healthy.
 *
 * One stream owns one instance and calls it from the command thread only.
 */
class MetricHealth {
    private val failing = mutableSetOf<StreamMetric>()

    /** What changed for [metric] after one cycle's [outcome]. */
    sealed interface Transition {
        /** Nothing to report. */
        data object None : Transition

        /** The metric started failing for [reason]. */
        data class Failed(
            val reason: String,
        ) : Transition

        /** The metric succeeded after failing. */
        data object Recovered : Transition
    }

    /** Records [outcome] for [metric] and returns the change to report. */
    fun record(
        metric: StreamMetric,
        outcome: Result<*>,
    ): Transition =
        outcome.fold(
            onSuccess = { if (failing.remove(metric)) Transition.Recovered else Transition.None },
            onFailure = { error ->
                if (failing.add(metric)) Transition.Failed(reasonOf(error)) else Transition.None
            },
        )

    private fun reasonOf(error: Throwable): String = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
}
