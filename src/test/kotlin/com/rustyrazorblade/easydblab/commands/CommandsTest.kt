package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import picocli.CommandLine
import picocli.CommandLine.Command
import java.io.ByteArrayOutputStream
import java.io.PrintStream

@Command(name = "test-root", description = ["Test root command"])
class TestRootCommand : Runnable {
    override fun run() {
        // no-op for test
    }
}

class CommandsTest : BaseKoinTest() {
    private val stdout = ByteArrayOutputStream()
    private val originalOut = System.out

    @BeforeEach
    fun setup() {
        System.setOut(PrintStream(stdout))
    }

    @AfterEach
    fun restoreStdout() {
        System.setOut(originalOut)
        stdout.reset()
    }

    @Test
    fun `execute outputs command tree`() {
        val parentCmd = CommandLine(TestRootCommand())

        val commandsCmd = Commands()
        val commandsCmdLine = CommandLine(commandsCmd)
        parentCmd.addSubcommand("commands", commandsCmdLine)

        commandsCmd.spec = commandsCmdLine.commandSpec

        commandsCmd.execute()

        val output = stdout.toString()
        assertThat(output).contains("test-root")
        assertThat(output).contains("commands")
    }

    @Test
    fun `execute prints options for subcommands`() {
        val parentCmd = CommandLine(TestRootCommand())

        @Command(name = "sub", description = ["A sub command"])
        class SubCommand : Runnable {
            @CommandLine.Option(names = ["--verbose"], description = ["Enable verbose output"])
            var verbose: Boolean = false

            override fun run() {
                // no-op for test
            }
        }

        parentCmd.addSubcommand("sub", CommandLine(SubCommand()))

        val commandsCmd = Commands()
        val commandsCmdLine = CommandLine(commandsCmd)
        parentCmd.addSubcommand("commands", commandsCmdLine)

        commandsCmd.spec = commandsCmdLine.commandSpec

        commandsCmd.execute()

        val output = stdout.toString()
        assertThat(output).contains("sub")
        assertThat(output).contains("--verbose")
    }

    @Test
    fun `a subcommand with an alias is listed once and names the alias`() {
        @Command(name = "sub", aliases = ["s"], description = ["A sub command"])
        class SubCommand : Runnable {
            override fun run() {
                // no-op for test
            }
        }

        val output = treeOf(SubCommand())

        val subLines = output.lines().filter { it.trim().startsWith("sub") || it.trim().startsWith("s ") }
        assertThat(subLines).containsExactly("  sub (alias: s) - A sub command")
    }

    @Test
    fun `a value-taking option shows its placeholder and a flag shows none`() {
        @Command(name = "sub", description = ["A sub command"])
        class SubCommand : Runnable {
            @CommandLine.Option(names = ["--count"], paramLabel = "<count>", description = ["How many"])
            var count: Int = 1

            @CommandLine.Option(names = ["--verbose"], description = ["Enable verbose output"])
            var verbose: Boolean = false

            override fun run() {
                // no-op for test
            }
        }

        val output = treeOf(SubCommand())

        assertThat(output).contains("--count=<count> - How many")
        assertThat(output).contains("    --verbose - Enable verbose output")
    }

    private fun treeOf(sub: Any): String {
        val parentCmd = CommandLine(TestRootCommand())
        parentCmd.addSubcommand(CommandLine(sub))
        val commandsCmd = Commands()
        val commandsCmdLine = CommandLine(commandsCmd)
        parentCmd.addSubcommand("commands", commandsCmdLine)
        commandsCmd.spec = commandsCmdLine.commandSpec
        commandsCmd.execute()
        return stdout.toString()
    }
}
