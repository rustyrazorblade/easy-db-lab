package com.rustyrazorblade.easydblab.mcp

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClickHouseConfig
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.services.PromQueryResult
import com.rustyrazorblade.easydblab.services.VictoriaMetricsQueryService
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Collections

class MetricsCollectorTest : BaseKoinTest() {
    private lateinit var mockQueryService: VictoriaMetricsQueryService
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var collector: MetricsCollector
    private val capturedEvents = Collections.synchronizedList(mutableListOf<Event>())

    private val testControlHost =
        ClusterHost(
            publicIp = "54.123.45.67",
            privateIp = "10.0.1.5",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-test123",
        )

    private val testDbHost =
        ClusterHost(
            publicIp = "54.123.45.68",
            privateIp = "10.0.1.6",
            alias = "db-0",
            availabilityZone = "us-west-2a",
            instanceId = "i-test456",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<VictoriaMetricsQueryService>().also { mockQueryService = it } }
                single { mock<ClusterStateManager>().also { mockClusterStateManager = it } }
            },
        )

    @BeforeEach
    fun setUp() {
        mockQueryService = getKoin().get()
        mockClusterStateManager = getKoin().get()
        capturedEvents.clear()

        val eventBus = getKoin().get<EventBus>()
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    capturedEvents.add(envelope.event)
                }

                override fun close() {}
            },
        )

        collector = MetricsCollector(mockQueryService, mockClusterStateManager, eventBus)
    }

    @Test
    fun `emits SystemSnapshot when system metrics are available`() {
        setupCassandraCluster()
        setupSystemMetrics()
        setupEmptyCassandraMetrics()

        triggerCollection()

        val systemEvents = capturedEvents.filterIsInstance<Event.Metrics.System>()
        assertThat(systemEvents).hasSize(1)
        assertThat(systemEvents[0].nodes).containsKey("db-0")
        assertThat(systemEvents[0].nodes["db-0"]!!.cpuUsagePct).isEqualTo(34.2)
        assertThat(systemEvents[0].nodes["db-0"]!!.memoryUsedBytes).isEqualTo(17179869184L)
    }

    @Test
    fun `emits CassandraSnapshot when cassandra metrics are available`() {
        setupCassandraCluster()
        setupSystemMetrics()
        setupCassandraMetrics()

        triggerCollection()

        val cassandraEvents = capturedEvents.filterIsInstance<Event.Metrics.Cassandra>()
        assertThat(cassandraEvents).hasSize(1)
        assertThat(cassandraEvents[0].readP99Ms).isEqualTo(1.247)
        assertThat(cassandraEvents[0].writeOpsPerSec).isEqualTo(12087.3)
    }

    @Test
    fun `does not emit CassandraSnapshot when cluster is ClickHouse`() {
        val clickHouseState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mapOf(
                        ServerType.Control to listOf(testControlHost),
                        ServerType.Cassandra to listOf(testDbHost),
                    ),
                clickHouseConfig = ClickHouseConfig(),
            )
        whenever(mockClusterStateManager.load()).thenReturn(clickHouseState)
        setupSystemMetrics()

        triggerCollection()

        val cassandraEvents = capturedEvents.filterIsInstance<Event.Metrics.Cassandra>()
        assertThat(cassandraEvents).isEmpty()
    }

    @Test
    fun `cassandra query failure does not block system metrics`() {
        setupCassandraCluster()
        setupSystemMetrics()

        // Make all Cassandra queries fail
        whenever(
            mockQueryService.query(
                eq(testControlHost),
                eq(MetricsCollector.CASSANDRA_READ_P99_QUERY),
            ),
        ).thenReturn(Result.failure(RuntimeException("connection refused")))
        whenever(
            mockQueryService.query(
                eq(testControlHost),
                eq(MetricsCollector.CASSANDRA_WRITE_P99_QUERY),
            ),
        ).thenReturn(Result.failure(RuntimeException("connection refused")))
        whenever(
            mockQueryService.query(
                eq(testControlHost),
                eq(MetricsCollector.CASSANDRA_READ_OPS_QUERY),
            ),
        ).thenReturn(Result.failure(RuntimeException("connection refused")))
        whenever(
            mockQueryService.query(
                eq(testControlHost),
                eq(MetricsCollector.CASSANDRA_WRITE_OPS_QUERY),
            ),
        ).thenReturn(Result.failure(RuntimeException("connection refused")))
        whenever(
            mockQueryService.query(
                eq(testControlHost),
                eq(MetricsCollector.CASSANDRA_COMPACTION_PENDING_QUERY),
            ),
        ).thenReturn(Result.failure(RuntimeException("connection refused")))
        whenever(
            mockQueryService.query(
                eq(testControlHost),
                eq(MetricsCollector.CASSANDRA_COMPACTION_COMPLETED_QUERY),
            ),
        ).thenReturn(Result.failure(RuntimeException("connection refused")))
        whenever(
            mockQueryService.query(
                eq(testControlHost),
                eq(MetricsCollector.CASSANDRA_COMPACTION_BYTES_QUERY),
            ),
        ).thenReturn(Result.failure(RuntimeException("connection refused")))

        triggerCollection()

        // System metrics should still be emitted
        val systemEvents = capturedEvents.filterIsInstance<Event.Metrics.System>()
        assertThat(systemEvents).hasSize(1)

        // Cassandra metrics should not be emitted
        val cassandraEvents = capturedEvents.filterIsInstance<Event.Metrics.Cassandra>()
        assertThat(cassandraEvents).isEmpty()
    }

    @Test
    fun `does not emit events when queries return empty results`() {
        setupCassandraCluster()
        whenever(mockQueryService.query(any(), any())).thenReturn(Result.success(emptyList()))

        triggerCollection()

        assertThat(capturedEvents).isEmpty()
    }

    private fun triggerCollection() {
        // Use reflection to call the private collect() method for testing
        val collectMethod = MetricsCollector::class.java.getDeclaredMethod("collect")
        collectMethod.isAccessible = true
        collectMethod.invoke(collector)
    }

    private fun setupCassandraCluster() {
        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mapOf(
                        ServerType.Control to listOf(testControlHost),
                        ServerType.Cassandra to listOf(testDbHost),
                    ),
            )
        whenever(mockClusterStateManager.load()).thenReturn(state)
    }

    private fun setupSystemMetrics() {
        whenever(
            mockQueryService.query(eq(testControlHost), eq(MetricsCollector.SYSTEM_CPU_QUERY)),
        ).thenReturn(
            Result.success(
                listOf(makeResult("db-0", 34.2)),
            ),
        )
        whenever(
            mockQueryService.query(eq(testControlHost), eq(MetricsCollector.SYSTEM_MEMORY_QUERY)),
        ).thenReturn(
            Result.success(
                listOf(makeResult("db-0", 17179869184.0)),
            ),
        )
        whenever(
            mockQueryService.query(eq(testControlHost), eq(MetricsCollector.SYSTEM_DISK_READ_QUERY)),
        ).thenReturn(
            Result.success(
                listOf(makeResult("db-0", 52428800.0)),
            ),
        )
        whenever(
            mockQueryService.query(eq(testControlHost), eq(MetricsCollector.SYSTEM_DISK_WRITE_QUERY)),
        ).thenReturn(
            Result.success(
                listOf(makeResult("db-0", 104857600.0)),
            ),
        )
        whenever(
            mockQueryService.query(eq(testControlHost), eq(MetricsCollector.SYSTEM_FILESYSTEM_QUERY)),
        ).thenReturn(
            Result.success(
                listOf(makeResult("db-0", 45.2)),
            ),
        )
    }

    private fun setupCassandraMetrics() {
        whenever(
            mockQueryService.query(eq(testControlHost), eq(MetricsCollector.CASSANDRA_READ_P99_QUERY)),
        ).thenReturn(Result.success(listOf(makeScalarResult(1.247))))
        whenever(
            mockQueryService.query(eq(testControlHost), eq(MetricsCollector.CASSANDRA_WRITE_P99_QUERY)),
        ).thenReturn(Result.success(listOf(makeScalarResult(0.832))))
        whenever(
            mockQueryService.query(eq(testControlHost), eq(MetricsCollector.CASSANDRA_READ_OPS_QUERY)),
        ).thenReturn(Result.success(listOf(makeScalarResult(15234.5))))
        whenever(
            mockQueryService.query(eq(testControlHost), eq(MetricsCollector.CASSANDRA_WRITE_OPS_QUERY)),
        ).thenReturn(Result.success(listOf(makeScalarResult(12087.3))))
        whenever(
            mockQueryService.query(eq(testControlHost), eq(MetricsCollector.CASSANDRA_COMPACTION_PENDING_QUERY)),
        ).thenReturn(Result.success(listOf(makeScalarResult(3.0))))
        whenever(
            mockQueryService.query(eq(testControlHost), eq(MetricsCollector.CASSANDRA_COMPACTION_COMPLETED_QUERY)),
        ).thenReturn(Result.success(listOf(makeScalarResult(1.5))))
        whenever(
            mockQueryService.query(eq(testControlHost), eq(MetricsCollector.CASSANDRA_COMPACTION_BYTES_QUERY)),
        ).thenReturn(Result.success(listOf(makeScalarResult(52428800.0))))
    }

    private fun setupEmptyCassandraMetrics() {
        whenever(
            mockQueryService.query(eq(testControlHost), eq(MetricsCollector.CASSANDRA_READ_P99_QUERY)),
        ).thenReturn(Result.success(emptyList()))
        whenever(
            mockQueryService.query(eq(testControlHost), eq(MetricsCollector.CASSANDRA_WRITE_P99_QUERY)),
        ).thenReturn(Result.success(emptyList()))
        whenever(
            mockQueryService.query(eq(testControlHost), eq(MetricsCollector.CASSANDRA_READ_OPS_QUERY)),
        ).thenReturn(Result.success(emptyList()))
        whenever(
            mockQueryService.query(eq(testControlHost), eq(MetricsCollector.CASSANDRA_WRITE_OPS_QUERY)),
        ).thenReturn(Result.success(emptyList()))
        whenever(
            mockQueryService.query(eq(testControlHost), eq(MetricsCollector.CASSANDRA_COMPACTION_PENDING_QUERY)),
        ).thenReturn(Result.success(emptyList()))
        whenever(
            mockQueryService.query(eq(testControlHost), eq(MetricsCollector.CASSANDRA_COMPACTION_COMPLETED_QUERY)),
        ).thenReturn(Result.success(emptyList()))
        whenever(
            mockQueryService.query(eq(testControlHost), eq(MetricsCollector.CASSANDRA_COMPACTION_BYTES_QUERY)),
        ).thenReturn(Result.success(emptyList()))
    }

    private fun makeResult(
        hostName: String,
        value: Double,
    ): PromQueryResult =
        PromQueryResult(
            metric = mapOf("host_name" to hostName),
            value = listOf(JsonPrimitive(1709913600), JsonPrimitive(value.toString())),
        )

    private fun makeScalarResult(value: Double): PromQueryResult =
        PromQueryResult(
            metric = emptyMap(),
            value = listOf(JsonPrimitive(1709913600), JsonPrimitive(value.toString())),
        )
}
