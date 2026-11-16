package com.rustyrazorblade.easydblab.services

/**
 * One kind of observability data `down` saves before teardown. Each signal is saved by its own step,
 * so a failure names the signals that are not saved and the others still are.
 *
 * Only [LOGS] and [METRICS] are ever recorded in the cluster state: their flushes leave Loki and
 * Mimir stopped and cannot run twice. The other signals are saved again on every `down`.
 *
 * @property description the signal as the operator reads it.
 */
enum class TailSignal(
    val description: String,
) {
    LOGS("logs (Loki)"),
    METRICS("metrics (Mimir)"),
    TRACES("traces (Tempo)"),
    PROFILES("profiles (Pyroscope)"),
    ANNOTATIONS("annotations (Grafana)"),
    ;

    companion object {
        /** The signals whose save is recorded, because it stops its backend and cannot run again. */
        val RECORDED: Set<TailSignal> = setOf(LOGS, METRICS)
    }
}
