package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState

/**
 * Flushes Loki before teardown and waits for the flush to finish.
 *
 * 1. `POST /ingester/shutdown?flush=true&terminate=false`: the handler returns 204 only once every
 *    flush queue has drained, i.e. every chunk is in S3. Its 204 is the completion signal.
 * 2. Scale Loki to 0 and wait for the pod to go: stopping the store builds the index head into
 *    index files and the shipper uploads them.
 *
 * Each step is recorded in the [FlushProgress] before it runs, and so is each change to Loki's
 * state, so a failure names the step and leaves Loki as that step left it.
 */
class LokiTailFlush(
    private val http: ObservabilityHttp,
    private val workloads: BackendWorkloads,
    private val timeouts: FlushTimeouts = FlushTimeouts(),
) : SignalFlush {
    companion object {
        private const val SHUTDOWN_PATH = "/ingester/shutdown?flush=true&terminate=false&delete_ring_tokens=false"
    }

    /**
     * Runs the flush, advancing [progress] before each step and marking Loki's state as it changes.
     *
     * @throws IllegalStateException naming the step that failed or timed out.
     */
    override fun flush(
        controlHost: ClusterHost,
        clusterState: ClusterState,
        progress: FlushProgress,
    ): SignalReport.Logs {
        progress.begin(FlushStep.LOKI_SHUTDOWN)
        // Marked before the call: a shutdown that times out may still have stopped the ingester.
        progress.mark(Constants.K8s.LOKI_APP_LABEL, BackendState.INGESTER_STOPPED)
        val shutdown = http.post(Constants.K8s.LOKI_HTTP_PORT, SHUTDOWN_PATH, timeout = timeouts.shutdown)
        check(shutdown.code == Constants.HttpStatus.NO_CONTENT) {
            "Loki's ingester did not finish flushing (status ${shutdown.code}): ${shutdown.body}"
        }

        progress.begin(FlushStep.LOKI_SCALE_DOWN)
        progress.mark(Constants.K8s.LOKI_APP_LABEL, BackendState.SCALED_TO_ZERO)
        workloads.scaleDown(controlHost, Constants.K8s.LOKI_APP_LABEL, timeouts.scaleDown)
        return SignalReport.Logs
    }
}
