package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration

/**
 * How long each step of the pre-teardown flush may take. Every step has a timeout, and a timeout
 * stops the flush like any other failure.
 *
 * @property shutdown the wait for a backend's synchronous `/ingester/shutdown`.
 * @property scaleDown the wait for a backend's pod to be gone once its Deployment is scaled to 0.
 */
data class FlushTimeouts(
    val shutdown: Duration = Duration.ofSeconds(Constants.TeardownFlush.SHUTDOWN_TIMEOUT_SECONDS),
    val scaleDown: Duration = Duration.ofSeconds(Constants.TeardownFlush.SCALE_DOWN_TIMEOUT_SECONDS),
)

/**
 * The steps of the pre-teardown flush, in the order they run. A failure names the step it stopped
 * in, so the operator knows what did not reach S3.
 *
 * @property description the step as the operator reads it.
 */
enum class FlushStep(
    val description: String,
) {
    BACKENDS_RUNNING("check that Loki and Mimir are running"),
    ANNOTATION_MIRROR("mirror the Grafana annotations to Loki"),
    LOKI_SHUTDOWN("Loki ingester shutdown (flush every chunk to S3)"),
    LOKI_SCALE_DOWN("scale Loki to 0 (build and upload its index)"),
    LOKI_WAL_CHECK("Loki index write-ahead log check"),
    LOKI_S3_CHECK("Loki index check in S3"),
    MIMIR_SHUTDOWN("Mimir ingester shutdown (compact the head and ship its blocks)"),
    MIMIR_COMPACTION_CHECK("Mimir head compaction check"),
    MIMIR_S3_CHECK("Mimir block check in S3"),
    MIMIR_SCALE_DOWN("scale Mimir to 0"),
    ANNOTATIONS_BACKUP("Grafana annotations backup"),
    RECORD("record the completed flush in the cluster state"),
}

/**
 * What the flush has done to a backend. `down` never undoes it, so a failure reports it.
 *
 * @property description the state as the operator reads it.
 */
enum class BackendState(
    val description: String,
) {
    RUNNING("running"),
    NOT_READY("not ready"),
    INGESTER_STOPPED("ingester stopped (the pod still runs and takes no new data)"),
    SCALED_TO_ZERO("scaled to 0"),
}

/**
 * Where a flush is: the step it runs and each backend's state. The backend flushes advance it
 * before each step, so a failure reports where it stopped.
 */
class FlushProgress {
    /** The step running now, or the one that failed. */
    var step: FlushStep = FlushStep.BACKENDS_RUNNING
        private set

    private val states =
        linkedMapOf(Constants.K8s.LOKI_APP_LABEL to BackendState.RUNNING, Constants.K8s.MIMIR_APP_LABEL to BackendState.RUNNING)

    /** Each backend's state, Loki first. */
    val backends: Map<String, BackendState> get() = states.toMap()

    /** Starts [step]. */
    fun begin(step: FlushStep) {
        this.step = step
    }

    /** Records that [workload] is now in [state]. */
    fun mark(
        workload: String,
        state: BackendState,
    ) {
        states[workload] = state
    }
}

/**
 * What a successful flush proved is in S3.
 *
 * @property lokiIndexFiles the index files Loki built locally, each found in S3.
 * @property lokiChunksFlushed the chunks Loki's shutdown wrote to S3.
 * @property mimirBlocks the shippable blocks Mimir holds locally, each found in S3.
 */
data class FlushReport(
    val lokiIndexFiles: Int,
    val lokiChunksFlushed: Long,
    val mimirBlocks: Int,
)

/**
 * The flush stopped at [step]. Nothing was undone: each backend is as [backends] says.
 *
 * @property step the step that failed or timed out.
 * @property backends each backend's state when the flush stopped.
 */
class FlushStepFailed(
    val step: FlushStep,
    val backends: Map<String, BackendState>,
    cause: Throwable,
) : RuntimeException("${step.description} failed: ${cause.message ?: cause}", cause)

/**
 * Saves everything the cluster holds that is not yet in S3, before any infrastructure is torn down
 * (issue 967, decision D2).
 *
 * In order: both backends must be running; every Grafana annotation is mirrored to Loki; Loki is
 * flushed and verified ([LokiTailFlush]); Mimir is flushed and verified ([MimirTailFlush]); the
 * Grafana annotations are backed up. A step that fails or times out stops the flush there. Nothing
 * is undone and no backend is started again: the owner wants a cluster being taken down to go down
 * (owner decision, 2026-09-26). The write-ahead data and local blocks stay on the control node's disk.
 */
interface TeardownFlushService {
    /**
     * Runs the flush once.
     *
     * @return what reached S3, or a [FlushStepFailed] naming the step and each backend's state.
     */
    fun saveTail(
        controlHost: ClusterHost,
        clusterState: ClusterState,
    ): Result<FlushReport>
}

/**
 * [TeardownFlushService] over the two backend flushes.
 *
 * @property workloads reports whether each backend runs before anything is touched.
 */
@Suppress("LongParameterList")
class DefaultTeardownFlushService(
    private val lokiFlush: LokiTailFlush,
    private val mimirFlush: MimirTailFlush,
    private val workloads: BackendWorkloads,
    private val annotationMirror: AnnotationMirror,
    private val annotationBackupService: GrafanaAnnotationBackupService,
    private val eventBus: EventBus,
) : TeardownFlushService {
    private val log = KotlinLogging.logger {}

    @Suppress("TooGenericExceptionCaught")
    override fun saveTail(
        controlHost: ClusterHost,
        clusterState: ClusterState,
    ): Result<FlushReport> {
        val progress = FlushProgress()
        return try {
            requireBackendsRunning(controlHost, progress)
            progress.begin(FlushStep.ANNOTATION_MIRROR)
            val annotations = annotationMirror.syncAll(controlHost).getOrThrow()
            eventBus.emit(Event.Grafana.AnnotationsMirrored(annotations))
            val loki = lokiFlush.flush(controlHost, clusterState, progress)
            eventBus.emit(Event.Teardown.LokiFlushed(indexFiles = loki.indexFiles, chunksFlushed = loki.chunksFlushed))
            val blocks = mimirFlush.flush(controlHost, clusterState, progress)
            eventBus.emit(Event.Teardown.MimirFlushed(blocks))
            progress.begin(FlushStep.ANNOTATIONS_BACKUP)
            annotationBackupService.backup(controlHost, clusterState).getOrThrow()
            Result.success(FlushReport(lokiIndexFiles = loki.indexFiles, lokiChunksFlushed = loki.chunksFlushed, mimirBlocks = blocks))
        } catch (failure: Exception) {
            log.warn(failure) { "Pre-teardown flush stopped at ${progress.step}; backends ${progress.backends}" }
            Result.failure(FlushStepFailed(progress.step, progress.backends, failure))
        }
    }

    /**
     * Refuses to flush when a backend is not running. With no completed flush recorded, one that is
     * at 0 or not ready was stopped by an earlier `down` whose flush failed, or by hand; its tail
     * cannot be flushed without starting it again, which `down` never does.
     */
    private fun requireBackendsRunning(
        controlHost: ClusterHost,
        progress: FlushProgress,
    ) {
        progress.begin(FlushStep.BACKENDS_RUNNING)
        listOf(Constants.K8s.LOKI_APP_LABEL, Constants.K8s.MIMIR_APP_LABEL).forEach { workload ->
            progress.mark(workload, workloads.state(controlHost, workload))
        }
        val stopped = progress.backends.filterValues { it != BackendState.RUNNING }
        check(stopped.isEmpty()) {
            stopped.entries.joinToString { (workload, state) -> "$workload (${state.description})" } +
                " cannot be flushed without starting it again, and down never starts a backend"
        }
    }
}
