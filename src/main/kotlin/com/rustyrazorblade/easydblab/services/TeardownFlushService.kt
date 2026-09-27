package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * How long each step of the pre-teardown save may take. Every step has a timeout, and a timeout
 * fails its signal like any other failure.
 *
 * @property shutdown the wait for a backend's synchronous `/ingester/shutdown`.
 * @property scaleDown the wait for a backend's pod to be gone once its Deployment is scaled to 0.
 * @property sendersStop the wait for the OTel collector's pods to be gone once its DaemonSet is deleted.
 * @property tempoDrain the wait for Tempo to hold no live trace and to have uploaded every block.
 */
data class FlushTimeouts(
    val shutdown: Duration = Duration.ofSeconds(Constants.TeardownFlush.SHUTDOWN_TIMEOUT_SECONDS),
    val scaleDown: Duration = Duration.ofSeconds(Constants.TeardownFlush.SCALE_DOWN_TIMEOUT_SECONDS),
    val sendersStop: Duration = Duration.ofSeconds(Constants.TeardownFlush.SENDERS_STOP_TIMEOUT_SECONDS),
    val tempoDrain: Duration = Duration.ofSeconds(Constants.TeardownFlush.TEMPO_DRAIN_TIMEOUT_SECONDS),
)

/**
 * The steps of the pre-teardown save. Each belongs to the signal it saves, so a failure names the
 * signal that is not saved and the step it stopped in.
 *
 * @property signal the signal this step saves.
 * @property description the step as the operator reads it.
 */
enum class FlushStep(
    val signal: TailSignal,
    val description: String,
) {
    LOKI_RUNNING(TailSignal.LOGS, "check that Loki is running"),
    ANNOTATION_MIRROR(TailSignal.LOGS, "mirror the Grafana annotations to Loki"),
    LOKI_SHUTDOWN(TailSignal.LOGS, "Loki ingester shutdown (flush every chunk to S3)"),
    LOKI_SCALE_DOWN(TailSignal.LOGS, "scale Loki to 0 (build and upload its index)"),
    LOKI_WAL_CHECK(TailSignal.LOGS, "Loki index write-ahead log check"),
    LOKI_S3_CHECK(TailSignal.LOGS, "Loki index check in S3"),
    RECORD_LOGS(TailSignal.LOGS, "record the saved logs in the cluster state"),
    MIMIR_RUNNING(TailSignal.METRICS, "check that Mimir is running"),
    MIMIR_SHUTDOWN(TailSignal.METRICS, "Mimir ingester shutdown (compact the head and ship its blocks)"),
    MIMIR_COMPACTION_CHECK(TailSignal.METRICS, "Mimir head compaction check"),
    MIMIR_S3_CHECK(TailSignal.METRICS, "Mimir block check in S3"),
    MIMIR_SCALE_DOWN(TailSignal.METRICS, "scale Mimir to 0"),
    RECORD_METRICS(TailSignal.METRICS, "record the saved metrics in the cluster state"),
    SENDERS_STOP(TailSignal.TRACES, "stop the OTel collector (delete its DaemonSet and wait for its pods to go)"),
    TEMPO_RUNNING(TailSignal.TRACES, "check that Tempo is running"),
    TEMPO_LIVE_TRACES(TailSignal.TRACES, "wait until Tempo holds no live trace"),
    TEMPO_BLOCKS_FLUSHED(TailSignal.TRACES, "wait until Tempo has uploaded every block on the control node"),
    PROFILES_NO_FLUSH(TailSignal.PROFILES, "report that profiles need no flush"),
    ANNOTATIONS_BACKUP(TailSignal.ANNOTATIONS, "Grafana annotations backup"),
    ;

    companion object {
        /** The step that records [signal] in the cluster state once its flush succeeded. */
        fun recordOf(signal: TailSignal): FlushStep =
            when (signal) {
                TailSignal.LOGS -> RECORD_LOGS
                TailSignal.METRICS -> RECORD_METRICS
                else -> error("${signal.description} is never recorded")
            }
    }
}

/**
 * What the save has done to a workload. `down` never undoes it, so a failure reports it.
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
    DELETED("deleted"),
}

/**
 * Where one step of the save is: the step it runs and the state of the one workload it owns. Each
 * parallel step has its own, so no two threads write the same progress; `down` merges their
 * workload states once every step has finished.
 *
 * @param first the step the save starts in.
 * @property owns the workload this step may change, if any.
 */
class FlushProgress(
    first: FlushStep,
    private val owns: String? = null,
) {
    /** The step running now, or the one that failed. */
    @Volatile
    var step: FlushStep = first
        private set

    @Volatile
    private var state: BackendState = BackendState.RUNNING

    /** The owned workload's state, or nothing when this step owns no workload. */
    val backends: Map<String, BackendState> get() = owns?.let { mapOf(it to state) }.orEmpty()

    /** Starts [step]. */
    fun begin(step: FlushStep) {
        this.step = step
    }

    /** Records that [workload], the one this step owns, is now in [state]. */
    fun mark(
        workload: String,
        state: BackendState,
    ) {
        require(workload == owns) { "this step owns ${owns ?: "no workload"}, not $workload" }
        this.state = state
    }
}

/**
 * What one signal's save proved is in S3.
 */
sealed interface SignalReport {
    /** The objects the save verified in S3; what a recorded signal keeps. */
    val verifiedObjects: Long

    /** Loki's shutdown wrote [chunksFlushed] chunks, and [indexFiles] index files were found in S3. */
    data class Logs(
        val indexFiles: Int,
        val chunksFlushed: Long,
    ) : SignalReport {
        override val verifiedObjects: Long get() = indexFiles.toLong()
    }

    /** [blocks] shippable Mimir blocks were found in S3. */
    data class Metrics(
        val blocks: Int,
    ) : SignalReport {
        override val verifiedObjects: Long get() = blocks.toLong()
    }

    /** Tempo held no live trace and had uploaded all [blocks] of its local blocks. */
    data class Traces(
        val blocks: Int,
    ) : SignalReport {
        override val verifiedObjects: Long get() = blocks.toLong()
    }

    /** Profiles need no flush: Pyroscope writes each batch to S3 before it accepts it. */
    data object Profiles : SignalReport {
        override val verifiedObjects: Long get() = 0
    }

    /** The annotations backup is at [key]. */
    data class Annotations(
        val key: String,
    ) : SignalReport {
        override val verifiedObjects: Long get() = 1
    }
}

/**
 * One signal's save stopped at [step]. Nothing was undone: each workload is as [backends] says.
 *
 * @property step the step that failed or timed out.
 * @property backends the state of the workload the step owns, when it stopped.
 */
class FlushStepFailed(
    val step: FlushStep,
    val backends: Map<String, BackendState>,
    cause: Throwable,
) : RuntimeException("${step.description} failed: ${cause.message ?: cause}", cause) {
    /** The signal that is not saved. */
    val signal: TailSignal get() = step.signal
}

/**
 * What a save did: the signals it saved, the ones that failed, and each workload's state after it.
 *
 * @property saved each saved signal and what its save proved.
 * @property failed each signal that is not saved, with the step it stopped in.
 * @property backends each workload's state once every step finished (Loki, Mimir, the OTel collector, Tempo).
 */
data class FlushOutcome(
    val saved: Map<TailSignal, SignalReport>,
    val failed: Map<TailSignal, FlushStepFailed>,
    val backends: Map<String, BackendState>,
)

/**
 * One backend's part of the save: flushes its signal and proves it is in S3, advancing the
 * [FlushProgress] before each step.
 */
fun interface SignalFlush {
    /**
     * Runs the flush once.
     *
     * @throws IllegalStateException naming what is not in S3, or the step that failed.
     */
    fun flush(
        controlHost: ClusterHost,
        clusterState: ClusterState,
        progress: FlushProgress,
    ): SignalReport
}

/**
 * Saves everything the cluster holds that is not yet in S3, before any infrastructure is torn down.
 *
 * Phase A runs in order: check that Loki runs and mirror the Grafana annotations to Loki (only while
 * logs are pending), then stop the telemetry senders so their last batches reach the backends while
 * those still accept writes. Phase B runs every remaining step at once: the Loki flush, the Mimir
 * flush, the Tempo drain, the profiles report and the annotations backup. Every step runs to
 * completion and a failed step never stops another. Nothing is undone and no backend is started
 * again: the owner wants a cluster being taken down to go down (owner decision, 2026-09-26).
 */
interface TeardownFlushService {
    /**
     * Saves each signal in [pending] once.
     *
     * @param onSaved called on the step's own thread the moment a recorded signal
     *   ([TailSignal.RECORDED]) is saved, before the step reports success; a failure fails the signal.
     * @return what was saved, what failed and at which step, and each workload's state.
     */
    fun saveTail(
        controlHost: ClusterHost,
        clusterState: ClusterState,
        pending: Set<TailSignal>,
        onSaved: (TailSignal, SignalReport) -> Unit = { _, _ -> },
    ): FlushOutcome
}

/**
 * [TeardownFlushService] over the backend flushes, run on a platform-thread pool.
 *
 * @property workloads reports whether each backend runs before its flush touches it.
 */
@Suppress("LongParameterList")
class DefaultTeardownFlushService(
    private val lokiFlush: SignalFlush,
    private val mimirFlush: SignalFlush,
    private val tempoDrain: SignalFlush,
    private val workloads: BackendWorkloads,
    private val telemetrySenders: TelemetrySenders,
    private val annotationMirror: AnnotationMirror,
    private val annotationBackupService: GrafanaAnnotationBackupService,
    private val eventBus: EventBus,
    private val timeouts: FlushTimeouts = FlushTimeouts(),
) : TeardownFlushService {
    private val log = KotlinLogging.logger {}

    /** One Phase B step: the signal it saves, its progress, and the work. */
    private class SaveTask(
        val signal: TailSignal,
        val progress: FlushProgress,
        val run: () -> SignalReport,
    )

    override fun saveTail(
        controlHost: ClusterHost,
        clusterState: ClusterState,
        pending: Set<TailSignal>,
        onSaved: (TailSignal, SignalReport) -> Unit,
    ): FlushOutcome {
        val failed = linkedMapOf<TailSignal, FlushStepFailed>()
        val progresses = mutableListOf<FlushProgress>()
        val tasks = mutableListOf<SaveTask>()

        // Phase A, in order. The mirror writes to Loki, so it must finish before Loki's flush starts.
        if (TailSignal.LOGS in pending) {
            val progress = FlushProgress(FlushStep.LOKI_RUNNING, Constants.K8s.LOKI_APP_LABEL).also(progresses::add)
            attempt(progress) { mirrorToRunningLoki(controlHost, progress) }
                .onSuccess { tasks += SaveTask(TailSignal.LOGS, progress) { lokiFlush.flush(controlHost, clusterState, progress) } }
                .onFailure { failed[TailSignal.LOGS] = it as FlushStepFailed }
        }
        val sendersProgress = FlushProgress(FlushStep.SENDERS_STOP, Constants.K8s.OTEL_COLLECTOR_APP_LABEL).also(progresses::add)
        val sendersStop = attempt(sendersProgress) { stopSenders(controlHost, sendersProgress) }

        if (TailSignal.METRICS in pending) {
            val progress = FlushProgress(FlushStep.MIMIR_RUNNING, Constants.K8s.MIMIR_APP_LABEL).also(progresses::add)
            tasks +=
                SaveTask(TailSignal.METRICS, progress) {
                    requireRunning(controlHost, Constants.K8s.MIMIR_APP_LABEL, "Mimir", progress)
                    mimirFlush.flush(controlHost, clusterState, progress)
                }
        }
        if (TailSignal.TRACES in pending) {
            // The collector is Tempo's only sender: while it may still run, Tempo cannot drain.
            sendersStop.exceptionOrNull()?.let { failed[TailSignal.TRACES] = it as FlushStepFailed }
            if (sendersStop.isSuccess) {
                val progress = FlushProgress(FlushStep.TEMPO_RUNNING, Constants.K8s.TEMPO_APP_LABEL).also(progresses::add)
                tasks +=
                    SaveTask(TailSignal.TRACES, progress) {
                        requireRunning(controlHost, Constants.K8s.TEMPO_APP_LABEL, "Tempo", progress)
                        eventBus.emit(Event.Teardown.TempoDrainStarting(timeouts.tempoDrain.seconds))
                        tempoDrain.flush(controlHost, clusterState, progress)
                    }
            }
        }
        if (TailSignal.PROFILES in pending) {
            tasks += SaveTask(TailSignal.PROFILES, FlushProgress(FlushStep.PROFILES_NO_FLUSH)) { reportProfiles() }
        }
        if (TailSignal.ANNOTATIONS in pending) {
            tasks +=
                SaveTask(TailSignal.ANNOTATIONS, FlushProgress(FlushStep.ANNOTATIONS_BACKUP)) {
                    val backup = annotationBackupService.backup(controlHost, clusterState).getOrThrow()
                    SignalReport.Annotations(backup.s3Path.getKey())
                }
        }

        // Phase B, all at once. Each step catches its own failure, so every step runs to completion.
        val saved = linkedMapOf<TailSignal, SignalReport>()
        runInParallel(tasks, onSaved).forEach { (signal, result) ->
            result.onSuccess { saved[signal] = it }.onFailure { failed[signal] = it as FlushStepFailed }
        }
        failed.values.forEach { log.warn(it) { "Pre-teardown save of ${it.signal} stopped at ${it.step}" } }

        val backends = linkedMapOf<String, BackendState>()
        progresses.forEach { backends.putAll(it.backends) }
        return FlushOutcome(saved = saved, failed = failed, backends = backends)
    }

    /** Checks that Loki runs, then mirrors every Grafana annotation to it. */
    private fun mirrorToRunningLoki(
        controlHost: ClusterHost,
        progress: FlushProgress,
    ) {
        requireRunning(controlHost, Constants.K8s.LOKI_APP_LABEL, "Loki", progress)
        progress.begin(FlushStep.ANNOTATION_MIRROR)
        val mirrored =
            annotationMirror.syncAll(controlHost).getOrElse { failure ->
                throw IllegalStateException(
                    "${failure.message ?: failure}; the Loki flush was not run, and Loki keeps running",
                    failure,
                )
            }
        eventBus.emit(Event.Grafana.AnnotationsMirrored(mirrored))
    }

    /** Deletes the OTel collector and waits for its pods to go; succeeds when it is already gone. */
    private fun stopSenders(
        controlHost: ClusterHost,
        progress: FlushProgress,
    ) {
        // Marked before the call: a stop that times out may still have deleted the DaemonSet.
        progress.mark(Constants.K8s.OTEL_COLLECTOR_APP_LABEL, BackendState.DELETED)
        telemetrySenders.stop(controlHost, timeouts.sendersStop)
        eventBus.emit(Event.Teardown.TelemetrySendersStopped)
    }

    /**
     * Fails the step unless [workload] runs. With its signal not recorded, a backend at 0 or not
     * ready was stopped by an earlier `down`, and `down` never starts a backend again.
     */
    private fun requireRunning(
        controlHost: ClusterHost,
        workload: String,
        name: String,
        progress: FlushProgress,
    ) {
        val state = workloads.state(controlHost, workload)
        progress.mark(workload, state)
        check(state == BackendState.RUNNING) {
            "$name was stopped by an earlier `down` (${state.description}); down never starts a backend again"
        }
    }

    private fun reportProfiles(): SignalReport {
        eventBus.emit(Event.Teardown.ProfilesNeedNoFlush)
        return SignalReport.Profiles
    }

    /**
     * Runs every task at once on its own platform thread and waits for all of them. A recorded
     * signal is handed to [onSaved] on its task's thread, under its record step.
     */
    private fun runInParallel(
        tasks: List<SaveTask>,
        onSaved: (TailSignal, SignalReport) -> Unit,
    ): List<Pair<TailSignal, Result<SignalReport>>> {
        if (tasks.isEmpty()) return emptyList()
        val callables =
            tasks.map { task ->
                Callable {
                    task.signal to
                        attempt(task.progress) {
                            task.run().also { report ->
                                emitSaved(report)
                                if (task.signal in TailSignal.RECORDED) {
                                    task.progress.begin(FlushStep.recordOf(task.signal))
                                    onSaved(task.signal, report)
                                }
                            }
                        }
                }
            }
        return Executors.newFixedThreadPool(tasks.size).use { pool -> pool.invokeAll(callables).map { it.get() } }
    }

    private fun emitSaved(report: SignalReport) {
        when (report) {
            is SignalReport.Logs ->
                eventBus.emit(
                    Event.Teardown.LokiFlushed(indexFiles = report.indexFiles, chunksFlushed = report.chunksFlushed),
                )
            is SignalReport.Metrics -> eventBus.emit(Event.Teardown.MimirFlushed(report.blocks))
            is SignalReport.Traces -> eventBus.emit(Event.Teardown.TempoFlushed(report.blocks))
            SignalReport.Profiles, is SignalReport.Annotations -> Unit
        }
    }

    /** Runs [block], turning any failure into a [FlushStepFailed] at [progress]'s step. */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> attempt(
        progress: FlushProgress,
        block: () -> T,
    ): Result<T> =
        try {
            Result.success(block())
        } catch (failure: Exception) {
            Result.failure(FlushStepFailed(progress.step, progress.backends, failure))
        }
}
