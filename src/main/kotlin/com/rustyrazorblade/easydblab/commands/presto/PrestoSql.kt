package com.rustyrazorblade.easydblab.commands.presto

import com.rustyrazorblade.easydblab.annotations.KitCommand
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.presto.PrestoService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File

/**
 * Executes a SQL statement against the Presto cluster via the Presto JDBC driver.
 *
 * Only available when the presto kit is installed in the current cluster workspace.
 * Results are displayed in a tabular format. For large result sets, add a LIMIT clause
 * to avoid accumulating all rows in memory.
 *
 * Examples:
 *   easy-db-lab presto sql "SELECT count(*) FROM cassandra.keyspace.table"
 *   easy-db-lab presto sql --file query.sql
 */
@KitCommand(kit = "presto", name = "sql")
@McpCommand
@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "sql",
    description = ["Execute SQL against the Presto cluster"],
)
class PrestoSql : PicoBaseCommand() {
    private val prestoService: PrestoService by inject()

    @Parameters(index = "0", description = ["SQL statement to execute"], arity = "0..1")
    var statement: String? = null

    @Option(names = ["--file", "-f"], description = ["Execute SQL from a local file"])
    var file: File? = null

    override fun execute() {
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
                        |Usage: easy-db-lab presto sql <statement>
                        |       easy-db-lab presto sql --file <file.sql>
                        |
                        |Examples:
                        |  easy-db-lab presto sql "SELECT count(*) FROM cassandra.keyspace.table"
                        |  easy-db-lab presto sql --file query.sql
                        """.trimMargin(),
                    )
                    return
                }
            }

        prestoService
            .execute(sql.trim().trimEnd(';'))
            .onSuccess { result ->
                eventBus.emit(Event.Sql.QueryOutput(result.columns, result.rows))
            }.onFailure { e ->
                eventBus.emit(Event.Sql.QueryError(e.message ?: "Unknown error"))
            }
    }
}
