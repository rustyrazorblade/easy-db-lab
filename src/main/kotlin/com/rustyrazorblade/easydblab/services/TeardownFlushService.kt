package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.exceptions.EasyDBLabException
import io.fabric8.kubernetes.client.KubernetesClientException
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
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
    ANNOTATION_MIRROR(TailSignal.LOGS, "mirror the Grafana annotations to Loki"),
    LOKI_SHUTDOWN(TailSignal.LOGS, "Loki ingester shutdown (flush every chunk to S3)"),
    LOKI_SCALE_DOWN(TailSignal.LOGS, "scale Loki to 0 (build and upload its index)"),
    RECORD_LOGS(TailSignal.LOGS, "record the saved logs in the cluster state"),
    MIMIR_SHUTDOWN(TailSignal.METRICS, "Mimir ingester shutdown (compact the head and ship its blocks)"),
    MIMIR_SCALE_DOWN(TailSignal.METRICS, "scale Mimir to 0"),
    RECORD_METRICS(TailSignal.METRICS, "record the saved metrics in the cluster state"),
    SENDERS_STOP(TailSignal.TRACES, "stop the OTel collector (delete its DaemonSet and wait for its pods to go)"),
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
 * What one signal's save did. A flush reports only that it finished; it verifies nothing further.
 */
sealed interface SignalReport {
    /** Loki's ingester flushed every chunk to S3 and Loki stopped, uploading its index. */
    data object Logs : SignalReport

    /** Mimir's ingester compacted its head and shipped its blocks, and Mimir stopped. */
    data object Metrics : SignalReport

    /** Tempo held no live trace and had uploaded every local block. */
    data object Traces : SignalReport

    /** Profiles need no flush: Pyroscope writes each batch to S3 before it accepts it. */
    data object Profiles : SignalReport

    /** The annotations backup is at [key]. */
    data class Annotations(
        val key: String,
    ) : SignalReport
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
 * @property saved each saved signal and what its save did.
 * @property failed each signal that is not saved, with the step it stopped in.
 * @property backends each workload's state once every step finished (Loki, Mimir, the OTel collector, Tempo).
 */
data class FlushOutcome(
    val saved: Map<TailSignal, SignalReport>,
    val failed: Map<TailSignal, FlushStepFailed>,
    val backends: Map<String, BackendState>,
)

/**
 * One backend's part of the save: flushes its signal and waits for the flush to finish, advancing
 * the [FlushProgress] before each step.
 */
fun interface SignalFlush {
    /**
     * Runs the flush once.
     *
     * @throws IllegalStateException naming the step that failed or timed out.
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
 * Phase A runs in order: mirror the Grafana annotations to Loki (only while logs are pending), then
 * stop the telemetry senders so their last batches reach the backends while those still accept
 * writes. Phase B runs every remaining step at once: the Loki flush, the Mimir
 * flush, the Tempo drain, the profiles report and the annotations backup. Every step runs to
 * completion and a failed step never stops another. Nothing is undone and no backend is started
 * again: the owner wants a cluster being taken down to go down (owner decision, 2026-09-26). No step
 * checks anything beyond its own flush and wait.
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
 * [TeardownFlushService] over the backend flushes, run on a platform-thread pool. Built with
 * [builder]: every collaborator is required except the timeouts.
 */
class DefaultTeardownFlushService private constructor(
    builder: Builder,
) : TeardownFlushService {
    private val log = KotlinLogging.logger {}
    private val lokiFlush = builder.lokiFlush
    private val mimirFlush = builder.mimirFlush
    private val tempoDrain = builder.tempoDrain
    private val telemetrySenders = builder.telemetrySenders
    private val annotationMirror = builder.annotationMirror
    private val annotationBackupService = builder.annotationBackupService
    private val eventBus = builder.eventBus
    private val timeouts = builder.timeouts

    companion object {
        fun builder(): Builder = Builder()
    }

    /** Collects the collaborators of a [DefaultTeardownFlushService]. */
    class Builder internal constructor() {
        lateinit var lokiFlush: SignalFlush
            private set
        lateinit var mimirFlush: SignalFlush
            private set
        lateinit var tempoDrain: SignalFlush
            private set
        lateinit var telemetrySenders: TelemetrySenders
            private set
        lateinit var annotationMirror: AnnotationMirror
            private set
        lateinit var annotationBackupService: GrafanaAnnotationBackupService
            private set
        lateinit var eventBus: EventBus
            private set
        var timeouts: FlushTimeouts = FlushTimeouts()
            private set

        fun lokiFlush(flush: SignalFlush) = apply { lokiFlush = flush }

        fun mimirFlush(flush: SignalFlush) = apply { mimirFlush = flush }

        fun tempoDrain(flush: SignalFlush) = apply { tempoDrain = flush }

        fun telemetrySenders(senders: TelemetrySenders) = apply { telemetrySenders = senders }

        fun annotationMirror(mirror: AnnotationMirror) = apply { annotationMirror = mirror }

        fun annotationBackupService(service: GrafanaAnnotationBackupService) = apply { annotationBackupService = service }

        fun eventBus(bus: EventBus) = apply { eventBus = bus }

        fun timeouts(value: FlushTimeouts) = apply { timeouts = value }

        /** @throws UninitializedPropertyAccessException when a required collaborator was not set. */
        fun build(): DefaultTeardownFlushService = DefaultTeardownFlushService(this)
    }

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
            val progress = FlushProgress(FlushStep.ANNOTATION_MIRROR, Constants.K8s.LOKI_APP_LABEL).also(progresses::add)
            attempt(progress) { mirrorToLoki(controlHost) }
                .onSuccess { tasks += SaveTask(TailSignal.LOGS, progress) { lokiFlush.flush(controlHost, clusterState, progress) } }
                .onFailure { failed[TailSignal.LOGS] = it as FlushStepFailed }
        }
        val sendersProgress = FlushProgress(FlushStep.SENDERS_STOP, Constants.K8s.OTEL_COLLECTOR_APP_LABEL).also(progresses::add)
        val sendersStop = attempt(sendersProgress) { stopSenders(controlHost, sendersProgress) }

        if (TailSignal.METRICS in pending) {
            val progress = FlushProgress(FlushStep.MIMIR_SHUTDOWN, Constants.K8s.MIMIR_APP_LABEL).also(progresses::add)
            tasks += SaveTask(TailSignal.METRICS, progress) { mimirFlush.flush(controlHost, clusterState, progress) }
        }
        if (TailSignal.TRACES in pending) {
            // The collector is Tempo's only sender: while it may still run, Tempo cannot drain.
            sendersStop.exceptionOrNull()?.let { failed[TailSignal.TRACES] = it as FlushStepFailed }
            if (sendersStop.isSuccess) {
                val progress = FlushProgress(FlushStep.TEMPO_LIVE_TRACES, Constants.K8s.TEMPO_APP_LABEL).also(progresses::add)
                tasks +=
                    SaveTask(TailSignal.TRACES, progress) {
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
                    val backup =
                        annotationBackupService.backup(controlHost, clusterState).getOrElse { failure ->
                            throw IllegalStateException(failure.message ?: failure.toString(), failure)
                        }
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

    /** Mirrors every Grafana annotation to Loki. */
    private fun mirrorToLoki(controlHost: ClusterHost) {
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
            SignalReport.Logs -> eventBus.emit(Event.Teardown.LokiFlushed)
            SignalReport.Metrics -> eventBus.emit(Event.Teardown.MimirFlushed)
            SignalReport.Traces -> eventBus.emit(Event.Teardown.TempoFlushed)
            SignalReport.Profiles, is SignalReport.Annotations -> Unit
        }
    }

    /**
     * Runs [block], turning a step's failure into a [FlushStepFailed] at [progress]'s step. A step
     * fails with a failed check or timeout ([IllegalStateException]), an HTTP or file error
     * ([IOException]), a Kubernetes API error, or a failed SSH or remote command.
     */
    private fun <T> attempt(
        progress: FlushProgress,
        block: () -> T,
    ): Result<T> {
        fun failed(failure: Exception) = Result.failure<T>(FlushStepFailed(progress.step, progress.backends, failure))
        return try {
            Result.success(block())
        } catch (failure: IllegalStateException) {
            failed(failure)
        } catch (failure: IOException) {
            failed(failure)
        } catch (failure: KubernetesClientException) {
            failed(failure)
        } catch (failure: EasyDBLabException) {
            failed(failure)
        }
    }
}
