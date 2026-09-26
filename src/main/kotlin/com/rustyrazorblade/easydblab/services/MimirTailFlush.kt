package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Flushes Mimir before teardown and proves its head became blocks that are in S3.
 *
 * 1. Scrape the failed-compaction counter and the head's newest timestamp.
 * 2. `POST /ingester/shutdown`: compacts every tenant's head into a block and ships it. It answers
 *    204 whatever happened, so the next two steps are the proof.
 * 3. The failed-compaction counter did not move, and some local block's `maxTime` covers the head's
 *    newest sample (a head with no samples needs no block).
 * 4. Every block the shipper uploads (samples, compaction level 1) has its `meta.json` in S3 under
 *    `observabilitymetrics/<tenant>/<block>/` — the shipper writes it last.
 * 5. Scale Mimir to 0.
 *
 * Each step is recorded in the [FlushProgress] before it runs, and so is each change to Mimir's
 * state, so a failure names the step and leaves Mimir as that step left it.
 */
class MimirTailFlush(
    private val http: ObservabilityHttp,
    private val workloads: BackendWorkloads,
    private val remoteOps: RemoteOperationsService,
    private val objectStore: ObjectStore,
    private val timeouts: FlushTimeouts = FlushTimeouts(),
) {
    companion object {
        /** Mimir's TSDB directory on the control node: `<tenant>/<block>/meta.json` under it. */
        const val TSDB_DIR = "${MimirManifestBuilder.DATA_HOST_PATH}/tsdb"
        private const val FAILED_COMPACTIONS = "cortex_ingester_tsdb_compactions_failed_total"
        private const val HEAD_MAX_TIMESTAMP = "cortex_ingester_tsdb_head_max_timestamp_seconds"
        private const val BLOCK_MARKER = "=== "
        private const val MILLIS_PER_SECOND = 1000.0
        private val json = Json { ignoreUnknownKeys = true }
    }

    /**
     * Runs the flush, advancing [progress] before each step and marking Mimir's state as it changes.
     *
     * @return the number of blocks verified in S3.
     * @throws IllegalStateException naming what is not proven, or the step that failed.
     */
    fun flush(
        controlHost: ClusterHost,
        clusterState: ClusterState,
        progress: FlushProgress,
    ): Int {
        progress.begin(FlushStep.MIMIR_SHUTDOWN)
        val before = scrape()

        // Marked before the call: a shutdown that times out may still have stopped the ingester.
        progress.mark(Constants.K8s.MIMIR_APP_LABEL, BackendState.INGESTER_STOPPED)
        val shutdown = http.post(Constants.K8s.MIMIR_HTTP_PORT, "/ingester/shutdown", timeout = timeouts.shutdown)
        check(shutdown.code in Constants.HttpStatus.OK until Constants.HttpStatus.MULTIPLE_CHOICES) {
            "Mimir's ingester shutdown failed (status ${shutdown.code}): ${shutdown.body}"
        }

        progress.begin(FlushStep.MIMIR_COMPACTION_CHECK)
        val after = scrape()
        check(after.getValue(FAILED_COMPACTIONS) == before.getValue(FAILED_COMPACTIONS)) {
            "A Mimir head compaction failed during the flush ($FAILED_COMPACTIONS went from " +
                "${before.getValue(FAILED_COMPACTIONS)} to ${after.getValue(FAILED_COMPACTIONS)})"
        }

        val blocks = localBlocks(controlHost)
        val headMaxMillis = (before.getValue(HEAD_MAX_TIMESTAMP) * MILLIS_PER_SECOND).toLong()
        if (headMaxMillis > 0) {
            val newest = blocks.maxOfOrNull { it.meta.maxTime } ?: 0
            check(newest >= headMaxMillis) {
                "No local Mimir block covers the head's newest sample (head $headMaxMillis ms, newest block ends $newest ms)"
            }
        }

        progress.begin(FlushStep.MIMIR_S3_CHECK)
        val store = ObservabilityStore.from(clusterState)
        val shippable = blocks.filter { it.meta.stats.numSamples > 0 && it.meta.compaction.level == 1 }
        val missing =
            shippable.filterNot {
                objectStore.fileExists(
                    ClusterS3Path.root(store.bucket).resolve("${store.metricsPrefix()}/${it.tenant}/${it.meta.ulid}/meta.json"),
                )
            }
        check(missing.isEmpty()) { "Mimir blocks are not in S3: ${missing.joinToString { "${it.tenant}/${it.meta.ulid}" }}" }

        progress.begin(FlushStep.MIMIR_SCALE_DOWN)
        progress.mark(Constants.K8s.MIMIR_APP_LABEL, BackendState.SCALED_TO_ZERO)
        workloads.scaleDown(controlHost, Constants.K8s.MIMIR_APP_LABEL, timeouts.scaleDown)
        return shippable.size
    }

    /** The two metrics the flush compares, summed over their series, from Mimir's `/metrics`. */
    private fun scrape(): Map<String, Double> {
        val response = http.get(Constants.K8s.MIMIR_HTTP_PORT, "/metrics")
        check(response.code == Constants.HttpStatus.OK) { "Could not read Mimir's metrics (status ${response.code})" }
        val samples =
            response.body
                .lines()
                .filterNot { it.startsWith("#") }
                .mapNotNull { line ->
                    val name = line.substringBefore('{').substringBefore(' ')
                    line.substringAfterLast(' ').toDoubleOrNull()?.let { name to it }
                }
        return listOf(FAILED_COMPACTIONS, HEAD_MAX_TIMESTAMP).associateWith { metric ->
            samples.filter { it.first == metric }.sumOf { it.second }
        }
    }

    /** Every block in Mimir's local TSDB, read from its `meta.json` over SSH. */
    private fun localBlocks(controlHost: ClusterHost): List<LocalBlock> {
        val output =
            remoteOps
                .executeRemotely(
                    controlHost.toHost(),
                    "sudo sh -c 'for f in $TSDB_DIR/*/*/meta.json; do [ -f \"\$f\" ] && echo \"$BLOCK_MARKER\$f\" && cat \"\$f\" && echo; done; true'",
                    output = false,
                ).text
        return output
            .split(BLOCK_MARKER)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { section ->
                val path = section.lineSequence().first().trim()
                val tenant = path.removePrefix("$TSDB_DIR/").substringBefore('/')
                LocalBlock(tenant, json.decodeFromString<BlockMeta>(section.substringAfter('\n')))
            }
    }

    private data class LocalBlock(
        val tenant: String,
        val meta: BlockMeta,
    )

    @Serializable
    private data class BlockMeta(
        val ulid: String,
        val maxTime: Long,
        val stats: Stats = Stats(),
        val compaction: Compaction = Compaction(),
    )

    @Serializable
    private data class Stats(
        val numSamples: Long = 0,
    )

    @Serializable
    private data class Compaction(
        val level: Int = 0,
    )
}
