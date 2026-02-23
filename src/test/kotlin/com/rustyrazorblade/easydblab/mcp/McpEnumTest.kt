package com.rustyrazorblade.easydblab.mcp

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.PicoCommand
import com.rustyrazorblade.easydblab.events.Event
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import picocli.CommandLine.Command
import picocli.CommandLine.Option

class McpEnumTest : BaseKoinTest() {
    private lateinit var registry: McpToolRegistry

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                factory { TestCommandWithEnum() }
            },
        )

    @BeforeEach
    fun setup() {
        registry = McpToolRegistry()
    }

    @Test
    fun `test enum schema generation with type property`() {
        // Create a test command with enum
        val testCommand = TestCommandWithEnum()

        // Use the public generatePicoSchema method to test schema generation
        val schema = registry.generatePicoSchema(testCommand)

        // Print the schema to see what's being generated
        println("Generated schema for enum command:")
        println(
            Json { prettyPrint = true }
                .encodeToString(JsonObject.serializer(), schema),
        )

        // Verify the schema is not null and has some content
        assertThat(schema).isNotNull
        assertThat(schema.size).isGreaterThan(0)
    }

    @Test
    fun `test enum value mapping from arguments`() {
        val testCommand = TestCommandWithEnum()

        // Create arguments with enum value
        val arguments =
            buildJsonObject {
                put("arch", "arm64")
                put("mode", "production")
            }

        // Map arguments to PicoCLI command using reflection
        val mapArgumentsMethod =
            McpToolRegistry::class.java
                .getDeclaredMethod(
                    "mapArgumentsToPicoCommand",
                    PicoCommand::class.java,
                    JsonObject::class.java,
                ).apply { isAccessible = true }

        mapArgumentsMethod.invoke(registry, testCommand, arguments)

        // Verify enum fields were set correctly
        assertThat(testCommand.arch).isEqualTo(TestArch.ARM64)
        assertThat(testCommand.mode).isEqualTo(TestMode.PRODUCTION)
    }

    // Test enum with type property (like Arch)
    enum class TestArch(
        val type: String,
    ) {
        AMD64("amd64"),
        ARM64("arm64"),
    }

    // Test enum without type property
    enum class TestMode {
        DEVELOPMENT,
        STAGING,
        PRODUCTION,
    }

    @Command(name = "enum-test", description = ["Test command with enum parameters"])
    class TestCommandWithEnum : PicoBaseCommand() {
        @Option(names = ["--arch", "-a"], description = ["CPU architecture"])
        var arch: TestArch = TestArch.AMD64

        @Option(names = ["--mode", "-m"], description = ["Deployment mode"])
        var mode: TestMode = TestMode.DEVELOPMENT

        override fun execute() {
            eventBus.emit(Event.Message("Executing with arch=$arch, mode=$mode"))
        }
    }
}
