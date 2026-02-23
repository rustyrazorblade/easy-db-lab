package com.rustyrazorblade.easydblab.mcp

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.PicoCommand
import com.rustyrazorblade.easydblab.events.Event
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import kotlin.reflect.KClass

// Test command for MCP annotation testing
@McpCommand
@Command(name = "test-mcp-command", description = ["A test command with MCP annotation"])
class TestMcpCommand : PicoBaseCommand() {
    @Option(names = ["--test-param"], description = ["Test parameter"])
    var testParam: String = ""

    override fun execute() {
        if (testParam.isNotEmpty()) {
            eventBus.emit(Event.Message("Test MCP command executed with param: $testParam"))
        } else {
            eventBus.emit(Event.Message("Test MCP command executed"))
        }
    }
}

// Test registry that includes test commands
class TestMcpToolRegistry : McpToolRegistry() {
    companion object {
        // Additional test commands
        private val testCommandClasses: List<KClass<out PicoCommand>> =
            listOf(TestMcpCommand::class)
    }

    override fun getTools(): List<ToolInfo> {
        // Get the real tools and add our test commands
        val realTools = super.getTools()
        val testTools =
            testCommandClasses
                .filter { cls ->
                    cls.java.isAnnotationPresent(McpCommand::class.java)
                }.map { cls -> createTestToolInfo(cls) }

        return realTools + testTools
    }

    private fun createTestToolInfo(commandClass: KClass<out PicoCommand>): ToolInfo {
        val tempCommand = getKoin().get<PicoCommand>(commandClass)
        val commandAnnotation =
            commandClass.java.getAnnotation(picocli.CommandLine.Command::class.java)
        val commandName = commandAnnotation?.name ?: commandClass.simpleName?.lowercase() ?: "unknown"
        val description = commandAnnotation?.description?.firstOrNull() ?: "No description available"

        return ToolInfo(
            name = generateToolName(tempCommand, commandName),
            description = description,
            inputSchema = generatePicoSchema(tempCommand),
            commandClass = commandClass,
        )
    }
}

class McpAnnotationTest : BaseKoinTest() {
    private lateinit var registry: TestMcpToolRegistry

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                // Register test command in Koin
                factory { TestMcpCommand() }
            },
        )

    @BeforeEach
    fun setup() {
        registry = TestMcpToolRegistry()
    }

    @Test
    fun `should detect and register commands with MCP annotation`() {
        val tools = registry.getTools()

        // Find our test command
        val testTool = tools.find { it.name == "test_mcp_command" }

        assertThat(testTool).isNotNull
        assertThat(testTool?.description).isEqualTo("A test command with MCP annotation")
    }

    @Test
    fun `should execute MCP annotated command`() {
        val result = registry.executeTool("test_mcp_command", null)

        assertThat(result.isError).isFalse
        assertThat(result.content).contains("Tool executed successfully")
    }

    @Test
    fun `should handle parameters for MCP annotated commands`() {
        val arguments = buildJsonObject { put("testParam", "hello") }

        val result = registry.executeTool("test_mcp_command", arguments)

        assertThat(result.isError).isFalse
        assertThat(result.content).contains("Tool executed successfully")
    }
}
