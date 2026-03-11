package com.rustyrazorblade.easydblab.configuration

// Extension functions for ClusterS3Path providing convenience methods
// for technology-specific directory paths.
//
// These are pure path builders that delegate to ClusterS3Path.resolve.
// Config file paths are in ClusterS3PathConfigExtensions.kt.

/** Path for Cassandra data and backups. */
fun ClusterS3Path.cassandra(): ClusterS3Path = resolve(ClusterS3Path.CASSANDRA_DIR)

/** Path for ClickHouse data. */
fun ClusterS3Path.clickhouse(): ClusterS3Path = resolve(ClusterS3Path.CLICKHOUSE_DIR)

/** Path for Spark JARs and data. */
fun ClusterS3Path.spark(): ClusterS3Path = resolve(ClusterS3Path.SPARK_DIR)

/** Path for EMR logs. */
fun ClusterS3Path.emrLogs(): ClusterS3Path = resolve(ClusterS3Path.SPARK_DIR).resolve(ClusterS3Path.EMR_LOGS_DIR)

/** Path for backups. */
fun ClusterS3Path.backups(): ClusterS3Path = resolve(ClusterS3Path.BACKUPS_DIR)

/** Path for log aggregation. */
fun ClusterS3Path.logs(): ClusterS3Path = resolve(ClusterS3Path.LOGS_DIR)

/** Path for general data storage. */
fun ClusterS3Path.data(): ClusterS3Path = resolve(ClusterS3Path.DATA_DIR)

/** Path for Tempo trace storage. */
fun ClusterS3Path.tempo(): ClusterS3Path = resolve(ClusterS3Path.TEMPO_DIR)

/** Path for Pyroscope profile storage. */
fun ClusterS3Path.pyroscope(): ClusterS3Path = resolve(ClusterS3Path.PYROSCOPE_DIR)

/** Path for VictoriaMetrics backups. */
fun ClusterS3Path.victoriaMetrics(): ClusterS3Path = resolve(ClusterS3Path.VICTORIA_METRICS_DIR)

/** Path for VictoriaLogs backups. */
fun ClusterS3Path.victoriaLogs(): ClusterS3Path = resolve(ClusterS3Path.VICTORIA_LOGS_DIR)
