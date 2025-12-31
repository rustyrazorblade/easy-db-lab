package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.services.CqlSessionService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File

/**
 * Execute CQL statements on the Cassandra cluster.
 *
 * Examples:
 *   easy-db-lab cassandra cql "SELECT * FROM system.local"
 *   easy-db-lab cassandra cql --file schema.cql
 *   easy-db-lab cassandra cql "CREATE KEYSPACE test WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}"
 */
@McpCommand
@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "cql",
    description = ["Execute CQL on the Cassandra cluster"],
)
class Cql : PicoBaseCommand() {
    private val cqlSessionService: CqlSessionService by inject()

    @Parameters(index = "0", description = ["CQL statement to execute"], arity = "0..1")
    var statement: String? = null

    @Option(names = ["--file", "-f"], description = ["Execute CQL from a local file"])
    var file: File? = null

    override fun execute() {
        val cql =
            when {
                file != null -> {
                    if (!file!!.exists()) {
                        outputHandler.handleMessage("Error: File not found: ${file!!.absolutePath}")
                        return
                    }
                    file!!.readText()
                }
                statement != null -> statement!!
                else -> {
                    outputHandler.handleMessage("Usage: easy-db-lab cassandra cql <statement>")
                    outputHandler.handleMessage("       easy-db-lab cassandra cql --file <file.cql>")
                    outputHandler.handleMessage("")
                    outputHandler.handleMessage("Examples:")
                    outputHandler.handleMessage("  easy-db-lab cassandra cql \"SELECT * FROM system.local\"")
                    outputHandler.handleMessage("  easy-db-lab cassandra cql --file schema.cql")
                    return
                }
            }

        // Execute as a single query (trim trailing semicolon if present)
        val query = cql.trim().trimEnd(';')

        cqlSessionService
            .execute(query)
            .onSuccess { output ->
                if (output.isNotBlank()) {
                    outputHandler.handleMessage(output)
                } else {
                    // DDL statements (CREATE, ALTER, DROP, etc.) return no rows
                    outputHandler.handleMessage("OK")
                }
            }.onFailure { e ->
                outputHandler.handleMessage("Error: ${e.message}")
            }
        // Note: Session cleanup is handled by ResourceManager via CommandExecutor
    }
}
