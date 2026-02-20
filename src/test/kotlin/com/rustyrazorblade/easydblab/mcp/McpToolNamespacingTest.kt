package com.rustyrazorblade.easydblab.mcp

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.commands.Status
import com.rustyrazorblade.easydblab.commands.cassandra.Start
import com.rustyrazorblade.easydblab.commands.cassandra.UpdateConfig
import com.rustyrazorblade.easydblab.commands.cassandra.stress.StressStart
import com.rustyrazorblade.easydblab.commands.clickhouse.ClickHouseStart
import com.rustyrazorblade.easydblab.commands.opensearch.OpenSearchStart
import com.rustyrazorblade.easydblab.commands.spark.SparkSubmit
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
    fun `clickhouse namespace is correct`() {
        val command = ClickHouseStart()
        val toolName = registry.generateToolName(command, "start")

        assertThat(toolName).isEqualTo("clickhouse_start")
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
}
