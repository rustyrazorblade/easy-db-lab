package com.rustyrazorblade.easydblab.commands.clickhouse

import com.rustyrazorblade.easydblab.annotations.KitCommand
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.clickhouse.ClickHouseService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File

/**
 * Executes a SQL statement against the ClickHouse cluster via the ClickHouse JDBC driver.
 *
 * Only available when the clickhouse kit is installed in the current cluster workspace.
 * Results are displayed in a tabular format. For large result sets, add a LIMIT clause
 * to avoid accumulating all rows in memory.
 *
 * Examples:
 *   easy-db-lab clickhouse sql "SELECT count(*) FROM default.events"
 *   easy-db-lab clickhouse sql --file query.sql
 */
@KitCommand(kit = "clickhouse", name = "sql")
@McpCommand
@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "sql",
    description = ["Execute SQL against the ClickHouse cluster"],
)
class ClickHouseSql : PicoBaseCommand() {
    private val clickHouseService: ClickHouseService by inject()

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
                        |Usage: easy-db-lab clickhouse sql <statement>
                        |       easy-db-lab clickhouse sql --file <file.sql>
                        |
                        |Examples:
                        |  easy-db-lab clickhouse sql "SELECT count(*) FROM default.events"
                        |  easy-db-lab clickhouse sql --file query.sql
                        """.trimMargin(),
                    )
                    return
                }
            }

        clickHouseService
            .execute(sql.trim().trimEnd(';'))
            .onSuccess { result ->
                eventBus.emit(Event.Sql.QueryOutput(result.columns, result.rows))
            }.onFailure { e ->
                eventBus.emit(Event.Sql.QueryError(e.message ?: "Unknown error"))
            }
    }
}
