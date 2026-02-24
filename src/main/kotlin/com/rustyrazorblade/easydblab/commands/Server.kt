package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.mcp.McpServer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File

/**
 * Starts the MCP (Model Context Protocol) server for AI assistant integration.
 */
@RequireProfileSetup
@Command(
    name = "server",
    description = [
        "Start MCP server for AI assistant integration. " +
            "Add to claude with: claude mcp add --transport sse easy-db-lab http://127.0.0.1:<port>/sse",
    ],
)
class Server : PicoBaseCommand() {
    @Option(
        names = ["--port", "-p"],
        description = ["MCP server port (0 = any free port)"],
    )
    var port: Int = 0

    @Option(
        names = ["--bind", "-b"],
        description = ["Address to bind the server to (default: 127.0.0.1)"],
    )
    var bind: String = "127.0.0.1"

    @Option(
        names = ["--refresh", "-r"],
        description = ["Status cache refresh interval in seconds (default: 30)"],
    )
    var refreshInterval: Long = Constants.Time.DEFAULT_STATUS_REFRESH_SECONDS

    companion object {
        private val log = KotlinLogging.logger {}
    }

    @Serializable
    data class McpServerConfig(
        val type: String,
        val url: String,
    )

    @Serializable
    data class McpConfiguration(
        val mcpServers: Map<String, McpServerConfig>,
    )

    override fun execute() {
        // Mark context as interactive so CQL sessions stay open across commands
        context.isInteractive = true

        log.info { "Starting easy-db-lab MCP server..." }

        try {
            val server = McpServer(refreshInterval)
            server.start(port, bind) { actualPort ->
                // Generate .mcp.json with actual port
                val config =
                    McpConfiguration(
                        mcpServers =
                            mapOf(
                                "easy-db-lab" to
                                    McpServerConfig(
                                        type = "sse",
                                        url = "http://$bind:$actualPort/sse",
                                    ),
                            ),
                    )
                val json = Json { prettyPrint = true }
                val configFile = File(".mcp.json")
                configFile.writeText(json.encodeToString(config))

                eventBus.emit(Event.Command.McpConfigSaved(configFile.absolutePath))
            }

            log.info { "MCP server stopped." }
        } catch (e: RuntimeException) {
            log.error(e) { "Failed to start MCP server" }
            System.err.println("Failed to start MCP server: ${e.message}")
            throw e
        }
    }
}
