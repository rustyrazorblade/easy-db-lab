package com.rustyrazorblade.cassandrametrics.collector

import com.rustyrazorblade.cassandrametrics.prometheus.MetricType

class VirtualTableRegistry(
    val tables: List<VirtualTableDefinition>,
) {
    companion object {
        private const val PREFIX = "cassandra"

        fun buildDefault(): VirtualTableRegistry = VirtualTableRegistry(allTables())

        @Suppress("LongMethod")
        private fun allTables(): List<VirtualTableDefinition> =
            listOf(
                threadPools(),
                caches(),
                batchMetrics(),
                cqlMetrics(),
                coordinatorReadLatency(),
                coordinatorWriteLatency(),
                coordinatorScanLatency(),
                localReadLatency(),
                localWriteLatency(),
                localScanLatency(),
                diskUsage(),
                maxPartitionSize(),
                maxSstableSize(),
                maxSstableDuration(),
                rowsPerRead(),
                tombstonesPerRead(),
                internodeInbound(),
                internodeOutbound(),
                pendingHints(),
                streaming(),
                sstableTasks(),
                clients(),
                snapshots(),
                queries(),
                cidrFilteringMetricsCounts(),
                cidrFilteringMetricsLatencies(),
                settings(),
                systemProperties(),
                gossipInfo(),
                credentialsCacheKeys(),
                jmxPermissionsCacheKeys(),
                networkPermissionsCacheKeys(),
                permissionsCacheKeys(),
                rolesCacheKeys(),
                repairs(),
                repairSessions(),
                repairJobs(),
                repairValidations(),
                repairParticipates(),
                saiColumnIndexes(),
                saiSstableIndexes(),
                saiSstableIndexSegments(),
            )

        private fun threadPools() =
            VirtualTableDefinition(
                tableName = "thread_pools",
                labelColumns = listOf("name"),
                metricColumns =
                    listOf(
                        gauge("active_tasks", "thread_pools", "Current active tasks"),
                        gauge("active_tasks_limit", "thread_pools", "Maximum active tasks limit"),
                        counter("blocked_tasks", "thread_pools", "Currently blocked tasks"),
                        counter("blocked_tasks_all_time", "thread_pools", "Total blocked tasks since start"),
                        counter("completed_tasks", "thread_pools", "Total completed tasks since start"),
                        gauge("pending_tasks", "thread_pools", "Current pending tasks"),
                    ),
            )

        private fun caches() =
            VirtualTableDefinition(
                tableName = "caches",
                labelColumns = listOf("name"),
                metricColumns =
                    listOf(
                        gauge("capacity_bytes", "caches", "Cache capacity in bytes"),
                        gauge("entry_count", "caches", "Number of entries in cache"),
                        counter("hit_count", "caches", "Total cache hits"),
                        gauge("hit_ratio", "caches", "Cache hit ratio"),
                        gauge("recent_hit_rate_per_second", "caches", "Recent cache hit rate per second"),
                        gauge("recent_request_rate_per_second", "caches", "Recent cache request rate per second"),
                        counter("request_count", "caches", "Total cache requests"),
                        gauge("size_bytes", "caches", "Current cache size in bytes"),
                    ),
            )

        private fun batchMetrics() =
            VirtualTableDefinition(
                tableName = "batch_metrics",
                labelColumns = listOf("name"),
                metricColumns =
                    listOf(
                        gauge("max", "batch_metrics", "Maximum value"),
                        gauge("p50th", "batch_metrics", "50th percentile"),
                        gauge("p999th", "batch_metrics", "99.9th percentile"),
                        gauge("p99th", "batch_metrics", "99th percentile"),
                    ),
            )

        private fun cqlMetrics() =
            VirtualTableDefinition(
                tableName = "cql_metrics",
                labelColumns = listOf("name"),
                metricColumns =
                    listOf(
                        gauge("value", "cql_metrics", "CQL metric value"),
                    ),
            )

        private fun coordinatorReadLatency() =
            latencyTable("coordinator_read_latency", "Coordinator read")

        private fun coordinatorWriteLatency() =
            latencyTable("coordinator_write_latency", "Coordinator write")

        private fun coordinatorScanLatency() =
            latencyTable("coordinator_scan_latency", "Coordinator scan")

        private fun localReadLatency() =
            latencyTable("local_read_latency", "Local read")

        private fun localWriteLatency() =
            latencyTable("local_write_latency", "Local write")

        private fun localScanLatency() =
            latencyTable("local_scan_latency", "Local scan")

        private fun latencyTable(
            table: String,
            prefix: String,
        ) = VirtualTableDefinition(
            tableName = table,
            labelColumns = listOf("keyspace_name", "table_name"),
            keyspaceFiltered = true,
            metricColumns =
                listOf(
                    counter("count", table, "$prefix operation count"),
                    gauge("max_ms", table, "$prefix max latency in ms"),
                    gauge("p50th_ms", table, "$prefix 50th percentile latency in ms"),
                    gauge("p99th_ms", table, "$prefix 99th percentile latency in ms"),
                    gauge("per_second", table, "$prefix operations per second"),
                ),
        )

        private fun diskUsage() =
            VirtualTableDefinition(
                tableName = "disk_usage",
                labelColumns = listOf("keyspace_name", "table_name"),
                keyspaceFiltered = true,
                metricColumns =
                    listOf(
                        gauge("mebibytes", "disk_usage", "Disk usage in mebibytes"),
                    ),
            )

        private fun maxPartitionSize() =
            VirtualTableDefinition(
                tableName = "max_partition_size",
                labelColumns = listOf("keyspace_name", "table_name"),
                keyspaceFiltered = true,
                metricColumns =
                    listOf(
                        gauge("mebibytes", "max_partition_size", "Max partition size in mebibytes"),
                    ),
            )

        private fun maxSstableSize() =
            VirtualTableDefinition(
                tableName = "max_sstable_size",
                labelColumns = listOf("keyspace_name", "table_name"),
                keyspaceFiltered = true,
                metricColumns =
                    listOf(
                        gauge("mebibytes", "max_sstable_size", "Max SSTable size in mebibytes"),
                    ),
            )

        private fun maxSstableDuration() =
            VirtualTableDefinition(
                tableName = "max_sstable_duration",
                labelColumns = listOf("keyspace_name", "table_name"),
                keyspaceFiltered = true,
                metricColumns =
                    listOf(
                        MetricColumn(
                            cqlColumn = "max_sstable_duration",
                            prometheusName = "${PREFIX}_max_sstable_duration",
                            type = MetricType.GAUGE,
                            help = "Max SSTable duration",
                        ),
                    ),
            )

        private fun rowsPerRead() =
            VirtualTableDefinition(
                tableName = "rows_per_read",
                labelColumns = listOf("keyspace_name", "table_name"),
                keyspaceFiltered = true,
                metricColumns =
                    listOf(
                        counter("count", "rows_per_read", "Total read count"),
                        gauge("max", "rows_per_read", "Max rows per read"),
                        gauge("p50th", "rows_per_read", "50th percentile rows per read"),
                        gauge("p99th", "rows_per_read", "99th percentile rows per read"),
                    ),
            )

        private fun tombstonesPerRead() =
            VirtualTableDefinition(
                tableName = "tombstones_per_read",
                labelColumns = listOf("keyspace_name", "table_name"),
                keyspaceFiltered = true,
                metricColumns =
                    listOf(
                        counter("count", "tombstones_per_read", "Total read count"),
                        gauge("max", "tombstones_per_read", "Max tombstones per read"),
                        gauge("p50th", "tombstones_per_read", "50th percentile tombstones per read"),
                        gauge("p99th", "tombstones_per_read", "99th percentile tombstones per read"),
                    ),
            )

        private fun internodeInbound() =
            VirtualTableDefinition(
                tableName = "internode_inbound",
                labelColumns = listOf("address", "port", "dc", "rack"),
                metricColumns =
                    listOf(
                        counter("corrupt_frames_recovered", "internode_inbound", "Corrupt frames recovered"),
                        counter("corrupt_frames_unrecovered", "internode_inbound", "Corrupt frames unrecovered"),
                        counter("error_bytes", "internode_inbound", "Error bytes"),
                        counter("error_count", "internode_inbound", "Error count"),
                        counter("expired_bytes", "internode_inbound", "Expired bytes"),
                        counter("expired_count", "internode_inbound", "Expired message count"),
                        counter("processed_bytes", "internode_inbound", "Processed bytes"),
                        counter("processed_count", "internode_inbound", "Processed message count"),
                        counter("received_bytes", "internode_inbound", "Received bytes"),
                        counter("received_count", "internode_inbound", "Received message count"),
                        counter("scheduled_bytes", "internode_inbound", "Scheduled bytes"),
                        counter("scheduled_count", "internode_inbound", "Scheduled message count"),
                        counter("throttled_count", "internode_inbound", "Throttled message count"),
                        counter("throttled_nanos", "internode_inbound", "Throttled time in nanoseconds"),
                        gauge("using_bytes", "internode_inbound", "Current bytes in use"),
                        gauge("using_reserve_bytes", "internode_inbound", "Current reserve bytes in use"),
                    ),
            )

        private fun internodeOutbound() =
            VirtualTableDefinition(
                tableName = "internode_outbound",
                labelColumns = listOf("address", "port", "dc", "rack"),
                metricColumns =
                    listOf(
                        gauge("active_connections", "internode_outbound", "Active connections"),
                        counter("connection_attempts", "internode_outbound", "Connection attempts"),
                        counter("error_bytes", "internode_outbound", "Error bytes"),
                        counter("error_count", "internode_outbound", "Error count"),
                        counter("expired_bytes", "internode_outbound", "Expired bytes"),
                        counter("expired_count", "internode_outbound", "Expired message count"),
                        counter("overload_bytes", "internode_outbound", "Overload bytes"),
                        counter("overload_count", "internode_outbound", "Overload message count"),
                        gauge("pending_bytes", "internode_outbound", "Pending bytes"),
                        gauge("pending_count", "internode_outbound", "Pending message count"),
                        counter("sent_bytes", "internode_outbound", "Sent bytes"),
                        counter("sent_count", "internode_outbound", "Sent message count"),
                        counter(
                            "successful_connection_attempts",
                            "internode_outbound",
                            "Successful connection attempts",
                        ),
                        gauge("using_bytes", "internode_outbound", "Current bytes in use"),
                        gauge("using_reserve_bytes", "internode_outbound", "Current reserve bytes in use"),
                    ),
            )

        private fun pendingHints() =
            VirtualTableDefinition(
                tableName = "pending_hints",
                labelColumns = listOf("host_id", "address", "dc", "rack", "status"),
                metricColumns =
                    listOf(
                        gauge("files", "pending_hints", "Number of pending hint files"),
                        gauge("port", "pending_hints", "Target port"),
                    ),
            )

        private fun streaming() =
            VirtualTableDefinition(
                tableName = "streaming",
                labelColumns = listOf("id", "operation", "status"),
                metricColumns =
                    listOf(
                        counter("bytes_received", "streaming", "Bytes received"),
                        counter("bytes_sent", "streaming", "Bytes sent"),
                        gauge("bytes_to_receive", "streaming", "Bytes remaining to receive"),
                        gauge("bytes_to_send", "streaming", "Bytes remaining to send"),
                        gauge("duration_millis", "streaming", "Duration in milliseconds"),
                        counter("files_received", "streaming", "Files received"),
                        counter("files_sent", "streaming", "Files sent"),
                        gauge("files_to_receive", "streaming", "Files remaining to receive"),
                        gauge("files_to_send", "streaming", "Files remaining to send"),
                        gauge("progress_percentage", "streaming", "Progress percentage"),
                    ),
            )

        private fun sstableTasks() =
            VirtualTableDefinition(
                tableName = "sstable_tasks",
                labelColumns = listOf("keyspace_name", "table_name", "task_id", "kind", "unit"),
                keyspaceFiltered = true,
                metricColumns =
                    listOf(
                        gauge("completion_ratio", "sstable_tasks", "Task completion ratio"),
                        gauge("progress", "sstable_tasks", "Task progress"),
                        gauge("sstables", "sstable_tasks", "Number of SSTables involved"),
                        gauge("total", "sstable_tasks", "Total work units"),
                    ),
            )

        private fun clients() =
            VirtualTableDefinition(
                tableName = "clients",
                labelColumns = listOf("address", "port", "driver_name", "driver_version", "keyspace_name", "username"),
                metricColumns =
                    listOf(
                        gauge("protocol_version", "clients", "Client protocol version"),
                        counter("request_count", "clients", "Client request count"),
                    ),
            )

        private fun snapshots() =
            VirtualTableDefinition(
                tableName = "snapshots",
                labelColumns = listOf("name", "keyspace_name", "table_name"),
                metricColumns =
                    listOf(
                        gauge("size_on_disk", "snapshots", "Snapshot size on disk in bytes"),
                        gauge("true_size", "snapshots", "Snapshot true size in bytes"),
                    ),
            )

        private fun queries() =
            VirtualTableDefinition(
                tableName = "queries",
                labelColumns = listOf("thread_id"),
                metricColumns =
                    listOf(
                        gauge("queued_micros", "queries", "Time queued in microseconds"),
                        gauge("running_micros", "queries", "Time running in microseconds"),
                    ),
            )

        private fun cidrFilteringMetricsCounts() =
            VirtualTableDefinition(
                tableName = "cidr_filtering_metrics_counts",
                labelColumns = listOf("name"),
                metricColumns =
                    listOf(
                        counter("value", "cidr_filtering_metrics_counts", "CIDR filtering count"),
                    ),
            )

        private fun cidrFilteringMetricsLatencies() =
            VirtualTableDefinition(
                tableName = "cidr_filtering_metrics_latencies",
                labelColumns = listOf("name"),
                metricColumns =
                    listOf(
                        gauge("max", "cidr_filtering_metrics_latencies", "Max latency"),
                        gauge("p50th", "cidr_filtering_metrics_latencies", "50th percentile latency"),
                        gauge("p95th", "cidr_filtering_metrics_latencies", "95th percentile latency"),
                        gauge("p999th", "cidr_filtering_metrics_latencies", "99.9th percentile latency"),
                        gauge("p99th", "cidr_filtering_metrics_latencies", "99th percentile latency"),
                    ),
            )

        private fun settings() =
            rowCountOnly("settings", "Number of Cassandra settings entries")

        private fun systemProperties() =
            rowCountOnly("system_properties", "Number of system properties entries")

        private fun gossipInfo() =
            rowCountOnly("gossip_info", "Number of gossip info entries")

        private fun credentialsCacheKeys() =
            rowCountOnly("credentials_cache_keys", "Number of entries in credentials cache")

        private fun jmxPermissionsCacheKeys() =
            rowCountOnly("jmx_permissions_cache_keys", "Number of entries in JMX permissions cache")

        private fun networkPermissionsCacheKeys() =
            rowCountOnly("network_permissions_cache_keys", "Number of entries in network permissions cache")

        private fun permissionsCacheKeys() =
            rowCountOnly("permissions_cache_keys", "Number of entries in permissions cache")

        private fun rolesCacheKeys() =
            rowCountOnly("roles_cache_keys", "Number of entries in roles cache")

        private fun repairs() =
            repairTable("repairs", "Repair operations")

        private fun repairSessions() =
            repairTable("repair_sessions", "Repair sessions")

        private fun repairJobs() =
            repairTable("repair_jobs", "Repair jobs")

        private fun repairValidations() =
            repairTable("repair_validations", "Repair validations")

        private fun repairParticipates() =
            repairTable("repair_participates", "Repair participates")

        private fun repairTable(
            table: String,
            description: String,
        ) = VirtualTableDefinition(
            tableName = table,
            labelColumns = listOf("status"),
            metricColumns =
                listOf(
                    gauge("row_count", table, "$description count by status"),
                ),
        )

        private fun saiColumnIndexes() =
            VirtualTableDefinition(
                tableName = "sai_column_indexes",
                labelColumns = listOf("keyspace_name", "index_name", "table_name", "column_name"),
                metricColumns =
                    listOf(
                        gauge("is_queryable", "sai_column_indexes", "Whether SAI index is queryable (1=true, 0=false)"),
                        gauge("is_building", "sai_column_indexes", "Whether SAI index is building (1=true, 0=false)"),
                        gauge("is_string", "sai_column_indexes", "Whether SAI index is on a string column (1=true, 0=false)"),
                    ),
            )

        private fun saiSstableIndexes() =
            VirtualTableDefinition(
                tableName = "sai_sstable_indexes",
                labelColumns = listOf("keyspace_name", "index_name", "sstable_name", "table_name"),
                metricColumns =
                    listOf(
                        gauge("cell_count", "sai_sstable_indexes", "Cell count in SSTable index"),
                        gauge("max_row_id", "sai_sstable_indexes", "Max row ID"),
                        gauge("min_row_id", "sai_sstable_indexes", "Min row ID"),
                        gauge("per_column_disk_size", "sai_sstable_indexes", "Per-column disk size in bytes"),
                        gauge("per_table_disk_size", "sai_sstable_indexes", "Per-table disk size in bytes"),
                    ),
            )

        private fun saiSstableIndexSegments() =
            VirtualTableDefinition(
                tableName = "sai_sstable_index_segments",
                labelColumns =
                    listOf(
                        "keyspace_name",
                        "index_name",
                        "sstable_name",
                        "segment_row_id_offset",
                        "table_name",
                        "column_name",
                    ),
                metricColumns =
                    listOf(
                        gauge("cell_count", "sai_sstable_index_segments", "Cell count in segment"),
                        gauge("max_sstable_row_id", "sai_sstable_index_segments", "Max SSTable row ID in segment"),
                        gauge("min_sstable_row_id", "sai_sstable_index_segments", "Min SSTable row ID in segment"),
                    ),
            )

        private fun rowCountOnly(
            table: String,
            help: String,
        ) = VirtualTableDefinition(
            tableName = table,
            labelColumns = emptyList(),
            metricColumns =
                listOf(
                    gauge("row_count", table, help),
                ),
        )

        private fun gauge(
            column: String,
            table: String,
            help: String,
        ) = MetricColumn(column, "${PREFIX}_${table}_$column", MetricType.GAUGE, help)

        private fun counter(
            column: String,
            table: String,
            help: String,
        ) = MetricColumn(column, "${PREFIX}_${table}_$column", MetricType.COUNTER, help)
    }
}
