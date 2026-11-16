package com.rustyrazorblade.easydblab.commands.metrics

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.events.RedisConfiguration
import com.rustyrazorblade.easydblab.events.RedisEndpoint
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.proxy.ObservabilityRoute
import com.rustyrazorblade.easydblab.proxy.RouteLookup
import com.rustyrazorblade.easydblab.services.DefaultMimirQueryService
import com.rustyrazorblade.easydblab.services.metrics.CycleQueries
import com.rustyrazorblade.easydblab.services.metrics.FakeMimirHttp
import com.rustyrazorblade.easydblab.services.metrics.FakeSamplePublisher
import com.rustyrazorblade.easydblab.services.metrics.MetricsStreamService
import com.rustyrazorblade.easydblab.services.metrics.StopSignals
import com.rustyrazorblade.easydblab.services.metrics.TickLoop
import com.rustyrazorblade.easydblab.services.metrics.fastReconnect
import com.rustyrazorblade.easydblab.services.metrics.runningCluster
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.time.Duration

class MetricsStreamTest : BaseKoinTest() {
    private val events = mutableListOf<Event>()
    private val http = FakeMimirHttp()
    private val publisher = FakeSamplePublisher()
    private var redis: RedisConfiguration = RedisConfiguration.Configured(RedisEndpoint.parse("redis://redis.test:6379/camera"))
    private var cyclesBeforeStop = 2

    // Each cycle publishes the two node.cpu samples, so the run stops once the last wanted cycle has
    // published all of its samples, between cycles.
    private val signals =
        object : StopSignals {
            override val isStopped: Boolean get() = publisher.published.size >= SAMPLES_PER_CYCLE * cyclesBeforeStop

            override fun install(commandThread: Thread) = Unit

            override fun close() = Unit
        }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { ClusterStateManager(File(get<Context>().workingDirectory, "state.json")) }
                factory { redis }
                factory {
                    MetricsStreamService(
                        queries =
                            CycleQueries(
                                loadState = { runningCluster() },
                                routes = { RouteLookup.Usable(ObservabilityRoute.Direct) },
                                httpClientFactory =
                                    object : HttpClientFactory {
                                        override fun createClient(): OkHttpClient = OkHttpClient()

                                        override fun close() = Unit
                                    },
                                mimirFor = { DefaultMimirQueryService(http) },
                            ),
                        publisher = publisher,
                        eventBus = get(),
                        stopSignals = signals,
                        tickLoop = TickLoop(sleeper = {}, interval = Duration.ofMillis(1)),
                        reconnect = fastReconnect(),
                    )
                }
            },
        )

    @BeforeEach
    fun captureEvents() {
        save(runningCluster())
        http.on("system_cpu_time_seconds_total") {
            FakeMimirHttp.success(mapOf("host_name" to "db0") to "12", mapOf("host_name" to "db1") to "34")
        }
        get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )
    }

    private fun save(state: ClusterState) = get<ClusterStateManager>().save(state)

    private fun stream(metrics: String? = "node.cpu") = MetricsStream().apply { this.metrics = metrics }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return captured.toString()
    }

    @Test
    fun `bad names are all named and the command fails before any cycle`() {
        assertThatThrownBy { stream("node.cpu,,x").execute() }.isInstanceOf(CommandFailedException::class.java)

        val invalid = events.filterIsInstance<Event.Metrics.InvalidNames>().single()
        assertThat(invalid.entries.map { it.position to it.value }).containsExactly(2 to "", 3 to "x")
        assertThat(http.queries).isEmpty()
    }

    @Test
    fun `an unset Redis URL is named and the command fails before any cycle`() {
        redis = RedisConfiguration.NotConfigured

        assertThatThrownBy { stream().execute() }.isInstanceOf(CommandFailedException::class.java)

        assertThat(events).containsExactly(Event.Metrics.RedisNotConfigured(Constants.EventBus.REDIS_URL_ENV_VAR))
        assertThat(http.queries).isEmpty()
    }

    @Test
    fun `no running cluster fails the command before any cycle`() {
        save(runningCluster().apply { markInfrastructureDown() })

        assertThatThrownBy { stream().execute() }.isInstanceOf(CommandFailedException::class.java)

        assertThat(events).containsExactly(Event.Metrics.NoRunningCluster(context.workingDirectory.absolutePath))
        assertThat(http.queries).isEmpty()
    }

    @Test
    fun `a workspace with no state fails as no running cluster`() {
        File(context.workingDirectory, "state.json").delete()

        assertThatThrownBy { stream().execute() }.isInstanceOf(CommandFailedException::class.java)
        assertThat(events.single()).isInstanceOf(Event.Metrics.NoRunningCluster::class.java)
    }

    @Test
    fun `a cluster with telemetry redirected to an external stack is refused`() {
        save(runningCluster().copy(initConfig = InitConfig(telemetryRedirect = TelemetryRedirect(metrics = "https://m.example"))))

        assertThatThrownBy { stream().execute() }.isInstanceOf(IllegalStateException::class.java)

        assertThat(events.single()).isInstanceOf(Event.Provision.RedirectCommandUnavailable::class.java)
        assertThat(http.queries).isEmpty()
    }

    @Test
    fun `each cycle prints one summary line and no sample, and a stop returns normally`() {
        val output = captureStdout { stream().execute() }

        val lines = output.lines().filter { it.isNotBlank() }
        assertThat(lines).hasSize(2).allSatisfy { assertThat(it).contains("published 2 samples").contains("0 metrics failed") }
        assertThat(output).doesNotContain("db0").doesNotContain("db1")
        assertThat(publisher.published).hasSize(4)
    }

    @Test
    fun `no --metrics streams the defaults`() {
        cyclesBeforeStop = 1

        captureStdout { stream(metrics = null).execute() }

        assertThat(http.queries).hasSize(11)
    }

    @Test
    fun `a lost Redis fails the command with an event naming the URL`() {
        publisher.failPings = 100

        assertThatThrownBy { stream().execute() }
            .isInstanceOf(CommandFailedException::class.java)
            .hasMessageContaining(publisher.url)

        val lost = events.filterIsInstance<Event.Metrics.RedisLost>().single()
        assertThat(lost.url).isEqualTo(publisher.url)
    }

    private companion object {
        /** The node.cpu answer has two hosts. */
        const val SAMPLES_PER_CYCLE = 2
    }
}
