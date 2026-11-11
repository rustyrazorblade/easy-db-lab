package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState

/**
 * Flushes Mimir before teardown and waits for the flush to finish.
 *
 * 1. `POST /ingester/shutdown`: compacts every tenant's head into a block and ships it before it
 *    returns. Any answer outside 2xx fails the flush.
 * 2. Scale Mimir to 0.
 *
 * Each step is recorded in the [FlushProgress] before it runs, and so is each change to Mimir's
 * state, so a failure names the step and leaves Mimir as that step left it.
 */
class MimirTailFlush(
    private val http: ObservabilityHttp,
    private val workloads: BackendWorkloads,
    private val timeouts: FlushTimeouts = FlushTimeouts(),
) : SignalFlush {
    /**
     * Runs the flush, advancing [progress] before each step and marking Mimir's state as it changes.
     *
     * @throws IllegalStateException naming the step that failed or timed out.
     */
    override fun flush(
        controlHost: ClusterHost,
        clusterState: ClusterState,
        progress: FlushProgress,
    ): SignalReport.Metrics {
        progress.begin(FlushStep.MIMIR_SHUTDOWN)
        // Marked before the call: a shutdown that times out may still have stopped the ingester.
        progress.mark(Constants.K8s.MIMIR_APP_LABEL, BackendState.INGESTER_STOPPED)
        val shutdown = http.post(Constants.K8s.MIMIR_HTTP_PORT, "/ingester/shutdown", timeout = timeouts.shutdown)
        check(shutdown.code in Constants.HttpStatus.OK until Constants.HttpStatus.MULTIPLE_CHOICES) {
            "Mimir's ingester shutdown failed (status ${shutdown.code}): ${shutdown.body}"
        }

        progress.begin(FlushStep.MIMIR_SCALE_DOWN)
        progress.mark(Constants.K8s.MIMIR_APP_LABEL, BackendState.SCALED_TO_ZERO)
        workloads.scaleDown(controlHost, Constants.K8s.MIMIR_APP_LABEL, timeouts.scaleDown)
        return SignalReport.Metrics
    }
}
