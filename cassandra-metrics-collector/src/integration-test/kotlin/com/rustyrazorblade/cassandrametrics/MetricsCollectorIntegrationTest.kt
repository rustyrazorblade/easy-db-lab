package com.rustyrazorblade.cassandrametrics

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import com.rustyrazorblade.cassandrametrics.cache.MetricsCache
import com.rustyrazorblade.cassandrametrics.collector.CqlQueryExecutor
import com.rustyrazorblade.cassandrametrics.collector.MetricsCollector
import com.rustyrazorblade.cassandrametrics.collector.VirtualTableRegistry
import io.ktor.http.ContentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.CassandraContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetricsCollectorIntegrationTest {
    companion object {
        @Container
        val cassandra: CassandraContainer<*> =
            CassandraContainer("cassandra:5.0")
                .withStartupTimeout(Duration.ofMinutes(3))
    }

    private lateinit var session: CqlSession
    private lateinit var cache: MetricsCache
    private lateinit var collector: MetricsCollector

    @BeforeAll
    fun setUp() {
        val configLoader =
            DriverConfigLoader.programmaticBuilder()
                .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(10))
                .withBoolean(DefaultDriverOption.METADATA_SCHEMA_ENABLED, false)
                .withBoolean(DefaultDriverOption.RECONNECT_ON_INIT, true)
                .build()

        session =
            CqlSession.builder()
                .addContactPoint(
                    InetSocketAddress(cassandra.host, cassandra.getMappedPort(9042)),
                )
                .withLocalDatacenter("datacenter1")
                .withConfigLoader(configLoader)
                .build()

        cache = MetricsCache()
        val registry = VirtualTableRegistry.buildDefault()

        collector =
            MetricsCollector(
                session = session,
                cache = cache,
                registry = registry,
                pollIntervalSeconds = 5,
                threadPoolSize = 4,
                includeSystemKeyspaces = true,
            )

        collector.start()
        // Wait for first collection cycle
        Thread.sleep(8000)
    }

    @AfterAll
    fun tearDown() {
        collector.stop()
        session.close()
    }

    @Test
    fun `collector populates cache with metrics`() {
        assertThat(cache.tableCount()).isGreaterThan(0)
    }

    @Test
    fun `cache contains thread_pools metrics`() {
        val threadPools = cache.get("thread_pools")

        assertThat(threadPools).isNotNull()
        assertThat(threadPools).contains("cassandra_thread_pools_active_tasks")
        assertThat(threadPools).contains("# HELP")
        assertThat(threadPools).contains("# TYPE")
    }

    @Test
    fun `cache contains caches metrics`() {
        val caches = cache.get("caches")

        assertThat(caches).isNotNull()
        assertThat(caches).contains("cassandra_caches_hit_ratio")
        assertThat(caches).contains("cassandra_caches_capacity_bytes")
    }

    @Test
    fun `cache contains settings row count`() {
        val settings = cache.get("settings")

        assertThat(settings).isNotNull()
        assertThat(settings).contains("cassandra_settings_row_count")
    }

    @Test
    fun `renderAll produces valid prometheus format`() {
        val output = cache.renderAll()

        assertThat(output).isNotEmpty()
        // Every # HELP should be followed by # TYPE
        val lines = output.lines()
        for (i in lines.indices) {
            if (lines[i].startsWith("# HELP")) {
                assertThat(lines[i + 1])
                    .describedAs("Line after HELP should be TYPE")
                    .startsWith("# TYPE")
            }
        }
    }

    @Test
    fun `ktor endpoint serves metrics from cache`() {
        val server =
            embeddedServer(Netty, port = 0) {
                routing {
                    get("/metrics") {
                        call.respondText(cache.renderAll(), ContentType.Text.Plain)
                    }
                }
            }
        server.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient.newHttpClient()
            val request =
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:$port/metrics"))
                    .GET()
                    .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            assertThat(response.statusCode()).isEqualTo(200)
            assertThat(response.body()).contains("cassandra_thread_pools_active_tasks")
            assertThat(response.body()).contains("# HELP")
        } finally {
            server.stop(100, 100)
        }
    }

    @Test
    fun `keyspace filtering works for per-table metrics`() {
        val filteredCache = MetricsCache()
        val filteredExecutor = CqlQueryExecutor(session, false)

        val registry = VirtualTableRegistry.buildDefault()
        val diskUsageTable = registry.tables.first { it.tableName == "disk_usage" }

        val result = filteredExecutor.collect(diskUsageTable)
        filteredCache.update("disk_usage", result)

        val output = filteredCache.get("disk_usage") ?: ""
        // system keyspaces should be filtered out
        assertThat(output).doesNotContain("keyspace_name=\"system\"")
        assertThat(output).doesNotContain("keyspace_name=\"system_schema\"")
    }
}
