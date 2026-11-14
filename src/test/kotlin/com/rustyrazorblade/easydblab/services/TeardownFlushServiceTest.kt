package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import io.fabric8.kubernetes.client.KubernetesClientException
import kotlinx.serialization.SerializationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.io.UncheckedIOException
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * How the pre-teardown save orders and runs its steps: Phase A in order, Phase B at once, every
 * step to completion, and one failure per signal. Each backend flush, Kubernetes, the collector,
 * the mirror and the backup are hand-written fakes that record what ran, in order.
 */
class TeardownFlushServiceTest {
    private val control = ClusterHost("54.0.0.1", "10.0.1.5", "control0", "us-west-2a")
    private val state = ClusterState(name = "lab", versions = mutableMapOf(), s3Bucket = "acct")
    private val all = TailSignal.entries.toSet()

    /** Every step that ran, in the order it started or finished. */
    private val order: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val events: MutableList<Event> = Collections.synchronizedList(mutableListOf())
    private val eventBus =
        EventBus().also {
            it.addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        events += envelope.event
                    }

                    override fun close() = Unit
                },
            )
        }

    /** A backend flush that records its start and end, and runs [body] in between. */
    private inner class FakeFlush(
        private val name: String,
        private val report: SignalReport,
        private val workload: String,
        private val body: () -> Unit = {},
    ) : SignalFlush {
        override fun flush(
            controlHost: ClusterHost,
            clusterState: ClusterState,
            progress: FlushProgress,
        ): SignalReport {
            order += "$name start"
            progress.mark(workload, BackendState.SCALED_TO_ZERO)
            body()
            order += "$name end"
            return report
        }
    }

    private var mirrorFailure: Throwable? = null
    private var sendersFailure: Throwable? = null

    /** The collector whose stop fails after its delete, as one whose pods outlive the deadline. */
    private var collectorThatDoesNotDrain: String? = null

    /** The collectors with no DaemonSet when the stop runs, as the add-on where pods may not be privileged. */
    private var absentCollectors: Set<String> = emptySet()

    private val mirror =
        object : AnnotationMirror by RecordingAnnotationMirror() {
            override fun syncAll(controlHost: ClusterHost): Result<Int> {
                order += "mirror"
                return mirrorFailure?.let { Result.failure(it) } ?: Result.success(3)
            }
        }

    private val senders =
        object : TelemetrySenders {
            override fun stop(
                timeout: Duration,
                onAbsent: (String) -> Unit,
                onDeleting: (String) -> Unit,
            ) {
                order += "senders stop"
                sendersFailure?.let { throw it }
                // As K8sTelemetrySenders does: each collector in order, announced as absent or just
                // before its delete, and a collector that does not drain ends the stop there.
                for (name in TelemetrySenders.COLLECTORS) {
                    if (name in absentCollectors) {
                        onAbsent(name)
                        continue
                    }
                    onDeleting(name)
                    if (name == collectorThatDoesNotDrain) error("$name still has 1 pod(s)")
                }
            }
        }

    private val backups =
        object : GrafanaAnnotationBackupService {
            override fun backup(
                controlHost: ClusterHost,
                clusterState: ClusterState,
            ): Result<GrafanaAnnotationBackupResult> {
                order += "annotations backup"
                return Result.success(GrafanaAnnotationBackupResult(ClusterS3Path.root("acct").resolve("grafana/annotations/x.json"), 2))
            }
        }

    private fun service(
        loki: SignalFlush = FakeFlush("loki", SignalReport.Logs, "loki"),
        mimir: SignalFlush = FakeFlush("mimir", SignalReport.Metrics, "mimir"),
        tempo: SignalFlush = FakeFlush("tempo", SignalReport.Traces, "tempo"),
    ) = DefaultTeardownFlushService
        .builder()
        .lokiFlush(loki)
        .mimirFlush(mimir)
        .tempoDrain(tempo)
        .telemetrySenders(senders)
        .annotationMirror(mirror)
        .annotationBackupService(backups)
        .eventBus(eventBus)
        .build()

    private fun failing(
        name: String,
        workload: String,
        message: String,
    ) = FakeFlush(name, SignalReport.Profiles, workload) { error(message) }

    /** A flush of [report] from [name] that throws [exception]. */
    private fun throwing(
        name: String,
        report: SignalReport,
        exception: Exception,
    ) = FakeFlush(name, report, name) { throw exception }

    @Test
    fun `every signal is saved, and each backend is left as its step left it`() {
        val outcome = service().saveTail(control, state, all)

        assertThat(outcome.failed).isEmpty()
        assertThat(outcome.saved).containsOnly(
            entry(TailSignal.LOGS, SignalReport.Logs),
            entry(TailSignal.METRICS, SignalReport.Metrics),
            entry(TailSignal.TRACES, SignalReport.Traces),
            entry(TailSignal.PROFILES, SignalReport.Profiles),
            entry(TailSignal.ANNOTATIONS, SignalReport.Annotations("grafana/annotations/x.json")),
        )
        assertThat(outcome.backends)
            .containsEntry("loki", BackendState.SCALED_TO_ZERO)
            .containsEntry("mimir", BackendState.SCALED_TO_ZERO)
            .containsAllEntriesOf(TelemetrySenders.COLLECTORS.associateWith { BackendState.DELETED })
        assertThat(events).contains(
            Event.Teardown.TelemetrySendersStopped,
            Event.Teardown.LokiFlushed,
            Event.Teardown.MimirFlushed,
            Event.Teardown.TempoFlushed,
            Event.Teardown.ProfilesNeedNoFlush,
        )
    }

    @Test
    fun `the mirror and the collector stop finish before any flush starts`() {
        service().saveTail(control, state, all)

        assertThat(order.take(2)).containsExactly("mirror", "senders stop")
        assertThat(order.drop(2)).contains("loki start", "mimir start", "tempo start", "annotations backup")
    }

    /** Each fake waits on a latch only the other releases: run one after the other, both would time out. */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `the flushes run at the same time`() {
        val lokiStarted = CountDownLatch(1)
        val mimirStarted = CountDownLatch(1)
        val loki =
            FakeFlush("loki", SignalReport.Logs, "loki") {
                lokiStarted.countDown()
                check(mimirStarted.await(5, TimeUnit.SECONDS)) { "Mimir's flush never started while Loki's ran" }
            }
        val mimir =
            FakeFlush("mimir", SignalReport.Metrics, "mimir") {
                mimirStarted.countDown()
                check(lokiStarted.await(5, TimeUnit.SECONDS)) { "Loki's flush never started while Mimir's ran" }
            }

        val outcome = service(loki = loki, mimir = mimir).saveTail(control, state, all)

        assertThat(outcome.failed).isEmpty()
    }

    @Test
    fun `one failing signal leaves every other signal saved, and every failure is reported`() {
        val outcome =
            service(
                mimir = failing("mimir", "mimir", "Mimir's ingester shutdown failed (status 503)"),
                tempo = failing("tempo", "tempo", "Tempo still receives or holds traces"),
            ).saveTail(control, state, all)

        assertThat(outcome.saved.keys).containsExactlyInAnyOrder(TailSignal.LOGS, TailSignal.PROFILES, TailSignal.ANNOTATIONS)
        assertThat(outcome.failed.keys).containsExactlyInAnyOrder(TailSignal.METRICS, TailSignal.TRACES)
        assertThat(outcome.failed.getValue(TailSignal.METRICS)).hasMessageContaining("status 503")
        assertThat(outcome.failed.getValue(TailSignal.TRACES)).hasMessageContaining("Tempo still receives")
        assertThat(outcome.failed.getValue(TailSignal.METRICS).backends).containsEntry("mimir", BackendState.SCALED_TO_ZERO)
    }

    /** An HTTP error, a Kubernetes API error and a failed remote command fail their signal; they do not escape the save. */
    @Test
    fun `an HTTP or Kubernetes error fails only its signal`() {
        val outcome =
            service(
                loki = throwing("loki", SignalReport.Logs, IOException("connection reset")),
                mimir = throwing("mimir", SignalReport.Metrics, KubernetesClientException("forbidden")),
                tempo =
                    throwing(
                        "tempo",
                        SignalReport.Traces,
                        RemoteCommandFailedException("curl tempo", "", "connection refused", "Tempo's readiness check failed"),
                    ),
            ).saveTail(control, state, all)

        assertThat(outcome.failed.keys).containsExactlyInAnyOrder(TailSignal.LOGS, TailSignal.METRICS, TailSignal.TRACES)
        assertThat(outcome.failed.getValue(TailSignal.LOGS)).hasMessageContaining("connection reset")
        assertThat(outcome.failed.getValue(TailSignal.METRICS)).hasMessageContaining("forbidden")
        assertThat(outcome.failed.getValue(TailSignal.TRACES)).hasMessageContaining("Tempo's readiness check failed")
        assertThat(outcome.saved.keys).containsExactlyInAnyOrder(TailSignal.PROFILES, TailSignal.ANNOTATIONS)
    }

    /** A failure of any other type still fails only its signal: it never escapes and loses the others. */
    @Test
    fun `an unexpected exception fails only its signal`() {
        val outcome =
            service(
                mimir = FakeFlush("mimir", SignalReport.Metrics, "mimir") { throw UnsupportedOperationException("unexpected") },
            ).saveTail(control, state, all)

        assertThat(outcome.failed.keys).containsExactly(TailSignal.METRICS)
        assertThat(outcome.failed.getValue(TailSignal.METRICS).step).isEqualTo(FlushStep.MIMIR_SHUTDOWN)
        assertThat(outcome.failed.getValue(TailSignal.METRICS)).hasMessageContaining("unexpected")
        assertThat(outcome.failed.getValue(TailSignal.METRICS).backends).containsEntry("mimir", BackendState.SCALED_TO_ZERO)
        assertThat(outcome.saved.keys).contains(TailSignal.LOGS, TailSignal.TRACES, TailSignal.ANNOTATIONS)
    }

    @Test
    fun `a record that fails with an unexpected exception fails only its signal`() {
        val outcome =
            service().saveTail(control, state, all) { signal, _ ->
                if (signal == TailSignal.METRICS) throw SerializationException("state.json could not be encoded")
            }

        assertThat(outcome.failed.keys).containsExactly(TailSignal.METRICS)
        assertThat(outcome.failed.getValue(TailSignal.METRICS).step).isEqualTo(FlushStep.RECORD_METRICS)
        assertThat(outcome.saved.keys).contains(TailSignal.LOGS, TailSignal.TRACES, TailSignal.ANNOTATIONS)
    }

    @Test
    fun `an unexpected exception from the collector stop fails only traces`() {
        sendersFailure = UncheckedIOException(IOException("socket closed"))

        val outcome = service().saveTail(control, state, all)

        assertThat(outcome.failed.keys).containsExactly(TailSignal.TRACES)
        assertThat(outcome.failed.getValue(TailSignal.TRACES).step).isEqualTo(FlushStep.SENDERS_STOP)
        assertThat(outcome.saved.keys).contains(TailSignal.LOGS, TailSignal.METRICS, TailSignal.ANNOTATIONS)
    }

    @Test
    fun `a failed annotations backup fails only annotations`() {
        val failingBackups =
            object : GrafanaAnnotationBackupService {
                override fun backup(
                    controlHost: ClusterHost,
                    clusterState: ClusterState,
                ): Result<GrafanaAnnotationBackupResult> = Result.failure(RuntimeException("S3 refused the upload"))
            }

        val outcome =
            DefaultTeardownFlushService
                .builder()
                .lokiFlush(FakeFlush("loki", SignalReport.Logs, "loki"))
                .mimirFlush(FakeFlush("mimir", SignalReport.Metrics, "mimir"))
                .tempoDrain(FakeFlush("tempo", SignalReport.Traces, "tempo"))
                .telemetrySenders(senders)
                .annotationMirror(mirror)
                .annotationBackupService(failingBackups)
                .eventBus(eventBus)
                .build()
                .saveTail(control, state, all)

        assertThat(outcome.failed.keys).containsExactly(TailSignal.ANNOTATIONS)
        assertThat(outcome.failed.getValue(TailSignal.ANNOTATIONS)).hasMessageContaining("S3 refused the upload")
    }

    @Test
    fun `a failed mirror fails only logs, skips the Loki flush, and leaves Loki running`() {
        mirrorFailure = IllegalStateException("Loki refused the push with status 500")

        val outcome = service().saveTail(control, state, all)

        val logs = outcome.failed.getValue(TailSignal.LOGS)
        assertThat(logs.step).isEqualTo(FlushStep.ANNOTATION_MIRROR)
        assertThat(logs).hasMessageContaining("Loki refused the push").hasMessageContaining("the Loki flush was not run")
        assertThat(order).doesNotContain("loki start").contains("mimir start", "tempo start", "annotations backup")
        assertThat(outcome.backends).containsEntry("loki", BackendState.RUNNING)
        assertThat(outcome.saved.keys).contains(TailSignal.METRICS, TailSignal.TRACES, TailSignal.ANNOTATIONS)
    }

    @Test
    fun `the drain start is announced with its timeout before Tempo's drain runs`() {
        val starting = Event.Teardown.TempoDrainStarting(Constants.TeardownFlush.TEMPO_DRAIN_TIMEOUT_SECONDS)
        var announcedBeforeDrain = false
        val tempo = FakeFlush("tempo", SignalReport.Traces, "tempo") { announcedBeforeDrain = starting in events }

        val outcome = service(tempo = tempo).saveTail(control, state, all)

        assertThat(outcome.failed).isEmpty()
        assertThat(announcedBeforeDrain).isTrue()
    }

    @Test
    fun `a failed collector stop fails traces and skips the drain, and the other flushes still run`() {
        sendersFailure = IllegalStateException("otel-collector still has 1 pod(s)")

        val outcome = service().saveTail(control, state, all)

        assertThat(outcome.failed.getValue(TailSignal.TRACES).step).isEqualTo(FlushStep.SENDERS_STOP)
        assertThat(order).doesNotContain("tempo start").contains("loki start", "mimir start")
    }

    /**
     * The add-on is stopped first; when its pods do not go, the core is never deleted, so the
     * report must say the core still runs rather than that both were deleted.
     */
    @Test
    fun `an add-on that does not drain reports the core still running`() {
        collectorThatDoesNotDrain = Constants.OtelCollector.HOST_COLLECTOR_NAME

        val outcome = service().saveTail(control, state, all)

        assertThat(outcome.failed.getValue(TailSignal.TRACES).step).isEqualTo(FlushStep.SENDERS_STOP)
        assertThat(outcome.backends)
            .containsEntry(Constants.OtelCollector.HOST_COLLECTOR_NAME, BackendState.DELETED)
            .containsEntry(Constants.K8s.OTEL_COLLECTOR_APP_LABEL, BackendState.RUNNING)
    }

    /** An add-on that was never deployed was not deleted by the save: the report says it is absent. */
    @Test
    fun `a collector with no DaemonSet is reported absent, not deleted`() {
        absentCollectors = setOf(Constants.OtelCollector.HOST_COLLECTOR_NAME)

        val outcome = service().saveTail(control, state, all)

        assertThat(outcome.backends)
            .containsEntry(Constants.OtelCollector.HOST_COLLECTOR_NAME, BackendState.ABSENT)
            .containsEntry(Constants.K8s.OTEL_COLLECTOR_APP_LABEL, BackendState.DELETED)
    }

    /**
     * The collector stop runs whatever is pending, so its failure must fail the save even when
     * traces are not among the pending signals; otherwise `down` would tear down past it.
     */
    @Test
    fun `a failed collector stop fails the save even when traces are not pending`() {
        sendersFailure = KubernetesClientException("connection refused")

        val outcome = service().saveTail(control, state, all - TailSignal.TRACES)

        val failure = outcome.failed.values.single { it.step == FlushStep.SENDERS_STOP }
        assertThat(failure).hasMessageContaining("connection refused")
        assertThat(failure.backends).containsAllEntriesOf(TelemetrySenders.COLLECTORS.associateWith { BackendState.RUNNING })
    }

    @Test
    fun `a stop that fails before any delete reports every collector still running`() {
        sendersFailure = KubernetesClientException("connection refused")

        val outcome = service().saveTail(control, state, all)

        assertThat(outcome.backends).containsAllEntriesOf(TelemetrySenders.COLLECTORS.associateWith { BackendState.RUNNING })
    }

    @Test
    fun `signals already recorded are never run, and a newly saved one is handed over to be recorded`() {
        val recorded = Collections.synchronizedList(mutableListOf<TailSignal>())

        val outcome =
            service().saveTail(control, state, all - TailSignal.LOGS) { signal, _ ->
                recorded += signal
                order += "record $signal"
            }

        assertThat(order).doesNotContain("mirror", "loki start")
        assertThat(outcome.saved.keys).doesNotContain(TailSignal.LOGS)
        assertThat(recorded).containsExactly(TailSignal.METRICS)
        assertThat(order.indexOf("record METRICS")).isGreaterThan(order.indexOf("mimir end"))
    }

    @Test
    fun `a record that cannot be written fails its signal at the record step`() {
        val outcome = service().saveTail(control, state, all) { _, _ -> error("disk full") }

        assertThat(outcome.failed.keys).containsExactlyInAnyOrder(TailSignal.LOGS, TailSignal.METRICS)
        assertThat(outcome.failed.getValue(TailSignal.LOGS).step).isEqualTo(FlushStep.RECORD_LOGS)
        assertThat(outcome.failed.getValue(TailSignal.METRICS).step).isEqualTo(FlushStep.RECORD_METRICS)
        assertThat(outcome.saved.keys).contains(TailSignal.TRACES, TailSignal.ANNOTATIONS)
    }

    /** `down` prints each owned workload's state on failure, so every owner is reported on its own. */
    @Test
    fun `a step with several workloads reports each one and rejects a workload it does not own`() {
        val progress = FlushProgress(FlushStep.SENDERS_STOP, setOf("otel-collector", "otel-collector-host"))
        assertThat(progress.backends).containsOnly(
            entry("otel-collector", BackendState.RUNNING),
            entry("otel-collector-host", BackendState.RUNNING),
        )

        progress.mark("otel-collector-host", BackendState.DELETED)

        assertThat(progress.backends).containsOnly(
            entry("otel-collector", BackendState.RUNNING),
            entry("otel-collector-host", BackendState.DELETED),
        )
        assertThatThrownBy { progress.mark("loki", BackendState.DELETED) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("loki")
    }
}
