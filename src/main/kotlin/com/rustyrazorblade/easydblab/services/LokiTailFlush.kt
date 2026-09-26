package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService

/**
 * What a Loki flush proved.
 *
 * @property chunksFlushed the chunks the shutdown wrote to S3.
 * @property indexFiles the index files Loki built locally, each found in S3.
 */
data class LokiFlushResult(
    val chunksFlushed: Long,
    val indexFiles: Int,
)

/**
 * Flushes Loki before teardown and proves its chunks and index are in S3.
 *
 * 1. `POST /ingester/shutdown?flush=true&terminate=false`: the handler returns 204 only once every
 *    flush queue has drained, i.e. every chunk is in S3. Its 204 is the completion signal; a gauge
 *    or a failure counter is not.
 * 2. Scale Loki to 0 and wait for the pod to go: stopping the store builds the index head into
 *    index files and the shipper uploads them.
 * 3. On the control node, with Loki stopped: no index write-ahead segment may remain (each is
 *    removed once its head is built), and every locally built index file must exist in S3 as
 *    `observability/logs/index/<table>/<file>.gz`. Backdated tables are covered, since every local
 *    file is checked.
 *
 * The node listings treat only a missing directory as empty; any other failure, sudo's included,
 * fails the flush with the node's error. And the check cannot pass vacuously: Loki's flushed-chunk
 * counter is read around the shutdown, and chunks flushed there put their series in the index
 * head, so finding no index file after they were flushed fails the flush.
 *
 * Each step is recorded in the [FlushProgress] before it runs, and so is each change to Loki's
 * state, so a failure names the step and leaves Loki as that step left it.
 */
class LokiTailFlush(
    private val http: ObservabilityHttp,
    private val workloads: BackendWorkloads,
    private val remoteOps: RemoteOperationsService,
    private val objectStore: ObjectStore,
    private val timeouts: FlushTimeouts = FlushTimeouts(),
) {
    companion object {
        /** Loki's TSDB index directory on the control node (`active_index_directory`). */
        const val INDEX_DIR = "${LokiManifestBuilder.DATA_HOST_PATH}/tsdb-index"
        private const val SHUTDOWN_PATH = "/ingester/shutdown?flush=true&terminate=false&delete_ring_tokens=false"
        private const val CHUNKS_FLUSHED = "loki_ingester_chunks_flushed_total"

        /** Lists [findArgs] under [dir] as root; a missing [dir] lists nothing, any other failure exits non-zero. */
        private fun listing(
            dir: String,
            findArgs: String,
        ) = "sudo sh -c 'if [ -d $dir ]; then find $dir $findArgs; fi'"
    }

    /**
     * Runs the flush, advancing [progress] before each step and marking Loki's state as it changes.
     *
     * @return the chunks the shutdown wrote and the index files verified in S3.
     * @throws IllegalStateException naming what is not in S3, or the step that failed.
     */
    fun flush(
        controlHost: ClusterHost,
        clusterState: ClusterState,
        progress: FlushProgress,
    ): LokiFlushResult {
        progress.begin(FlushStep.LOKI_SHUTDOWN)
        val chunksBefore = chunksFlushed()

        // Marked before the call: a shutdown that times out may still have stopped the ingester.
        progress.mark(Constants.K8s.LOKI_APP_LABEL, BackendState.INGESTER_STOPPED)
        val shutdown = http.post(Constants.K8s.LOKI_HTTP_PORT, SHUTDOWN_PATH, timeout = timeouts.shutdown)
        check(shutdown.code == Constants.HttpStatus.NO_CONTENT) {
            "Loki's ingester did not finish flushing (status ${shutdown.code}): ${shutdown.body}"
        }
        val flushedAtShutdown = chunksFlushed() - chunksBefore

        progress.begin(FlushStep.LOKI_SCALE_DOWN)
        progress.mark(Constants.K8s.LOKI_APP_LABEL, BackendState.SCALED_TO_ZERO)
        workloads.scaleDown(controlHost, Constants.K8s.LOKI_APP_LABEL, timeouts.scaleDown)

        progress.begin(FlushStep.LOKI_WAL_CHECK)
        val walSegments = lines(controlHost, listing("$INDEX_DIR/wal", "-type f"))
        check(walSegments.isEmpty()) {
            "Loki's index write-ahead log still holds ${walSegments.size} segment(s) after it stopped, so its " +
                "index head was not built: ${walSegments.joinToString()}"
        }

        progress.begin(FlushStep.LOKI_S3_CHECK)
        val indexFiles = lines(controlHost, listing("$INDEX_DIR/multitenant", "-mindepth 2 -maxdepth 2 -type f -printf \"%P\\n\""))
        check(flushedAtShutdown == 0L || indexFiles.isNotEmpty()) {
            "Loki flushed $flushedAtShutdown chunks at shutdown but no index file was found under $INDEX_DIR/multitenant, " +
                "so there is nothing to prove its index is in S3"
        }

        val store = ObservabilityStore.from(clusterState)
        val missing =
            indexFiles.filterNot {
                objectStore.fileExists(ClusterS3Path.root(store.bucket).resolve("${store.logsPrefix()}/index/$it.gz"))
            }
        check(missing.isEmpty()) { "Loki index files are not in S3: ${missing.joinToString()}" }
        return LokiFlushResult(chunksFlushed = flushedAtShutdown, indexFiles = indexFiles.size)
    }

    /** Loki's flushed-chunk counter, summed over its reasons; absent until the first flush. */
    private fun chunksFlushed(): Long {
        val response = http.get(Constants.K8s.LOKI_HTTP_PORT, "/metrics")
        check(response.code == Constants.HttpStatus.OK) { "Could not read Loki's metrics (status ${response.code})" }
        return response.body
            .lines()
            .filter { it.startsWith("$CHUNKS_FLUSHED{") || it.startsWith("$CHUNKS_FLUSHED ") }
            .sumOf { it.substringAfterLast(' ').toDouble() }
            .toLong()
    }

    private fun lines(
        controlHost: ClusterHost,
        command: String,
    ): List<String> =
        remoteOps
            .executeRemotely(controlHost.toHost(), command, output = false)
            .text
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
