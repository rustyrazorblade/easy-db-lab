package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InfrastructureStatus
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.proxy.ObservabilityRoute
import com.rustyrazorblade.easydblab.proxy.RouteLookup
import com.rustyrazorblade.easydblab.services.DefaultMimirQueryService
import com.rustyrazorblade.easydblab.services.RoutedObservabilityHttp
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MetricsCycleTest : BaseKoinTest() {
    private val http = FakeMimirHttp()
    private val publisher = FakeSamplePublisher()
    private val recording = RecordingBus()
    private val health = MetricHealth()
    private var stopped = false
    private var state: ClusterState? = runningCluster()
    private var route: RouteLookup = RouteLookup.Usable(ObservabilityRoute.Socks(PORT))

    private val cpu = StreamMetric.NODE_CPU
    private val memory = StreamMetric.NODE_MEMORY
    private val pending = StreamMetric.CASSANDRA_COMPACTION_PENDING

    private val clientFactory =
        object : HttpClientFactory {
            override fun createClient(): OkHttpClient = OkHttpClient()

            override fun close() = Unit
        }

    private val queries =
        CycleQueries(
            loadState = { state },
            routes = { route },
            httpClientFactory = clientFactory,
            mimirFor = { DefaultMimirQueryService(http) },
        )

    private fun cycle(vararg metrics: StreamMetric) = cycle(queries, *metrics)

    private fun cycle(
        queries: CycleQueries,
        vararg metrics: StreamMetric,
    ) = MetricsCycle(
            metrics = metrics.toList(),
            queries = queries,
            publisher = publisher,
            health = health,
            eventBus = recording.bus,
            isStopped = { stopped },
            reconnect = fastReconnect(),
        )

    private fun hosts(vararg pairs: Pair<String, String>) =
        FakeMimirHttp.success(*pairs.map { (host, value) -> mapOf("host_name" to host) to value }.toTypedArray())

    @Test
    fun `one failed metric does not block the others, and is queried again next cycle`() {
        http.on("system_cpu_time_seconds_total") { FakeMimirHttp.Answer.Body(500, "boom") }
        http.on("system_memory_usage_bytes") { hosts("db0" to "1024", "db1" to "2048") }
        http.on("cassandra_compaction_tasks_pending") { FakeMimirHttp.success(emptyMap<String, String>() to "4") }
        val cycle = cycle(cpu, memory, pending)

        val first = cycle.run()
        cycle.run()

        assertThat(first).isEqualTo(CycleOutcome.Completed(CycleReport(published = 3, failed = 1)))
        assertThat(publisher.published.take(3)).containsExactly(
            Event.Metrics.Sample("node.memory", "db0", 1024.0),
            Event.Metrics.Sample("node.memory", "db1", 2048.0),
            Event.Metrics.Sample("cassandra.compaction_pending", "cluster", 4.0),
        )
        assertThat(http.queries.count { it.contains("system_cpu_time_seconds_total") }).isEqualTo(2)
    }

    @Test
    fun `no response, a non-200 status and a body status other than success each count as a failure`() {
        http.on("system_cpu_time_seconds_total") { FakeMimirHttp.Answer.NoResponse }
        http.on("system_memory_usage_bytes") { FakeMimirHttp.Answer.Body(503, "unavailable") }
        http.on("cassandra_compaction_tasks_pending") {
            FakeMimirHttp.Answer.Body(200, "{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"parse error\"}")
        }

        val outcome = cycle(cpu, memory, pending).run()

        assertThat(outcome).isEqualTo(CycleOutcome.Completed(CycleReport(published = 0, failed = 3)))
        assertThat(recording.events.filterIsInstance<Event.Metrics.MetricFailing>().map { it.name })
            .containsExactly("node.cpu", "node.memory", "cassandra.compaction_pending")
    }

    @Test
    fun `a metric that fails three cycles in a row is reported once, and its recovery once`() {
        var failing = true
        http.on("system_cpu_time_seconds_total") { if (failing) FakeMimirHttp.Answer.Body(500, "boom") else hosts("db0" to "5") }
        val cycle = cycle(cpu)

        repeat(3) { cycle.run() }
        failing = false
        repeat(2) { cycle.run() }

        assertThat(recording.events).hasSize(2)
        assertThat(recording.events[0]).isInstanceOf(Event.Metrics.MetricFailing::class.java)
        assertThat((recording.events[0] as Event.Metrics.MetricFailing).reason).contains("500")
        assertThat(recording.events[1]).isEqualTo(Event.Metrics.MetricRecovered("node.cpu"))
    }

    @Test
    fun `an empty result, NaN and Inf publish nothing and are not failures`() {
        http.on("system_cpu_time_seconds_total") { FakeMimirHttp.success() }
        http.on("system_memory_usage_bytes") { hosts("db0" to "NaN", "db1" to "+Inf") }

        val outcome = cycle(cpu, memory).run()

        assertThat(outcome).isEqualTo(CycleOutcome.Completed(CycleReport(published = 0, failed = 0)))
        assertThat(publisher.published).isEmpty()
        assertThat(recording.events).isEmpty()
    }

    @Test
    fun `a cluster that is not running fails every metric with no query`() {
        state = runningCluster().apply { infrastructureStatus = InfrastructureStatus.DOWN }

        val outcome = cycle(cpu, memory).run()

        assertThat(outcome).isEqualTo(CycleOutcome.Completed(CycleReport(published = 0, failed = 2)))
        assertThat(http.queries).isEmpty()
        assertThat(recording.events.filterIsInstance<Event.Metrics.MetricFailing>().map { it.reason })
            .allSatisfy { assertThat(it).contains("not running") }
    }

    @Test
    fun `one cycle instance reads the cluster state again every cycle`() {
        http.on("system_memory_usage_bytes") { hosts("db0" to "1") }
        val cycle = cycle(memory)

        val running = cycle.run()
        state = runningCluster().apply { infrastructureStatus = InfrastructureStatus.DOWN }
        val queriesBeforeDown = http.queries.size
        val down = cycle.run()
        val queriesWhileDown = http.queries.size - queriesBeforeDown
        state = runningCluster()
        val back = cycle.run()

        assertThat(running).isEqualTo(CycleOutcome.Completed(CycleReport(published = 1, failed = 0)))
        assertThat(down).isEqualTo(CycleOutcome.Completed(CycleReport(published = 0, failed = 1)))
        assertThat(queriesWhileDown).isZero()
        assertThat(back).isEqualTo(CycleOutcome.Completed(CycleReport(published = 1, failed = 0)))
        assertThat(recording.events).containsExactly(
            Event.Metrics.MetricFailing("node.memory", "the cluster is not running"),
            Event.Metrics.MetricRecovered("node.memory"),
        )
    }

    @Test
    fun `one cycle instance follows the route recorded for each cycle`() {
        val routes = mutableListOf<ObservabilityRoute>()
        val queries =
            CycleQueries(
                loadState = { state },
                routes = { route },
                httpClientFactory = clientFactory,
                mimirFor = { routed ->
                    routes += (routed as RoutedObservabilityHttp).route
                    DefaultMimirQueryService(http)
                },
            )
        val cycle = cycle(queries, memory)

        cycle.run()
        route = RouteLookup.Usable(ObservabilityRoute.Socks(PORT + 1))
        cycle.run()

        assertThat(routes).containsExactly(ObservabilityRoute.Socks(PORT), ObservabilityRoute.Socks(PORT + 1))
    }

    @Test
    fun `every query is scoped to the label of the cluster in the state`() {
        val running = runningCluster().also { state = it }
        val expected = "cluster=\"${running.clusterLabelName()}\""

        cycle(cpu, memory, pending).run()

        assertThat(http.queries).hasSize(3).allSatisfy { assertThat(it).contains(expected) }
    }

    @Test
    fun `a workspace with no state fails every metric as not running`() {
        state = null

        assertThat(cycle(cpu).run()).isEqualTo(CycleOutcome.Completed(CycleReport(published = 0, failed = 1)))
        assertThat(http.queries).isEmpty()
    }

    @Test
    fun `no live tunnel fails every metric with the socks start reason and no query`() {
        route = RouteLookup.Unavailable("no live SOCKS tunnel is recorded; run 'easy-db-lab socks start'")

        cycle(cpu, memory).run()

        assertThat(http.queries).isEmpty()
        assertThat(recording.events.filterIsInstance<Event.Metrics.MetricFailing>().map { it.reason })
            .hasSize(2)
            .allSatisfy { assertThat(it).contains("socks start") }
    }

    @Test
    fun `a publish failure drops the rest of the cycle, reconnects and continues on the next cycle`() {
        http.on("system_memory_usage_bytes") { hosts("db0" to "1", "db1" to "2", "db2" to "3") }
        publisher.failPublishes = 1
        val cycle = cycle(memory)

        val first = cycle.run()
        val pingsAfterFirst = publisher.pings
        val second = cycle.run()

        assertThat(first).isEqualTo(CycleOutcome.Completed(CycleReport(published = 0, failed = 0)))
        // The cycle's own ping, then the reconnect's first ping.
        assertThat(pingsAfterFirst).isEqualTo(2)
        assertThat(second).isEqualTo(CycleOutcome.Completed(CycleReport(published = 3, failed = 0)))
        assertThat(publisher.published).hasSize(3)
    }

    @Test
    fun `a ping failure goes through the same reconnect, and a reconnect that succeeds continues`() {
        http.on("system_memory_usage_bytes") { hosts("db0" to "1") }
        publisher.failPings = 2
        val cycle = cycle(memory)

        val first = cycle.run()
        val second = cycle.run()

        assertThat(first).isEqualTo(CycleOutcome.Completed(CycleReport(published = 0, failed = 0)))
        assertThat(http.queries).hasSize(1)
        assertThat(second).isEqualTo(CycleOutcome.Completed(CycleReport(published = 1, failed = 0)))
    }

    @Test
    fun `three failed reconnect attempts are Redis lost, naming the URL`() {
        publisher.failPings = 10

        val outcome = cycle(memory).run()

        assertThat(outcome).isInstanceOf(CycleOutcome.RedisLost::class.java)
        assertThat((outcome as CycleOutcome.RedisLost).url).isEqualTo(publisher.url)
        // The cycle's ping and three reconnect attempts.
        assertThat(publisher.pings).isEqualTo(4)
    }

    @Test
    fun `a stop during the reconnect ends the cycle as stopped, not as Redis lost`() {
        publisher.failPings = 10
        publisher.onPing = { if (publisher.pings == 2) stopped = true }

        assertThat(cycle(memory).run()).isEqualTo(CycleOutcome.Stopped)
    }

    @Test
    fun `the stop flag is checked before each query`() {
        http.on("system_cpu_time_seconds_total") {
            stopped = true
            hosts("db0" to "1")
        }

        val outcome = cycle(cpu, memory).run()

        assertThat(outcome).isEqualTo(CycleOutcome.Stopped)
        assertThat(http.queries).hasSize(1)
        assertThat(publisher.published).isEmpty()
        assertThat(recording.events).isEmpty()
    }

    @Test
    fun `the stop flag is checked before each health record`() {
        http.on("system_memory_usage_bytes") {
            stopped = true
            FakeMimirHttp.Answer.Body(500, "boom")
        }

        assertThat(cycle(memory).run()).isEqualTo(CycleOutcome.Stopped)
        assertThat(recording.events).isEmpty()
    }

    @Test
    fun `the stop flag is checked before each publish`() {
        http.on("system_memory_usage_bytes") { hosts("db0" to "1", "db1" to "2") }
        publisher.onPublish = { stopped = true }

        assertThat(cycle(memory).run()).isEqualTo(CycleOutcome.Stopped)
        assertThat(publisher.published).hasSize(1)
    }

    @Test
    fun `a stop before the cycle starts sends nothing`() {
        stopped = true

        assertThat(cycle(memory).run()).isEqualTo(CycleOutcome.Stopped)
        assertThat(publisher.pings).isZero()
        assertThat(http.queries).isEmpty()
    }

    @Test
    fun `samples never reach the event bus`() {
        http.on("system_memory_usage_bytes") { hosts("db0" to "1") }

        cycle(memory).run()

        assertThat(publisher.published).hasSize(1)
        assertThat(recording.events.filterIsInstance<Event.Metrics.Sample>()).isEmpty()
    }

    @Test
    fun `the summary line gives the samples published and the metrics failed`() {
        assertThat(CycleReport(published = 7, failed = 2).summaryLine()).contains("7").contains("2")
    }

    private companion object {
        const val PORT = 21000
    }
}
