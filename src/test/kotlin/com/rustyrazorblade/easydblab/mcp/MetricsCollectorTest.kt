package com.rustyrazorblade.easydblab.mcp

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.services.MimirQueryService
import com.rustyrazorblade.easydblab.services.PromQueryResult
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Collections

class MetricsCollectorTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var collector: MetricsCollector
    private val capturedEvents = Collections.synchronizedList(mutableListOf<Event>())
    private val mimir = FakeMimir()

    private val testControlHost = ClusterHost("54.123.45.67", "10.0.1.5", "control0", "us-west-2a", "i-test123")
    private val testDbHost = ClusterHost("54.123.45.68", "10.0.1.6", "db-0", "us-west-2a", "i-test456")
    private val cluster = "test-cluster-c1"
    private val queries = MetricsQueries.forCluster(cluster)

    /** Answers each query from a table; a query not in it fails, as a refused one would. */
    private class FakeMimir : MimirQueryService {
        val answers = mutableMapOf<String, Result<List<PromQueryResult>>>()
        val asked = Collections.synchronizedList(mutableListOf<String>())

        override fun query(promql: String): Result<List<PromQueryResult>> {
            asked.add(promql)
            return answers[promql] ?: Result.failure(IllegalStateException("no answer for $promql"))
        }
    }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<ClusterStateManager>().also { mockClusterStateManager = it } }
            },
        )

    @BeforeEach
    fun setUp() {
        mockClusterStateManager = getKoin().get()
        capturedEvents.clear()

        val eventBus = getKoin().get<EventBus>()
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    capturedEvents.add(envelope.event)
                }

                override fun close() = Unit
            },
        )

        collector = MetricsCollector(mimir, mockClusterStateManager, eventBus)
    }

    @Test
    fun `emits SystemSnapshot when system metrics are available`() {
        setupCassandraCluster()
        setupSystemMetrics()
        setupCassandraMetrics(emptyList())

        collector.collect()

        val systemEvents = capturedEvents.filterIsInstance<Event.Metrics.System>()
        assertThat(systemEvents).hasSize(1)
        val node = systemEvents[0].nodes.getValue("db-0")
        assertThat(node.cpuUsagePct).isEqualTo(34.2)
        assertThat(node.memoryUsedBytes).isEqualTo(17179869184L)
    }

    @Test
    fun `emits CassandraSnapshot when cassandra metrics are available`() {
        setupCassandraCluster()
        setupSystemMetrics()
        setupCassandraMetrics(null)

        collector.collect()

        val cassandraEvents = capturedEvents.filterIsInstance<Event.Metrics.Cassandra>()
        assertThat(cassandraEvents).hasSize(1)
        assertThat(cassandraEvents[0].readP99Ms).isEqualTo(1.247)
        assertThat(cassandraEvents[0].writeOpsPerSec).isEqualTo(12087.3)
    }

    /** Clusters in one tenant can share a metrics store, so a query for this cluster names it. */
    @Test
    fun `every query is scoped to the current cluster`() {
        setupCassandraCluster()
        setupSystemMetrics()
        setupCassandraMetrics(null)

        collector.collect()

        assertThat(mimir.asked).hasSize(12)
        assertThat(mimir.asked).allSatisfy { query ->
            val selectors = Regex("\\{[^}]*}").findAll(query).map { it.value }.toList()
            assertThat(selectors).describedAs(query).isNotEmpty().allMatch { it.contains("cluster=\"$cluster\"") }
        }
    }

    @Test
    fun `does not emit CassandraSnapshot when no Cassandra hosts configured`() {
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test-cluster",
                clusterId = "c1",
                versions = mutableMapOf(),
                hosts = mapOf(ServerType.Control to listOf(testControlHost)),
            ),
        )
        setupSystemMetrics()

        collector.collect()

        assertThat(capturedEvents.filterIsInstance<Event.Metrics.Cassandra>()).isEmpty()
    }

    @Test
    fun `cassandra query failure does not block system metrics`() {
        setupCassandraCluster()
        setupSystemMetrics()
        // No answers for the Cassandra queries: every one fails.

        collector.collect()

        assertThat(capturedEvents.filterIsInstance<Event.Metrics.System>()).hasSize(1)
        assertThat(capturedEvents.filterIsInstance<Event.Metrics.Cassandra>()).isEmpty()
    }

    @Test
    fun `does not emit events when queries return empty results`() {
        setupCassandraCluster()
        (queries.system() + queries.cassandra()).forEach { mimir.answers[it] = Result.success(emptyList()) }

        collector.collect()

        assertThat(capturedEvents).isEmpty()
    }

    private fun setupCassandraCluster() {
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test-cluster",
                clusterId = "c1",
                versions = mutableMapOf(),
                hosts =
                    mapOf(
                        ServerType.Control to listOf(testControlHost),
                        ServerType.Cassandra to listOf(testDbHost),
                    ),
            ),
        )
    }

    private fun setupSystemMetrics() {
        mimir.answers[queries.systemCpu] = Result.success(listOf(hostResult("db-0", 34.2)))
        mimir.answers[queries.systemMemory] = Result.success(listOf(hostResult("db-0", 17179869184.0)))
        mimir.answers[queries.systemDiskRead] = Result.success(listOf(hostResult("db-0", 52428800.0)))
        mimir.answers[queries.systemDiskWrite] = Result.success(listOf(hostResult("db-0", 104857600.0)))
        mimir.answers[queries.systemFilesystem] = Result.success(listOf(hostResult("db-0", 45.2)))
    }

    /** Answers every Cassandra query with [override], or with realistic values when it is null. */
    private fun setupCassandraMetrics(override: List<PromQueryResult>?) {
        val values =
            mapOf(
                queries.cassandraReadP99 to 1.247,
                queries.cassandraWriteP99 to 0.832,
                queries.cassandraReadOps to 15234.5,
                queries.cassandraWriteOps to 12087.3,
                queries.cassandraCompactionPending to 3.0,
                queries.cassandraCompactionCompleted to 1.5,
                queries.cassandraCompactionBytes to 52428800.0,
            )
        values.forEach { (query, value) -> mimir.answers[query] = Result.success(override ?: listOf(scalarResult(value))) }
    }

    private fun hostResult(
        hostName: String,
        value: Double,
    ) = PromQueryResult(mapOf("host_name" to hostName), listOf(JsonPrimitive(1709913600), JsonPrimitive(value.toString())))

    private fun scalarResult(value: Double) =
        PromQueryResult(emptyMap(), listOf(JsonPrimitive(1709913600), JsonPrimitive(value.toString())))
}
