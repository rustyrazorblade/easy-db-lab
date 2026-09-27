package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
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

    private inner class FakeWorkloads(
        private val states: Map<String, BackendState> = emptyMap(),
    ) : BackendWorkloads {
        override fun scaleDown(
            controlHost: ClusterHost,
            workload: String,
            timeout: Duration,
        ) = error("the orchestrator never scales a backend itself")

        override fun state(
            controlHost: ClusterHost,
            workload: String,
        ): BackendState = states[workload] ?: BackendState.RUNNING
    }

    private var mirrorFailure: Throwable? = null
    private var sendersFailure: Throwable? = null

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
                controlHost: ClusterHost,
                timeout: Duration,
            ) {
                order += "senders stop"
                sendersFailure?.let { throw it }
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
        loki: SignalFlush = FakeFlush("loki", SignalReport.Logs(1, 2), "loki"),
        mimir: SignalFlush = FakeFlush("mimir", SignalReport.Metrics(3), "mimir"),
        tempo: SignalFlush = FakeFlush("tempo", SignalReport.Traces(4), "tempo"),
        workloads: BackendWorkloads = FakeWorkloads(),
    ) = DefaultTeardownFlushService(loki, mimir, tempo, workloads, senders, mirror, backups, eventBus)

    private fun failing(
        name: String,
        workload: String,
        message: String,
    ) = FakeFlush(name, SignalReport.Profiles, workload) { error(message) }

    @Test
    fun `every signal is saved, and each backend is left as its step left it`() {
        val outcome = service().saveTail(control, state, all)

        assertThat(outcome.failed).isEmpty()
        assertThat(outcome.saved).containsOnly(
            entry(TailSignal.LOGS, SignalReport.Logs(1, 2)),
            entry(TailSignal.METRICS, SignalReport.Metrics(3)),
            entry(TailSignal.TRACES, SignalReport.Traces(4)),
            entry(TailSignal.PROFILES, SignalReport.Profiles),
            entry(TailSignal.ANNOTATIONS, SignalReport.Annotations("grafana/annotations/x.json")),
        )
        assertThat(outcome.backends)
            .containsEntry("loki", BackendState.SCALED_TO_ZERO)
            .containsEntry("mimir", BackendState.SCALED_TO_ZERO)
            .containsEntry("otel-collector", BackendState.DELETED)
        assertThat(
            events,
        ).contains(Event.Teardown.TelemetrySendersStopped, Event.Teardown.ProfilesNeedNoFlush, Event.Teardown.TempoFlushed(4))
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
            FakeFlush("loki", SignalReport.Logs(1, 2), "loki") {
                lokiStarted.countDown()
                check(mimirStarted.await(5, TimeUnit.SECONDS)) { "Mimir's flush never started while Loki's ran" }
            }
        val mimir =
            FakeFlush("mimir", SignalReport.Metrics(3), "mimir") {
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
                mimir = failing("mimir", "mimir", "Mimir blocks are not in S3: acme/01HB"),
                tempo = failing("tempo", "tempo", "Tempo still receives or holds traces"),
            ).saveTail(control, state, all)

        assertThat(outcome.saved.keys).containsExactlyInAnyOrder(TailSignal.LOGS, TailSignal.PROFILES, TailSignal.ANNOTATIONS)
        assertThat(outcome.failed.keys).containsExactlyInAnyOrder(TailSignal.METRICS, TailSignal.TRACES)
        assertThat(outcome.failed.getValue(TailSignal.METRICS)).hasMessageContaining("acme/01HB")
        assertThat(outcome.failed.getValue(TailSignal.TRACES)).hasMessageContaining("Tempo still receives")
        assertThat(outcome.failed.getValue(TailSignal.METRICS).backends).containsEntry("mimir", BackendState.SCALED_TO_ZERO)
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
    fun `a Loki an earlier down stopped fails logs with that cause, and the mirror does not run`() {
        val outcome = service(workloads = FakeWorkloads(mapOf("loki" to BackendState.SCALED_TO_ZERO))).saveTail(control, state, all)

        val logs = outcome.failed.getValue(TailSignal.LOGS)
        assertThat(logs.step).isEqualTo(FlushStep.LOKI_RUNNING)
        assertThat(logs).hasMessageContaining("Loki was stopped by an earlier `down`")
        assertThat(order).doesNotContain("mirror", "loki start")
        assertThat(outcome.saved.keys).contains(TailSignal.METRICS, TailSignal.TRACES)
    }

    @Test
    fun `a failed collector stop fails traces and skips the drain, and the other flushes still run`() {
        sendersFailure = IllegalStateException("otel-collector still has 1 pod(s)")

        val outcome = service().saveTail(control, state, all)

        assertThat(outcome.failed.getValue(TailSignal.TRACES).step).isEqualTo(FlushStep.SENDERS_STOP)
        assertThat(order).doesNotContain("tempo start").contains("loki start", "mimir start")
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
}
