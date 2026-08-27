package com.rustyrazorblade.easydblab.mcp

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.commands.Status
import com.rustyrazorblade.easydblab.commands.cassandra.Start
import com.rustyrazorblade.easydblab.commands.cassandra.UpdateConfig
import com.rustyrazorblade.easydblab.commands.cassandra.profiler.ProfilingStart
import com.rustyrazorblade.easydblab.commands.cassandra.stress.StressStart
import com.rustyrazorblade.easydblab.commands.opensearch.OpenSearchStart
import com.rustyrazorblade.easydblab.commands.spark.SparkSubmit
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for MCP tool name namespacing.
 *
 * Verifies that tool names are generated correctly based on the command's
 * package path and @Command(name=...) annotation value.
 */
class McpToolNamespacingTest : BaseKoinTest() {
    private lateinit var registry: McpToolRegistry

    @BeforeEach
    fun setup() {
        registry = McpToolRegistry()
    }

    @Test
    fun `top-level command generates simple name without namespace`() {
        // Status is in com.rustyrazorblade.easydblab.commands (top-level)
        val command = Status()
        val toolName = registry.generateToolName(command, "status")

        assertThat(toolName).isEqualTo("status")
    }

    @Test
    fun `single-level nested command generates namespace_name`() {
        // Start is in com.rustyrazorblade.easydblab.commands.cassandra
        val command = Start()
        val toolName = registry.generateToolName(command, "start")

        assertThat(toolName).isEqualTo("cassandra_start")
    }

    @Test
    fun `double-level nested command generates full namespace_name`() {
        // StressStart is in com.rustyrazorblade.easydblab.commands.cassandra.stress
        val command = StressStart()
        val toolName = registry.generateToolName(command, "start")

        assertThat(toolName).isEqualTo("cassandra_stress_start")
    }

    @Test
    fun `hyphenated command name converts to underscores`() {
        // UpdateConfig has name="update-config"
        val command = UpdateConfig()
        val toolName = registry.generateToolName(command, "update-config")

        assertThat(toolName).isEqualTo("cassandra_update_config")
    }

    @Test
    fun `opensearch namespace is correct`() {
        val command = OpenSearchStart()
        val toolName = registry.generateToolName(command, "start")

        assertThat(toolName).isEqualTo("opensearch_start")
    }

    @Test
    fun `spark namespace is correct`() {
        val command = SparkSubmit()
        val toolName = registry.generateToolName(command, "submit")

        assertThat(toolName).isEqualTo("spark_submit")
    }

    @Test
    fun `getTools returns uniquely named tools`() {
        val tools = registry.getTools()
        val toolNames = tools.map { it.name }

        // Verify all names are unique
        assertThat(toolNames).doesNotHaveDuplicates()
    }

    @Test
    fun `getTools includes expected namespaced tool names`() {
        val tools = registry.getTools()
        val toolNames = tools.map { it.name }

        // Verify some expected namespaced names exist
        // Note: Only commands with @McpCommand annotation are included
        assertThat(toolNames).contains("status")
        assertThat(toolNames).contains("cassandra_start")
        assertThat(toolNames).contains("cassandra_stress_start")
    }

    @Test
    fun `triple-level nested command generates full namespace_name`() {
        assertThat(registry.generateToolName(ProfilingStart(), "start")).isEqualTo("cassandra_profiler_start")
    }

    @Test
    fun `every command annotated for MCP is actually registered`() {
        // The annotation is inert on its own — getTools() walks a hand-maintained list, so a class
        // can carry @McpCommand and be invisible to every MCP client.
        val toolNames = registry.getTools().map { it.name }

        assertThat(toolNames).contains(
            "cassandra_profiler_start",
            "cassandra_profiler_stop",
            "cassandra_profiler_status",
            "cassandra_profiler_fetch",
            "cassandra_profiler_flamegraph",
        )
    }

    @Test
    fun `a trailing passthrough parameter list is reachable over MCP`() {
        // profile start's whole point is forwarding async-profiler arguments. The schema generator
        // only walked @Option and @Mixin, so over MCP those arguments could not be supplied at all.
        val schema = registry.generatePicoSchema(ProfilingStart())
        val asprofArgs = schema["asprofArgs"]?.jsonObject

        assertThat(asprofArgs).isNotNull
        assertThat(asprofArgs?.get("type")?.jsonPrimitive?.content).isEqualTo("array")
        assertThat(
            asprofArgs
                ?.get("items")
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content,
        ).isEqualTo("string")
    }

    @Test
    fun `passthrough arguments supplied over MCP reach the command`() {
        val command = ProfilingStart()

        registry.mapArgumentsToPicoCommand(
            command,
            buildJsonObject {
                putJsonArray("asprofArgs") {
                    add("-e")
                    add("wall")
                }
            },
        )

        assertThat(command.asprofArgs).containsExactly("-e", "wall")
    }
}
