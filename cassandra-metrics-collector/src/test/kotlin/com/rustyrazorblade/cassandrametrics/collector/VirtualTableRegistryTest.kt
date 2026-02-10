package com.rustyrazorblade.cassandrametrics.collector

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VirtualTableRegistryTest {
    @Test
    fun `default registry contains all expected tables`() {
        val registry = VirtualTableRegistry.buildDefault()

        val tableNames = registry.tables.map { it.tableName }

        assertThat(tableNames).contains(
            "thread_pools",
            "caches",
            "batch_metrics",
            "cql_metrics",
            "coordinator_read_latency",
            "coordinator_write_latency",
            "coordinator_scan_latency",
            "local_read_latency",
            "local_write_latency",
            "local_scan_latency",
            "disk_usage",
            "max_partition_size",
            "max_sstable_size",
            "max_sstable_duration",
            "rows_per_read",
            "tombstones_per_read",
            "internode_inbound",
            "internode_outbound",
            "pending_hints",
            "streaming",
            "sstable_tasks",
            "clients",
            "snapshots",
            "queries",
            "cidr_filtering_metrics_counts",
            "cidr_filtering_metrics_latencies",
            "settings",
            "system_properties",
            "gossip_info",
            "credentials_cache_keys",
            "jmx_permissions_cache_keys",
            "network_permissions_cache_keys",
            "permissions_cache_keys",
            "roles_cache_keys",
            "repairs",
            "repair_sessions",
            "repair_jobs",
            "repair_validations",
            "repair_participates",
            "sai_column_indexes",
            "sai_sstable_indexes",
            "sai_sstable_index_segments",
        )
    }

    @Test
    fun `no duplicate table names`() {
        val registry = VirtualTableRegistry.buildDefault()
        val tableNames = registry.tables.map { it.tableName }

        assertThat(tableNames).doesNotHaveDuplicates()
    }

    @Test
    fun `no duplicate metric names across all tables`() {
        val registry = VirtualTableRegistry.buildDefault()
        val allMetricNames =
            registry.tables.flatMap { table ->
                table.metricColumns.map { it.prometheusName }
            }

        assertThat(allMetricNames).doesNotHaveDuplicates()
    }

    @Test
    fun `all queries target system_views keyspace`() {
        val registry = VirtualTableRegistry.buildDefault()

        for (table in registry.tables) {
            assertThat(table.query)
                .describedAs("Query for ${table.tableName}")
                .startsWith("SELECT * FROM system_views.")
        }
    }

    @Test
    fun `all prometheus metric names follow naming convention`() {
        val registry = VirtualTableRegistry.buildDefault()

        for (table in registry.tables) {
            for (metric in table.metricColumns) {
                assertThat(metric.prometheusName)
                    .describedAs("Metric ${metric.prometheusName} in ${table.tableName}")
                    .startsWith("cassandra_")
                    .matches("[a-z][a-z0-9_]*")
            }
        }
    }

    @Test
    fun `keyspace-filtered tables have keyspace_name label`() {
        val registry = VirtualTableRegistry.buildDefault()

        for (table in registry.tables.filter { it.keyspaceFiltered }) {
            assertThat(table.labelColumns)
                .describedAs("Labels for ${table.tableName}")
                .contains("keyspace_name")
        }
    }

    @Test
    fun `latency tables have correct metric columns`() {
        val registry = VirtualTableRegistry.buildDefault()
        val latencyTables =
            registry.tables.filter {
                it.tableName.endsWith("_latency")
            }

        assertThat(latencyTables).hasSize(6)

        for (table in latencyTables) {
            val colNames = table.metricColumns.map { it.cqlColumn }
            assertThat(colNames)
                .describedAs("Columns for ${table.tableName}")
                .containsExactlyInAnyOrder("count", "max_ms", "p50th_ms", "p99th_ms", "per_second")
        }
    }
}
