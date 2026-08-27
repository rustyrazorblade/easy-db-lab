package com.rustyrazorblade.easydblab.commands.cassandra.profiler

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.commands.cassandra.Cassandra
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.profiling.DesiredProfilingState
import com.rustyrazorblade.easydblab.profiling.ProfilingConfig
import com.rustyrazorblade.easydblab.profiling.ProfilingEffectiveState
import com.rustyrazorblade.easydblab.services.CassandraProfilingService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import picocli.CommandLine
import java.io.File
import java.nio.file.Path
import java.time.Instant

/**
 * Tests for the `cassandra profile` command group.
 *
 * [CassandraProfilingService] is mocked — these tests are about what the commands decide before and
 * around the SSH round-trip: rejecting reserved arguments before touching a node, assembling the
 * desired-state document, scoping to the right hosts, and surfacing node-reported failures as typed
 * events. The service's own command spelling is covered by its own test.
 */
class ProfilingCommandsTest : BaseKoinTest() {
    private lateinit var profilingService: CassandraProfilingService
    private val emitted = mutableListOf<Event>()

    private val hosts =
        mapOf(
            ServerType.Control to
                listOf(
                    ClusterHost(
                        publicIp = "54.0.0.1",
                        privateIp = "10.0.1.5",
                        alias = "control0",
                        availabilityZone = "us-west-2a",
                    ),
                ),
            ServerType.Cassandra to
                listOf(
                    ClusterHost(
                        publicIp = "54.1.2.3",
                        privateIp = "10.0.1.100",
                        alias = "db0",
                        availabilityZone = "us-west-2a",
                    ),
                    ClusterHost(
                        publicIp = "54.1.2.4",
                        privateIp = "10.0.1.101",
                        alias = "db1",
                        availabilityZone = "us-west-2b",
                    ),
                ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(
                            ClusterState(
                                name = "test-cluster",
                                clusterId = "abc123",
                                versions = mutableMapOf(),
                                hosts = hosts,
                            ),
                        )
                        whenever(it.exists()).thenReturn(true)
                    }
                }
                single { HostOperationsService(get()) }
                single<CassandraProfilingService> { mock<CassandraProfilingService>().also { profilingService = it } }
            },
        )

    @BeforeEach
    fun setup() {
        profilingService = getKoin().get()
        // The service never returns "no answer" for this read — an unconfigured node is a value.
        whenever(profilingService.readDesiredState(any())).thenReturn(DesiredProfilingState.Unconfigured)
        emitted.clear()
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    emitted += envelope.event
                }

                override fun close() = Unit
            },
        )
    }

    private fun capturedConfigs(): List<ProfilingConfig> {
        val captor = argumentCaptor<ProfilingConfig>()
        verify(profilingService, org.mockito.kotlin.atLeastOnce()).writeDesiredState(any(), captor.capture())
        return captor.allValues
    }

    // --- command naming ------------------------------------------------------

    @Test
    fun `both the command name and its legacy alias route to the same subcommand`() {
        // The group was renamed from `profiler` to `profile`, and the old spelling is still in
        // operator muscle memory, in scripts and in every lab plan written before the rename. An
        // alias that silently stopped resolving would fail those with "Unmatched argument".
        val cassandra = CommandLine(Cassandra())

        val viaName = cassandra.parseArgs("profile", "status").subcommand().subcommand()
        val viaAlias = cassandra.parseArgs("profiler", "status").subcommand().subcommand()

        assertThat(viaName.commandSpec().userObject()).isInstanceOf(ProfilingStatus::class.java)
        assertThat(viaAlias.commandSpec().userObject())
            .describedAs("the alias must reach the same command, not a second registration")
            .isSameAs(viaName.commandSpec().userObject())
    }

    @Test
    fun `every profiling subcommand is reachable under both spellings`() {
        val cassandra = CommandLine(Cassandra())
        val verbs = listOf("start", "stop", "status", "fetch", "flamegraph")

        verbs.forEach { verb ->
            val underName = cassandra.parseArgs("profile", verb).subcommand().subcommand()
            val underAlias = cassandra.parseArgs("profiler", verb).subcommand().subcommand()

            assertThat(underAlias.commandSpec().userObject())
                .describedAs("`cassandra profiler %s` must resolve to `cassandra profile %s`", verb, verb)
                .isSameAs(underName.commandSpec().userObject())
        }
    }

    // --- start ---------------------------------------------------------------

    @Test
    fun `start rejects a reserved argument before contacting any node`() {
        val command = ProfilingStart()
        command.asprofArgs = listOf("-e", "cpu", "-f", "/tmp/mine.jfr")

        assertThatThrownBy { command.execute() }
            .hasMessageContaining("-f")
            .hasMessageContaining("--loop")

        verify(profilingService, never()).writeDesiredState(any(), any())
    }

    @Test
    fun `start rejects a reserved argument smuggled after a comma`() {
        val command = ProfilingStart()
        command.asprofArgs = listOf("-e", "cpu,file=/tmp/elsewhere.jfr")

        assertThatThrownBy { command.execute() }.hasMessageContaining("file=/tmp/elsewhere.jfr")
        verify(profilingService, never()).writeDesiredState(any(), any())
    }

    @Test
    fun `start refuses the wall-clock batching switch and explains the corruption`() {
        // asprof rejected --nobatch itself at 4.3 and accepts it at 4.5, so the guard has to live
        // here now. The message must describe the event-type change, not the output plumbing.
        val command = ProfilingStart()
        command.asprofArgs = listOf("-e", "wall", "--nobatch")

        assertThatThrownBy { command.execute() }
            .hasMessageContaining("--nobatch")
            .hasMessageContaining("jdk.ExecutionSample")

        verify(profilingService, never()).writeDesiredState(any(), any())
    }

    @Test
    fun `start refuses a negative retention window, which the node would reject wholesale`() {
        // The node's reconciler requires a non-negative integer and discards the *entire* document
        // otherwise, so a negative window here would report "profiling enabled" on every node and
        // then never attach, with nothing anywhere saying why.
        val command = ProfilingStart()
        command.retentionMinutes = -5

        assertThatThrownBy { command.execute() }.hasMessageContaining("--retention")

        verify(profilingService, never()).writeDesiredState(any(), any())
    }

    @Test
    fun `start refuses a zero retention window, which prunes every chunk as it lands`() {
        // Zero passes the node's own validation and sets the prune cutoff to now, so every pass
        // deletes every chunk it just collected — profiling that cannot produce a profile.
        val command = ProfilingStart()
        command.retentionMinutes = 0

        assertThatThrownBy { command.execute() }.hasMessageContaining("--retention")

        verify(profilingService, never()).writeDesiredState(any(), any())
    }

    @Test
    fun `start refuses a byte ceiling below one byte`() {
        val command = ProfilingStart()
        command.maxBytes = 0

        assertThatThrownBy { command.execute() }.hasMessageContaining("--max-bytes")

        verify(profilingService, never()).writeDesiredState(any(), any())
    }

    @Test
    fun `start refuses a rotation interval carrying a quote, which would wreck the node's state document`() {
        // The node interpolates this value straight into its effective-state JSON. A quote here
        // produces a document that never parses again, rewritten identically every pass with nothing
        // logged — so `status` answers "unknown" for that node forever and every attach failure,
        // ship failure and rejection on it becomes invisible.
        val command = ProfilingStart()
        command.loopInterval = "30s\""

        assertThatThrownBy { command.execute() }
            .hasMessageContaining("--loop")
            .hasMessageContaining("30s\"")

        verify(profilingService, never()).writeDesiredState(any(), any())
    }

    @Test
    fun `start refuses a rotation interval that is not an async-profiler duration`() {
        val command = ProfilingStart()
        command.loopInterval = "every minute"

        assertThatThrownBy { command.execute() }.hasMessageContaining("--loop")

        verify(profilingService, never()).writeDesiredState(any(), any())
    }

    @Test
    fun `start refuses a zero rotation interval`() {
        val command = ProfilingStart()
        command.loopInterval = "0s"

        assertThatThrownBy { command.execute() }.hasMessageContaining("--loop")

        verify(profilingService, never()).writeDesiredState(any(), any())
    }

    @Test
    fun `start refuses a time-of-day rotation and says what to use instead`() {
        // asprof --loop takes this form, so it is copied straight out of async-profiler's own docs.
        // It rotates once a day, and this tool ships every completed chunk continuously and sizes
        // the completion and upload windows from a fixed interval — so it is a configuration that
        // looks valid and produces no usable profiles. Refusing it silently would be no better than
        // accepting it, hence the assertion on what the message offers.
        val command = ProfilingStart()
        command.loopInterval = "02:30:00"

        assertThatThrownBy { command.execute() }
            .hasMessageContaining("--loop")
            .hasMessageContaining("02:30:00")
            .hasMessageContaining("30s, 5m, 1h")

        verify(profilingService, never()).writeDesiredState(any(), any())
    }

    @Test
    fun `start accepts every rotation interval spelling the node can act on`() {
        // Bare seconds and each unit suffix. Deliberately not asprof's full surface: the hh:mm:ss
        // time of day is refused above.
        val intervals = listOf("30", "30s", "5m", "1h")
        intervals.forEach { interval ->
            val command = ProfilingStart()
            command.loopInterval = interval
            // A day of retention, because the window has to hold the rotation: the default 60
            // minutes is genuinely too short for --loop 1h, which is what the bounds check refuses.
            command.retentionMinutes = 1440
            command.execute()
        }

        // Each run writes to both database nodes, so the intervals repeat; the set is the assertion.
        assertThat(capturedConfigs().map { it.loopInterval }.distinct())
            .containsExactlyInAnyOrderElementsOf(intervals)
    }

    @Test
    fun `start records the desired state built from the options and the cluster`() {
        val command = ProfilingStart()
        command.asprofArgs = listOf("-e", "wall", "-i", "10ms")
        command.loopInterval = "30s"
        command.retentionMinutes = 15
        command.maxBytes = 8L * 1024 * 1024

        command.execute()

        val config = capturedConfigs().first()
        assertThat(config.enabled).isTrue()
        assertThat(config.asprofArgs).containsExactly("-e", "wall", "-i", "10ms")
        assertThat(config.loopInterval).isEqualTo("30s")
        assertThat(config.retentionMinutes).isEqualTo(15)
        assertThat(config.maxBytes).isEqualTo(8L * 1024 * 1024)
        assertThat(config.pyroscopeUrl).isEqualTo("http://10.0.1.5:${Constants.K8s.PYROSCOPE_PORT}")
        assertThat(config.clusterName).isEqualTo("test-cluster-abc123")
        assertThat(config.updatedAt).isNotEmpty()
    }

    @Test
    fun `start applies to every Cassandra node by default`() {
        ProfilingStart().execute()

        val targeted = argumentCaptor<Host>()
        verify(profilingService, org.mockito.kotlin.times(2)).writeDesiredState(targeted.capture(), any())
        assertThat(targeted.allValues.map { it.alias }).containsExactly("db0", "db1")
    }

    @Test
    fun `start applies only to the selected nodes`() {
        val command = ProfilingStart()
        command.hosts.hostList = "db1"

        command.execute()

        val targeted = argumentCaptor<Host>()
        verify(profilingService).writeDesiredState(targeted.capture(), any())
        assertThat(targeted.firstValue.alias).isEqualTo("db1")
    }

    @Test
    fun `start defaults to CPU profiling`() {
        ProfilingStart().execute()

        assertThat(capturedConfigs().first().asprofArgs).isEqualTo(Constants.Profiling.DEFAULT_ASPROF_ARGS)
    }

    @Test
    fun `start emits a typed started event per node`() {
        ProfilingStart().execute()

        val started = emitted.filterIsInstance<Event.Profiling.Started>()
        assertThat(started.map { it.host }).containsExactly("db0", "db1")
    }

    // --- stop ----------------------------------------------------------------

    @Test
    fun `stop records an explicit disabled state rather than removing the document`() {
        ProfilingStop().execute()

        val configs = capturedConfigs()
        assertThat(configs).hasSize(2)
        assertThat(configs).allMatch { !it.enabled }
        assertThat(emitted.filterIsInstance<Event.Profiling.Stopped>().map { it.host })
            .containsExactly("db0", "db1")
    }

    @Test
    fun `stop preserves the retention the operator started with`() {
        // Resetting retention to the default on stop would make the reconciler's next pass prune
        // away exactly the profiles the operator stopped in order to collect.
        whenever(profilingService.readDesiredState(any())).thenReturn(
            DesiredProfilingState.Configured(
                ProfilingConfig(
                    enabled = true,
                    asprofArgs = listOf("-e", "wall"),
                    loopInterval = "30s",
                    retentionMinutes = 600,
                    maxBytes = 42_000_000_000,
                    pyroscopeUrl = "http://10.0.1.5:4040",
                    clusterName = "test-cluster-abc123",
                    updatedAt = "2026-08-24T10:15:30Z",
                ),
            ),
        )

        ProfilingStop().execute()

        val config = capturedConfigs().first()
        assertThat(config.enabled).isFalse()
        assertThat(config.retentionMinutes).isEqualTo(600)
        assertThat(config.maxBytes).isEqualTo(42_000_000_000)
        assertThat(config.loopInterval).isEqualTo("30s")
        assertThat(config.updatedAt).isNotEqualTo("2026-08-24T10:15:30Z")
    }

    @Test
    fun `stop falls back to defaults on a node that was never configured`() {
        whenever(profilingService.readDesiredState(any())).thenReturn(DesiredProfilingState.Unconfigured)

        ProfilingStop().execute()

        val config = capturedConfigs().first()
        assertThat(config.enabled).isFalse()
        assertThat(config.retentionMinutes).isEqualTo(Constants.Profiling.DEFAULT_RETENTION_MINUTES)
        assertThat(config.pyroscopeUrl).isEqualTo("http://10.0.1.5:${Constants.K8s.PYROSCOPE_PORT}")
        assertThat(config.clusterName).isEqualTo("test-cluster-abc123")

        // Nothing was lost, so nothing to report.
        assertThat(emitted.filterIsInstance<Event.Profiling.DesiredStateUnreadable>()).isEmpty()
    }

    @Test
    fun `stop says so when an unreadable document forces it back to default bounds`() {
        // Falling back here does the very thing this command exists to avoid — resetting retention
        // and the byte ceiling — so it must not happen silently. Otherwise the next reconcile pass
        // prunes to the 60-minute default and the operator has no way to know why.
        whenever(profilingService.readDesiredState(any()))
            .thenReturn(DesiredProfilingState.Unreadable("""{"enabled": true, "asprofArgs": ["-e","cp"""))

        ProfilingStop().execute()

        val warnings = emitted.filterIsInstance<Event.Profiling.DesiredStateUnreadable>()
        assertThat(warnings.map { it.host }).containsExactly("db0", "db1")
        assertThat(warnings.first().retentionMinutes).isEqualTo(Constants.Profiling.DEFAULT_RETENTION_MINUTES)
        assertThat(warnings.first().isError()).isTrue()

        // ...and it still stops, because leaving a profiler attached would be worse.
        assertThat(capturedConfigs()).allMatch { !it.enabled }
    }

    // --- status --------------------------------------------------------------

    @Test
    fun `status surfaces shipping failures and rejections as separate typed events`() {
        whenever(profilingService.readEffectiveState(any())).thenReturn(
            ProfilingEffectiveState(
                running = true,
                pid = 4242,
                args = listOf("-e", "cpu"),
                shipFailures = 3,
                chunksRejected = 2,
                lastError = "http_500",
            ),
        )

        ProfilingStatus().execute()

        assertThat(emitted.filterIsInstance<Event.Profiling.ShippingFailed>()).isNotEmpty()
        assertThat(emitted.filterIsInstance<Event.Profiling.ChunksRejected>()).isNotEmpty()
    }

    @Test
    fun `status stays quiet when a node is shipping cleanly`() {
        whenever(profilingService.readEffectiveState(any())).thenReturn(
            ProfilingEffectiveState(running = true, desiredEnabled = true, pid = 4242, args = listOf("-e", "cpu")),
        )

        ProfilingStatus().execute()

        assertThat(emitted.filterIsInstance<Event.Profiling.ShippingFailed>()).isEmpty()
        assertThat(emitted.filterIsInstance<Event.Profiling.ChunksRejected>()).isEmpty()
        assertThat(emitted.filterIsInstance<Event.Profiling.AttachFailed>()).isEmpty()
    }

    @Test
    fun `status reports a node that wants to profile but has nothing attached`() {
        // A node whose reconciler has been failing to attach every 60s for hours must not look
        // identical to one the operator deliberately stopped.
        whenever(profilingService.readEffectiveState(any())).thenReturn(
            ProfilingEffectiveState(
                running = false,
                desiredEnabled = true,
                pid = 4242,
                lastAttachError = "Could not open /tmp/.java_pid4242",
            ),
        )

        ProfilingStatus().execute()

        val failures = emitted.filterIsInstance<Event.Profiling.AttachFailed>()
        assertThat(failures.map { it.host }).containsExactly("db0", "db1")
        assertThat(failures.first().reason).isEqualTo("Could not open /tmp/.java_pid4242")
    }

    @Test
    fun `status blames the missing database process when there is no pid to attach to`() {
        whenever(profilingService.readEffectiveState(any())).thenReturn(
            ProfilingEffectiveState(running = false, desiredEnabled = true, pid = 0),
        )

        ProfilingStatus().execute()

        assertThat(emitted.filterIsInstance<Event.Profiling.AttachFailed>().first().reason)
            .contains("no Cassandra process")
    }

    @Test
    fun `status reports waiting for the database as a wait, not as a failed attach`() {
        // The node's reconciler declined to attach because the database was not yet ready to be
        // signalled — jattach's SIGQUIT kills a JVM that has not installed its handler. That is the
        // normal state for a pass or two after a restart, and it has the same shape on paper as the
        // feature's primary failure mode: enabled, nothing attached.
        whenever(profilingService.readEffectiveState(any())).thenReturn(
            ProfilingEffectiveState(
                running = false,
                desiredEnabled = true,
                pid = 4242,
                attachDeferred = true,
            ),
        )

        ProfilingStatus().execute()

        assertThat(emitted.filterIsInstance<Event.Profiling.AttachDeferred>().map { it.host })
            .containsExactly("db0", "db1")
        assertThat(emitted.filterIsInstance<Event.Profiling.AttachFailed>())
            .describedAs("a node waiting for its database is not a node that cannot attach")
            .isEmpty()
    }

    @Test
    fun `status says nothing is wrong when profiling is deliberately off`() {
        whenever(profilingService.readEffectiveState(any())).thenReturn(
            ProfilingEffectiveState(running = false, desiredEnabled = false, pid = 4242),
        )

        ProfilingStatus().execute()

        assertThat(emitted.filterIsInstance<Event.Profiling.AttachFailed>()).isEmpty()
    }

    // --- status: the report itself -------------------------------------------

    private val db0 = Host(public = "54.1.2.3", private = "10.0.1.100", alias = "db0", availabilityZone = "us-west-2a")

    @Test
    fun `status reports a node that has not reconciled yet as unknown`() {
        // Every node is in this state between cluster-up and the first reconcile pass.
        val report = ProfilingStatus().render(db0, null)

        assertThat(report).contains("db0")
        assertThat(report).contains("unknown")
    }

    @Test
    fun `status reports the user arguments verbatim and the full command as invoked`() {
        val report =
            ProfilingStatus().render(
                db0,
                ProfilingEffectiveState(
                    running = true,
                    desiredEnabled = true,
                    pid = 4242,
                    args = listOf("-e", "wall", "-i", "10ms"),
                    loopInterval = "30s",
                ),
            )

        assertThat(report).contains("-e wall -i 10ms")
        assertThat(report)
            .describedAs("the arguments the tool adds are invisible otherwise")
            .contains(
                "asprof start -e wall -i 10ms -o jfr --loop 30s " +
                    "-f ${Constants.Profiling.PROFILE_DIR}/cassandra-%p-%t.jfr 4242",
            )
    }

    @Test
    fun `status separates what was asked for from what is attached`() {
        val report =
            ProfilingStatus().render(
                db0,
                ProfilingEffectiveState(running = false, desiredEnabled = true, pid = 4242),
            )

        assertThat(report).contains("desired:  enabled")
        assertThat(report).contains("attached: no")
        assertThat(report).contains("(no session attached)")
    }

    @Test
    fun `status explains a node that is waiting for its database rather than leaving a blank`() {
        // Same two lines as the report above — enabled, nothing attached — for a completely
        // different reason. Without the explanation an operator reads a node that has just
        // restarted as one whose profiler is broken.
        val report =
            ProfilingStatus().render(
                db0,
                ProfilingEffectiveState(
                    running = false,
                    desiredEnabled = true,
                    pid = 4242,
                    attachDeferred = true,
                    updatedAt = Instant.parse("2026-08-24T12:00:00Z").minusSeconds(20).epochSecond,
                ),
                Instant.parse("2026-08-24T12:00:00Z"),
            )

        assertThat(report).contains("WAITING")
        assertThat(report).contains("attached: no (waiting for the database to become ready)")
        assertThat(report)
            .describedAs("a node that is waiting is not a node whose reconciler has stopped")
            .doesNotContain("STALE")
    }

    @Test
    fun `status puts a dead reconciler ahead of a wait it recorded hours ago`() {
        // Both are true, and only one of them is worth acting on: if the pass that recorded the wait
        // is hours old, the node is not waiting for anything — its reconciler stopped running.
        val now = Instant.parse("2026-08-24T12:00:00Z")
        val report =
            ProfilingStatus().render(
                db0,
                ProfilingEffectiveState(
                    running = false,
                    desiredEnabled = true,
                    pid = 4242,
                    attachDeferred = true,
                    updatedAt = now.minusSeconds(9000).epochSecond,
                ),
                now,
            )

        assertThat(report).contains("STALE")
        assertThat(report).doesNotContain("WAITING")
    }

    @Test
    fun `status reports how long ago the node last reconciled`() {
        val now = Instant.parse("2026-08-24T12:00:00Z")
        val report =
            ProfilingStatus().render(
                db0,
                ProfilingEffectiveState(
                    running = true,
                    desiredEnabled = true,
                    pid = 4242,
                    updatedAt = now.minusSeconds(20).epochSecond,
                ),
                now,
            )

        assertThat(report).contains("updated:  20s ago")
        assertThat(report)
            .describedAs("a healthy node must not be dressed up as a broken one")
            .doesNotContain("STALE")
    }

    @Test
    fun `status refuses to render a dead reconciler's snapshot as a healthy node`() {
        // The document is rewritten every pass. If the timer is masked, disabled, or the oneshot is
        // being killed at TimeoutStartSec, it stops being rewritten and every field keeps reporting
        // the last healthy pass — attached: yes, and a session age that grows against the current
        // clock exactly as though something were still running.
        val now = Instant.parse("2026-08-24T12:00:00Z")
        val report =
            ProfilingStatus().render(
                db0,
                ProfilingEffectiveState(
                    running = true,
                    desiredEnabled = true,
                    pid = 4242,
                    startedAt = now.minusSeconds(9000).epochSecond,
                    updatedAt = now.minusSeconds(8000).epochSecond,
                ),
                now,
            )

        assertThat(report).contains("STALE")
        assertThat(report)
            .describedAs("the age of the snapshot is the operator's evidence, not just a flag")
            .contains("2h13m ago")
        assertThat(report)
            .describedAs("and it must say where to look")
            .contains(Constants.Profiling.TIMER_UNIT)
        assertThat(report).contains("(stale)")
        assertThat(report.lines())
            .describedAs("a two-line banner must not shift the rest of the report sideways")
            .contains("  desired:  enabled")
    }

    @Test
    fun `status does not call a document without a timestamp stale`() {
        // Written by a reconciler that predates updatedAt. Unknown age is not evidence of a fault.
        val report =
            ProfilingStatus().render(
                db0,
                ProfilingEffectiveState(running = true, desiredEnabled = true, pid = 4242),
                Instant.parse("2026-08-24T12:00:00Z"),
            )

        assertThat(report).doesNotContain("STALE")
        assertThat(report).contains("updated:  unknown")
    }

    @Test
    fun `status emits a typed event when a node's reconciler has stopped running`() {
        whenever(profilingService.readEffectiveState(any()))
            .thenReturn(
                ProfilingEffectiveState(
                    running = true,
                    desiredEnabled = true,
                    pid = 4242,
                    updatedAt = Instant.now().minusSeconds(7200).epochSecond,
                ),
            )

        ProfilingStatus().execute()

        val stale = emitted.filterIsInstance<Event.Profiling.StateStale>()
        assertThat(stale.map { it.host }).containsExactly("db0", "db1")
        assertThat(stale.first().ageSeconds).isGreaterThanOrEqualTo(7200)
        assertThat(stale.first().expectedIntervalSeconds)
            .isEqualTo(Constants.Profiling.RECONCILE_INTERVAL_SECONDS)
    }

    @Test
    fun `status stays quiet about staleness for a node reconciling normally`() {
        whenever(profilingService.readEffectiveState(any()))
            .thenReturn(
                ProfilingEffectiveState(
                    running = true,
                    desiredEnabled = true,
                    pid = 4242,
                    updatedAt = Instant.now().epochSecond,
                ),
            )

        ProfilingStatus().execute()

        assertThat(emitted.filterIsInstance<Event.Profiling.StateStale>()).isEmpty()
    }

    @Test
    fun `status blames the corrupt document, not the reconcile timer`() {
        // Before the reconciler kept reporting through an unreadable config, this node went silent:
        // the document stopped being rewritten, so `status` called it STALE and sent the operator to
        // check the timer — the one component still working. It now reports every pass and says why
        // it is not acting, and the banner has to say the same thing.
        val now = Instant.parse("2026-08-24T12:00:00Z")
        val report =
            ProfilingStatus().render(
                db0,
                ProfilingEffectiveState(
                    running = true,
                    desiredEnabled = true,
                    pid = 4242,
                    configError = "config_unreadable",
                    updatedAt = now.epochSecond,
                ),
                now,
            )

        assertThat(report).contains("CONFIG")
        assertThat(report).contains(Constants.Profiling.DESIRED_STATE_PATH)
        assertThat(report).contains("config_unreadable")
        assertThat(report)
            .describedAs("the operator needs to know shipping and pruning did not stop")
            .contains("shipping and pruning")
        assertThat(report)
            .describedAs("and what to do about it")
            .contains("profile start")
        assertThat(report).doesNotContain("STALE")
        assertThat(report.lines())
            .describedAs("a multi-line banner must not shift the rest of the report sideways")
            .contains("  desired:  enabled")
    }

    @Test
    fun `status does not tell an operator with a corrupt config that the reconciler is dead`() {
        whenever(profilingService.readEffectiveState(any()))
            .thenReturn(
                ProfilingEffectiveState(
                    running = true,
                    desiredEnabled = true,
                    pid = 4242,
                    configError = "config_unreadable",
                    // Old enough to be stale, which is what the previous behaviour keyed on.
                    updatedAt = Instant.now().minusSeconds(7200).epochSecond,
                ),
            )

        ProfilingStatus().execute()

        val unreadable = emitted.filterIsInstance<Event.Profiling.NodeConfigUnreadable>()
        assertThat(unreadable.map { it.host }).containsExactly("db0", "db1")
        assertThat(unreadable.first().reason).isEqualTo("config_unreadable")
        assertThat(emitted.filterIsInstance<Event.Profiling.StateStale>())
            .describedAs("StateStale asserts the reconciler is not running, and this node's is")
            .isEmpty()
    }

    @Test
    fun `status reports chunks destroyed before they ever shipped`() {
        // The one pruning number that means irreversible loss. It lived only in journald and the
        // metrics push, so the command an operator runs to ask "why did my profiles never show up?"
        // could not answer.
        whenever(profilingService.readEffectiveState(any()))
            .thenReturn(
                ProfilingEffectiveState(
                    running = true,
                    desiredEnabled = true,
                    pid = 4242,
                    prunedForAge = 40,
                    prunedForSize = 2,
                    prunedUnshipped = 17,
                    updatedAt = Instant.now().epochSecond,
                ),
            )

        ProfilingStatus().execute()

        val lost = emitted.filterIsInstance<Event.Profiling.ChunksLost>()
        assertThat(lost.map { it.host }).containsExactly("db0", "db1")
        assertThat(lost.first().lost).isEqualTo(17)
    }

    @Test
    fun `status renders what pruning took and why`() {
        val report =
            ProfilingStatus().render(
                db0,
                ProfilingEffectiveState(
                    running = true,
                    desiredEnabled = true,
                    pid = 4242,
                    prunedForAge = 40,
                    prunedForSize = 2,
                    prunedUnshipped = 17,
                    updatedAt = Instant.now().epochSecond,
                ),
            )

        assertThat(report).contains("40 for age")
        assertThat(report).contains("2 for size")
        assertThat(report).contains("17 never shipped")
    }

    @Test
    fun `status stays quiet about lost chunks when nothing has been lost`() {
        whenever(profilingService.readEffectiveState(any()))
            .thenReturn(
                ProfilingEffectiveState(
                    running = true,
                    desiredEnabled = true,
                    pid = 4242,
                    prunedForAge = 40,
                    updatedAt = Instant.now().epochSecond,
                ),
            )

        ProfilingStatus().execute()

        assertThat(emitted.filterIsInstance<Event.Profiling.ChunksLost>())
            .describedAs("routine age pruning of already-shipped chunks is not data loss")
            .isEmpty()
    }

    // --- fetch ---------------------------------------------------------------

    @Test
    fun `fetch downloads the selected chunks into a per-host directory`() {
        whenever(profilingService.listCompletedChunks(any(), any()))
            .thenReturn(listOf("/p/a.jfr", "/p/b.jfr"))

        val command = ProfilingFetch()
        command.outputDir = File(tempDir, "profiles")
        command.execute()

        val destination = argumentCaptor<File>()
        val fetched = argumentCaptor<List<String>>()
        verify(profilingService, org.mockito.kotlin.times(2))
            .fetchChunks(any(), fetched.capture(), destination.capture())
        assertThat(destination.allValues.map { it.name }).containsExactly("db0", "db1")
        assertThat(fetched.firstValue).containsExactly("/p/a.jfr", "/p/b.jfr")
        assertThat(emitted.filterIsInstance<Event.Profiling.ChunksFetched>()).hasSize(2)
    }

    @Test
    fun `fetch asks each node for the number of chunks the user requested`() {
        // --last is the only control the operator has over how much history comes back; dropping it
        // on the floor would still leave every other fetch assertion passing.
        whenever(profilingService.listCompletedChunks(any(), any())).thenReturn(listOf("/p/a.jfr"))

        val command = ProfilingFetch()
        command.outputDir = File(tempDir, "profiles")
        command.last = 3
        command.execute()

        val targets = argumentCaptor<Host>()
        val last = argumentCaptor<Int>()
        verify(profilingService, org.mockito.kotlin.times(2))
            .listCompletedChunks(targets.capture(), last.capture())
        assertThat(last.allValues).containsExactly(3, 3)
    }

    @Test
    fun `fetch downloads nothing when a node has no completed chunks`() {
        whenever(profilingService.listCompletedChunks(any(), any())).thenReturn(emptyList())

        val command = ProfilingFetch()
        command.outputDir = File(tempDir, "profiles")
        command.execute()

        verify(profilingService, never()).fetchChunks(any(), any(), any())
        assertThat(emitted.filterIsInstance<Event.Profiling.ChunksFetched>()).isEmpty()
    }

    // --- flamegraph ----------------------------------------------------------

    @Test
    fun `flamegraph rejects a converter argument the system supplies, before contacting any node`() {
        val command = ProfilingFlamegraph()
        command.jfrconvArgs = listOf("-o", "collapsed")

        assertThatThrownBy { command.execute() }.hasMessageContaining("-o")
        verify(profilingService, never()).convertToFlamegraph(any(), any(), any(), any())
    }

    @Test
    fun `flamegraph converts all selected chunks on the node and downloads the result`() {
        whenever(profilingService.listCompletedChunks(any(), any()))
            .thenReturn(listOf("/p/a.jfr", "/p/b.jfr"))
        whenever(profilingService.convertToFlamegraph(any(), any(), any(), any()))
            .thenReturn("/mnt/db1/cassandra/artifacts/flame-db0-1.html")

        val command = ProfilingFlamegraph()
        command.outputDir = File(tempDir, "profiles")
        command.jfrconvArgs = listOf("--threads")
        command.execute()

        val chunks = argumentCaptor<List<String>>()
        verify(profilingService, org.mockito.kotlin.times(2))
            .convertToFlamegraph(any(), chunks.capture(), any(), any())
        assertThat(chunks.firstValue).containsExactly("/p/a.jfr", "/p/b.jfr")
        verify(profilingService, org.mockito.kotlin.times(2)).download(any(), any(), any())
        assertThat(emitted.filterIsInstance<Event.Profiling.FlamegraphCreated>()).hasSize(2)
    }

    @Test
    fun `flamegraph hands the node the requested count, format, and converter arguments`() {
        // "jfrconv arguments pass through untouched" is a headline promise, and the rejection
        // messages tell operators to use --last and --format instead of the reserved arguments.
        // Nothing else in this class proves any of the three leaves the command.
        whenever(profilingService.listCompletedChunks(any(), any())).thenReturn(listOf("/p/a.jfr"))
        whenever(profilingService.convertToFlamegraph(any(), any(), any(), any()))
            .doAnswer { "/mnt/db1/cassandra/artifacts/flame-db0-1.${it.getArgument<String>(2)}" }

        val command = ProfilingFlamegraph()
        command.outputDir = File(tempDir, "profiles")
        command.last = 3
        command.format = "collapsed"
        command.jfrconvArgs = listOf("--threads", "--include", "org.apache.cassandra")
        command.execute()

        val last = argumentCaptor<Int>()
        verify(profilingService, org.mockito.kotlin.times(2)).listCompletedChunks(any(), last.capture())
        assertThat(last.allValues).containsExactly(3, 3)

        val format = argumentCaptor<String>()
        val extraArgs = argumentCaptor<List<String>>()
        verify(profilingService, org.mockito.kotlin.times(2))
            .convertToFlamegraph(any(), any(), format.capture(), extraArgs.capture())
        assertThat(format.firstValue).isEqualTo("collapsed")
        assertThat(extraArgs.firstValue).containsExactly("--threads", "--include", "org.apache.cassandra")

        val local = argumentCaptor<Path>()
        verify(profilingService, org.mockito.kotlin.times(2)).download(any(), any(), local.capture())
        assertThat(local.firstValue.fileName.toString()).endsWith(".collapsed")
    }

    @Test
    fun `flamegraph converts nothing when a node has no completed chunks`() {
        whenever(profilingService.listCompletedChunks(any(), any())).thenReturn(emptyList())

        val command = ProfilingFlamegraph()
        command.outputDir = File(tempDir, "profiles")
        command.execute()

        verify(profilingService, never()).convertToFlamegraph(any(), any(), any(), any())
    }

    @Test
    fun `fetch refuses a chunk count that would pull the whole profile directory`() {
        // --last reaches the node as `head -n <count>`, where a negative count selects everything
        // but the newest chunks rather than nothing. Not typeable by accident, but settable over
        // MCP, and the failure is a silent bulk download rather than an error.
        val command = ProfilingFetch()
        command.last = -1

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--last")

        verify(profilingService, never()).listCompletedChunks(any(), any())
    }

    @Test
    fun `flamegraph refuses a chunk count that would convert the whole profile directory`() {
        val command = ProfilingFlamegraph()
        command.last = 0

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--last")

        verify(profilingService, never()).listCompletedChunks(any(), any())
    }
}
