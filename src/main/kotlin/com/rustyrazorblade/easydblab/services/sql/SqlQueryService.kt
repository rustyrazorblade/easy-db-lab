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

/** Default factory that delegates to [DriverManager.getConnection]. */
val defaultJdbcConnectionFactory: JdbcConnectionFactory =
    JdbcConnectionFactory { url, props -> DriverManager.getConnection(url, props) }
