package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.providers.aws.pollUntil
import com.rustyrazorblade.easydblab.services.ObservabilityHttp
import com.rustyrazorblade.easydblab.services.TempoTailFlush
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Waits until the export writer's Tempo has every span it took in a backend block, before the writer
 * is stopped (spike S3): Tempo 3.0.3 has no flush endpoint, and a graceful stop cancels the upload of
 * its last blocks and leaves a partial one. It waits as [TempoTailFlush] does, with its rules:
 *
 * 1. no live traces in any tenant and a created-traces counter that holds across two readings, since
 *    the live-traces gauge is set only at each cut tick;
 * 2. no WAL block not yet completed, and every completed block under `blocks/<tenant>/<id>/` of the
 *    live store's WAL with its `flushed` marker. The WAL is the writer's state in the work directory,
 *    so it is listed here, not over SSH.
 *
 * @property http the writer's backends.
 * @property walDir the live store's WAL directory in the work directory.
 * @property timeout how long both waits may take together.
 * @property pollInterval the gap between two looks.
 */
class WriterTempoDrain(
    private val http: ObservabilityHttp,
    private val walDir: File,
    private val timeout: Duration = Duration.ofMinutes(Constants.Export.Query.TEMPO_DRAIN_TIMEOUT_MINUTES),
    private val pollInterval: Duration = Duration.ofSeconds(1),
) {
    private companion object {
        val NAMES = setOf("meta.json", "flushed")
        const val MAX_DEPTH = 4
    }

    /** Waits for both; fails naming what is still held when [timeout] passes. */
    fun await() {
        val deadline = Instant.now().plus(timeout)
        var previous: Double? = null
        val quiet =
            poll(deadline, "export writer Tempo idle") {
                val reading = read()
                val stable = previous == reading.second
                previous = reading.second
                reading to (reading.first && stable)
            }
        check(quiet.second) { "The export writer's Tempo still holds live traces after $timeout" }
        val listing = poll(deadline, "export writer Tempo blocks flushed") { listing().let { it to TempoTailFlush.drained(it) } }
        check(listing.second) {
            "The export writer's Tempo has not written every block within $timeout: WAL blocks not yet complete " +
                "${TempoTailFlush.pendingWalBlocks(listing.first)}, blocks not yet flushed ${TempoTailFlush.unflushedBlocks(listing.first)}"
        }
    }

    /** Whether no tenant holds a live trace, and the created-traces count. */
    private fun read(): Pair<Boolean, Double> {
        val response = http.get(Constants.K8s.TEMPO_PORT, "/metrics")
        check(response.code == Constants.HttpStatus.OK) { "Could not read the export writer Tempo's metrics (status ${response.code})" }
        val live = TempoTailFlush.series(response.body, TempoTailFlush.LIVE_TRACES)
        return live.values.all { it == 0.0 } to TempoTailFlush.series(response.body, TempoTailFlush.TRACES_CREATED).values.sum()
    }

    /** Every `meta.json` and `flushed` under the WAL, relative to it, as `TempoTailFlush.drained` reads them. */
    private fun listing(): List<String> =
        walDir
            .walkTopDown()
            .maxDepth(MAX_DEPTH)
            .filter { it.isFile && it.name in NAMES }
            .map { it.relativeTo(walDir).invariantSeparatorsPath }
            .toList()

    private fun <T> poll(
        deadline: Instant,
        name: String,
        look: () -> Pair<T, Boolean>,
    ): Pair<T, Boolean> {
        val remaining = Duration.between(Instant.now(), deadline)
        val attempts = (remaining.toMillis() / pollInterval.toMillis().coerceAtLeast(1)).toInt().coerceAtLeast(1) + 1
        return pollUntil(name, attempts, pollInterval, deadline, done = { it.second }, poll = look)
    }
}
