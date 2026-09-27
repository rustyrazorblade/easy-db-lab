package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.github.resilience4j.retry.Retry
import java.time.Duration
import java.time.Instant

/**
 * Waits before teardown until every span Tempo received is in a block it has uploaded to S3.
 *
 * Tempo 3.0.3 has no flush endpoint, a graceful stop cancels the upload of its last blocks, and a
 * restart deletes a never-completed WAL block. So Tempo is never stopped or restarted; the drain
 * waits for Tempo's own one-minute cut and upload, after the OTel collector (its only sender) is gone:
 *
 * 1. Poll `/metrics` until `tempo_live_store_live_traces` is 0 for every tenant and
 *    `tempo_live_store_traces_created_total` is the same in two readings at least
 *    [Constants.TeardownFlush.TEMPO_STABLE_READING_GAP_SECONDS] apart. The live-traces gauge is set
 *    only at the start of each cut tick, so one reading of 0 can be stale. Both metrics are per
 *    tenant: a Tempo that never received a span has no series of either, which reads as idle. A
 *    reading without [COMPLETE_QUEUE] either is not Tempo's live store, and fails.
 * 2. Poll the control node's disk under [WAL_DIR] until it is drained ([drained]). A cut tick writes
 *    the head block's `meta.json` in the same tick it appends traces, completion renames it to
 *    `meta.deleted.json`, and `flushed` is written only once the block is in the backend.
 *
 * Both waits share one timeout ([FlushTimeouts.tempoDrain]). On timeout the failure reports the live
 * traces and Tempo's failed-flush and failed-completion counters.
 *
 * @property pollInterval the gap between two looks; also the least gap between the two counter readings.
 */
class TempoTailFlush(
    private val http: ObservabilityHttp,
    private val remoteOps: RemoteOperationsService,
    private val timeouts: FlushTimeouts = FlushTimeouts(),
    private val pollInterval: Duration = Duration.ofSeconds(Constants.TeardownFlush.TEMPO_STABLE_READING_GAP_SECONDS),
) : SignalFlush {
    companion object {
        /** Tempo's live-store WAL on the control node: the hostPath of `/var/tempo/live-store/wal`. */
        const val WAL_DIR = "${TempoManifestBuilder.DATA_HOST_PATH}/live-store/wal"
        const val LIVE_TRACES = "tempo_live_store_live_traces"
        const val TRACES_CREATED = "tempo_live_store_traces_created_total"

        /** The live store's completion queue: unlabelled, and exposed from startup, before any tenant exists. */
        const val COMPLETE_QUEUE = "tempo_live_store_complete_queue_length"
        private const val META = "meta.json"
        private const val FLUSHED = "flushed"
        private const val BLOCKS_DIR = "blocks"
        private const val WAL_BLOCK_DEPTH = 2
        private const val LOCAL_BLOCK_DEPTH = 4

        /**
         * Whether the WAL listing [paths] (each relative to [WAL_DIR]) shows every span uploaded: no
         * top-level WAL block holds a `meta.json`, and every `blocks/<tenant>/<id>/` that holds a
         * `meta.json` also holds `flushed`.
         */
        fun drained(paths: List<String>): Boolean = pendingWalBlocks(paths).isEmpty() && unflushedBlocks(paths).isEmpty()

        /** The top-level WAL blocks that still hold a `meta.json`: traces not yet in a complete block. */
        fun pendingWalBlocks(paths: List<String>): List<String> =
            paths
                .map { it.split('/') }
                .filter { it.size == WAL_BLOCK_DEPTH && it[0] != BLOCKS_DIR && it[1] == META }
                .map { it[0] }

        /** The local complete blocks with a `meta.json` but no `flushed` marker: not yet in S3. */
        fun unflushedBlocks(paths: List<String>): List<String> {
            val local = paths.map { it.split('/') }.filter { it.size == LOCAL_BLOCK_DEPTH && it[0] == BLOCKS_DIR }
            val flushed = local.filter { it[3] == FLUSHED }.map { it.take(3).joinToString("/") }.toSet()
            return local.filter { it[3] == META }.map { it.take(3).joinToString("/") }.filterNot { it in flushed }
        }

        /** The local complete blocks that carry their `flushed` marker. */
        fun flushedBlocks(paths: List<String>): Int =
            paths.map { it.split('/') }.count { it.size == LOCAL_BLOCK_DEPTH && it[0] == BLOCKS_DIR && it[3] == FLUSHED }

        /** Each series of [metric] in a Prometheus text exposition, by its label set. */
        fun series(
            exposition: String,
            metric: String,
        ): Map<String, Double> =
            exposition
                .lines()
                .filter { it.startsWith("$metric{") || it.startsWith("$metric ") }
                .associate { line ->
                    line.substringBeforeLast(' ').removePrefix(metric) to (line.substringAfterLast(' ').toDoubleOrNull() ?: Double.NaN)
                }
    }

    /** One reading of Tempo's metrics. */
    private data class Reading(
        val liveTraces: Map<String, Double>,
        val created: Double,
        val failures: Map<String, Double>,
    ) {
        val idle: Boolean get() = liveTraces.values.all { it == 0.0 }
    }

    override fun flush(
        controlHost: ClusterHost,
        clusterState: ClusterState,
        progress: FlushProgress,
    ): SignalReport {
        val deadline = Instant.now().plus(timeouts.tempoDrain)

        progress.begin(FlushStep.TEMPO_LIVE_TRACES)
        var previous: Reading? = null
        val quiet =
            poll(deadline, "tempo-live-traces") {
                val reading = read()
                val stable = previous?.let { it.created == reading.created } ?: false
                previous = reading
                reading to (reading.idle && stable)
            }
        check(quiet.second) { "Tempo still receives or holds traces after ${timeouts.tempoDrain.seconds}s: ${describe(quiet.first)}" }

        progress.begin(FlushStep.TEMPO_BLOCKS_FLUSHED)
        val listing = poll(deadline, "tempo-blocks-flushed") { walListing(controlHost).let { it to drained(it) } }
        check(listing.second) {
            "Tempo has not uploaded every block within ${timeouts.tempoDrain.seconds}s: WAL blocks not yet complete " +
                "${pendingWalBlocks(listing.first)}, local blocks not yet in S3 ${unflushedBlocks(listing.first)}; " +
                runCatching { describe(read()) }.getOrElse { it.message.orEmpty() }
        }
        return SignalReport.Traces(flushedBlocks(listing.first))
    }

    /** Looks with [look] every [pollInterval] until it reports done or [deadline] passes; returns the last look. */
    private fun <T> poll(
        deadline: Instant,
        name: String,
        look: () -> Pair<T, Boolean>,
    ): Pair<T, Boolean> {
        val remaining = Duration.between(Instant.now(), deadline)
        val attempts = (remaining.toMillis() / pollInterval.toMillis().coerceAtLeast(1)).toInt().coerceAtLeast(1) + 1
        val config = RetryUtil.createPollUntilRetryConfig<Pair<T, Boolean>>(attempts, pollInterval, { it.second }, deadline)
        return Retry.decorateSupplier(Retry.of(name, config), look).get()
    }

    private fun read(): Reading {
        val response = http.get(Constants.K8s.TEMPO_PORT, "/metrics")
        check(response.code == Constants.HttpStatus.OK) { "Could not read Tempo's metrics (status ${response.code})" }
        val failureCounters =
            response.body
                .lines()
                .filter { it.startsWith("tempo_live_store_") && it.substringBefore('{').substringBefore(' ').contains("fail") }
                .associate { it.substringBeforeLast(' ') to (it.substringAfterLast(' ').toDoubleOrNull() ?: Double.NaN) }
        val liveTraces = series(response.body, LIVE_TRACES)
        // The live-traces gauge is per tenant, so a Tempo that never received a span has no series:
        // no tenant holds a live trace. Only when the live store's own queue is missing too are the
        // metrics themselves absent, and nothing can be read as idle.
        check(liveTraces.isNotEmpty() || series(response.body, COMPLETE_QUEUE).isNotEmpty()) {
            "Tempo's /metrics has neither a $LIVE_TRACES series nor $COMPLETE_QUEUE, so its live traces cannot be read"
        }
        return Reading(
            liveTraces = liveTraces,
            created = series(response.body, TRACES_CREATED).values.sum(),
            failures = failureCounters,
        )
    }

    private fun describe(reading: Reading): String =
        "live traces ${reading.liveTraces}, traces created ${reading.created}, " +
            "failure counters ${reading.failures}"

    /** Every `meta.json` and `flushed` under [WAL_DIR], relative to it; a missing directory lists nothing. */
    private fun walListing(controlHost: ClusterHost): List<String> =
        remoteOps
            .executeRemotely(
                controlHost.toHost(),
                "sudo sh -c 'if [ -d $WAL_DIR ]; then find $WAL_DIR -mindepth $WAL_BLOCK_DEPTH -maxdepth $LOCAL_BLOCK_DEPTH " +
                    "-type f \\( -name $META -o -name $FLUSHED \\) -printf \"%P\\n\"; fi'",
                output = false,
            ).text
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
