package com.rustyrazorblade.easydblab.services.sql

import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * The result of a successfully executed SQL query via JDBC.
 *
 * @property columns Ordered list of column names.
 * @property rows Each row as a list of string values, one per column.
 */
data class SqlQueryResult(
    val columns: List<String>,
    val rows: List<List<String>>,
)

/**
 * Factory for creating JDBC connections. Abstracted to allow test injection
 * of mock connections without a real cluster.
 */
fun interface JdbcConnectionFactory {
    fun connect(
        url: String,
        properties: Properties,
    ): Connection
}

/**
 * Ensures all bundled JDBC drivers are registered with [DriverManager].
 *
 * In a fat JAR (shadow JAR), the ServiceLoader mechanism that normally
 * auto-registers JDBC drivers via META-INF/services/java.sql.Driver may not
 * work correctly when multiple drivers are present. Explicitly loading each
 * driver class forces its static initializer to run, which registers the
 * driver instance with [DriverManager]. This is the canonical JDBC 4.0
 * workaround for fat JAR environments.
 */
fun ensureJdbcDriversLoaded() {
    listOf(
        "org.postgresql.Driver",
        "com.mysql.cj.jdbc.Driver",
        "com.clickhouse.jdbc.ClickHouseDriver",
        "com.facebook.presto.jdbc.PrestoDriver",
        "io.trino.jdbc.TrinoDriver",
    ).forEach { driverClass ->
        runCatching { Class.forName(driverClass) }
    }
}

/** Default factory that delegates to [DriverManager.getConnection]. */
val defaultJdbcConnectionFactory: JdbcConnectionFactory =
    JdbcConnectionFactory { url, props ->
        ensureJdbcDriversLoaded()
        DriverManager.getConnection(url, props)
    }
