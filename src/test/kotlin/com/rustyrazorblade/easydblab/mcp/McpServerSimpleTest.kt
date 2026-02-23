package com.rustyrazorblade.easydblab.mcp

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.events.Event
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import picocli.CommandLine.Command
import picocli.CommandLine.Option

class McpServerSimpleTest : BaseKoinTest() {
    private lateinit var registry: McpToolRegistry

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                factory { SimpleTestCommand() }
            },
        )

    @BeforeEach
    fun setup() {
        registry = McpToolRegistry()
    }

    @Test
    fun `test schema generation for simple command`() {
        // Create a test command
        val testCommand = SimpleTestCommand()

        // Use the public generatePicoSchema method
        val schema = registry.generatePicoSchema(testCommand)

        // Print the schema to see what's being generated
        println("Generated schema for simple command:")
        println(Json.encodeToString(JsonObject.serializer(), schema))

        // Verify the schema is not null and has some content
        assertThat(schema).isNotNull
        assertThat(schema.size).isGreaterThan(0)
    }

    @Test
    fun `test tool name generation`() {
        val testCommand = SimpleTestCommand()

        // Test the generateToolName method
        val toolName = registry.generateToolName(testCommand, "simple-test")

        // Note: hyphens are normalized to underscores in tool names
        assertThat(toolName).isEqualTo("simple_test")
    }

    @Command(name = "simple-test", description = ["Simple test command"])
    class SimpleTestCommand : PicoBaseCommand() {
        @Option(names = ["--name", "-n"], description = ["Name parameter"], required = true)
        var name: String = ""

        @Option(names = ["--count"], description = ["Count parameter"])
        var count: Int = 0

        @Option(names = ["--enabled"], description = ["Enabled flag"])
        var enabled: Boolean = false

        override fun execute() {
            eventBus.emit(
                Event.Message("Executing with name=$name, count=$count, enabled=$enabled"),
            )
        }
    }
}
