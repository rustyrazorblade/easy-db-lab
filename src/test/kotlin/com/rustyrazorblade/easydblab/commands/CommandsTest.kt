package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import picocli.CommandLine
import picocli.CommandLine.Command

@Command(name = "test-root", description = ["Test root command"])
class TestRootCommand : Runnable {
    override fun run() {
        // no-op for test
    }
}

class CommandsTest : BaseKoinTest() {
    private lateinit var outputHandler: BufferedOutputHandler

    @BeforeEach
    fun setup() {
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
    }

    @Test
    fun `execute outputs command tree`() {
        val parentCmd = CommandLine(TestRootCommand())

        val commandsCmd = Commands()
        val commandsCmdLine = CommandLine(commandsCmd)
        parentCmd.addSubcommand("commands", commandsCmdLine)

        commandsCmd.spec = commandsCmdLine.commandSpec

        commandsCmd.execute()

        val output = outputHandler.messages.joinToString("\n")
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

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("sub")
        assertThat(output).contains("--verbose")
    }
}
