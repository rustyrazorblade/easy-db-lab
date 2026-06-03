package com.rustyrazorblade.easydblab.commands.kit

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.sql.JdbcConnectionFactory
import com.rustyrazorblade.easydblab.services.sql.KitJdbcSqlService
import com.rustyrazorblade.easydblab.services.sql.defaultJdbcConnectionFactory
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File

/**
 * Generic SQL command generated from a kit's `sql` capability declaration in kit.yaml.
 *
 * A single instance is registered under each SQL-capable kit's subcommand group at startup —
 * no per-kit Kotlin class is required. Configuration (JDBC endpoint, user, driver class) is
 * provided at registration time from the kit's [KitConfig][com.rustyrazorblade.easydblab.services.KitConfig].
 *
 * If [driverClass] is non-blank, the class is force-loaded before the first connection attempt.
 * This is required for JDBC drivers (e.g. the Facebook Presto driver) that do not auto-register
 * via ServiceLoader in fat-JAR environments.
 *
 * The [connectionFactory] parameter is injectable for testing; production code uses the default
 * [DriverManager.getConnection][java.sql.DriverManager.getConnection]-backed factory.
 */
@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "sql",
    description = ["Execute SQL against the database"],
)
class KitSqlCommand(
    private val kitName: String,
    private val endpoint: KitEndpoint,
    private val user: String,
    private val driverClass: String = "",
    private val connectionFactory: JdbcConnectionFactory = defaultJdbcConnectionFactory,
) : PicoBaseCommand() {
    @Parameters(index = "0", description = ["SQL statement to execute"], arity = "0..1")
    var statement: String? = null

    @Option(names = ["--file", "-f"], description = ["Execute SQL from a local file"])
    var file: File? = null

    override fun execute() {
        if (driverClass.isNotBlank()) {
            runCatching { Class.forName(driverClass) }
                .onFailure { e ->
                    eventBus.emit(Event.Sql.QueryError("Driver class '$driverClass' not found: ${e.message}"))
                    return
                }
        }

        val localFile = file
        val localStatement = statement
        val sql =
            when {
                localFile != null -> {
                    if (!localFile.exists()) {
                        eventBus.emit(Event.Sql.FileNotFound(localFile.absolutePath))
                        return
                    }
                    localFile.readText()
                }
                localStatement != null -> localStatement
                else -> {
                    println(
                        """
                        |Usage: easy-db-lab $kitName sql <statement>
                        |       easy-db-lab $kitName sql --file <file.sql>
                        |
                        |Examples:
                        |  easy-db-lab $kitName sql "SELECT 1"
                        |  easy-db-lab $kitName sql --file query.sql
                        """.trimMargin(),
                    )
                    return
                }
            }

        KitJdbcSqlService(
            clusterStateManager = clusterStateManager,
            endpoint = endpoint,
            user = user,
            connectionFactory = connectionFactory,
        ).execute(sql.trim().trimEnd(';'))
            .onSuccess { result ->
                eventBus.emit(Event.Sql.QueryOutput(result.columns, result.rows))
            }.onFailure { e ->
                eventBus.emit(Event.Sql.QueryError(e.message ?: "Unknown error"))
            }
    }
}
