package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.proxy.ObservabilityRoute
import com.rustyrazorblade.easydblab.proxy.RouteLookup
import com.rustyrazorblade.easydblab.services.DefaultMimirQueryService
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class MetricsStreamServiceTest : BaseKoinTest() {
    /** Stops after [cycles] cycles, as a signal between cycles would. */
    private class StopAfter(
        private val cycles: () -> Int,
        private val limit: Int,
    ) : StopSignals {
        var installedOn: Thread? = null
        var closed = false

        override val isStopped: Boolean get() = cycles() >= limit

        override fun install(commandThread: Thread) {
            installedOn = commandThread
        }

        override fun close() {
            closed = true
        }
    }

    private val http =
        FakeMimirHttp().apply {
            on(
                "system_memory_usage_bytes",
            ) { FakeMimirHttp.success(mapOf("host_name" to "db0") to "1") }
        }
    private val publisher = FakeSamplePublisher()
    private val reports = mutableListOf<CycleReport>()

    private fun service(signals: StopSignals) =
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
            eventBus = RecordingBus().bus,
            stopSignals = signals,
            tickLoop = TickLoop(sleeper = {}, interval = Duration.ofMillis(1)),
            reconnect = fastReconnect(),
        )

    @Test
    fun `a stop ends the run as stopped, after one callback per completed cycle`() {
        val signals = StopAfter({ reports.size }, limit = 3)

        val end = service(signals).run(listOf(StreamMetric.NODE_MEMORY)) { reports += it }

        assertThat(end).isEqualTo(StreamEnd.Stopped)
        assertThat(reports).containsExactly(CycleReport(1, 0), CycleReport(1, 0), CycleReport(1, 0))
        assertThat(publisher.published).hasSize(3)
        assertThat(signals.installedOn).isSameAs(Thread.currentThread())
        assertThat(signals.closed).isTrue()
    }

    @Test
    fun `failed reconnects end the run as Redis lost, naming the URL`() {
        publisher.failPings = 100
        val signals = StopAfter({ 0 }, limit = 1)

        val end = service(signals).run(listOf(StreamMetric.NODE_MEMORY)) { reports += it }

        assertThat(end).isInstanceOf(StreamEnd.RedisLost::class.java)
        assertThat((end as StreamEnd.RedisLost).url).isEqualTo(publisher.url)
        assertThat(reports).isEmpty()
        assertThat(signals.closed).isTrue()
    }
}
