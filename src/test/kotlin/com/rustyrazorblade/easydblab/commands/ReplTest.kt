package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.di.KoinCommandFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import picocli.CommandLine
import picocli.shell.jline3.PicocliCommands

class ReplTest : BaseKoinTest() {
    /**
     * Factory that chains multiple PicoCLI factories together for testing.
     */
    private class TestChainedFactory(
        private vararg val factories: CommandLine.IFactory,
    ) : CommandLine.IFactory {
        override fun <K : Any> create(cls: Class<K>): K {
            for (factory in factories) {
                try {
                    return factory.create(cls)
                } catch (e: Exception) {
                    // Try next factory
                }
            }
            return CommandLine.defaultFactory().create(cls)
        }
    }

    @Test
    fun `PicocliCommands should wrap command hierarchy`() {
        val cmd = CommandLine(ShellCommands::class.java, KoinCommandFactory())
        val picocliCommands = PicocliCommands(cmd)

        // Verify the command registry has been created
        assertThat(picocliCommands.name()).isEqualTo("PicocliCommands")
    }

    @Test
    fun `PicocliCommands should provide command info for help`() {
        val cmd = CommandLine(ShellCommands::class.java, KoinCommandFactory())
        val picocliCommands = PicocliCommands(cmd)

        // Verify command info is available - commandInfo returns map of command descriptions
        val commandInfo = picocliCommands.commandInfo("status")
        assertThat(commandInfo).isNotNull
    }

    @Test
    fun `shell should include ClearScreen command`() {
        val picocliFactory = PicocliCommands.PicocliCommandsFactory()
        val koinFactory = KoinCommandFactory()
        val chainedFactory = TestChainedFactory(picocliFactory, koinFactory)
        val cmd = CommandLine(ShellCommands::class.java, chainedFactory)

        val subcommands = cmd.subcommands
        assertThat(subcommands).containsKey("cls")
    }

    @Test
    fun `command hierarchy should be complete for completion`() {
        val cmd = CommandLine(ShellCommands::class.java, KoinCommandFactory())

        // Verify top-level commands exist
        val subcommands = cmd.subcommands.keys
        assertThat(subcommands).contains("status", "cassandra", "spark", "clickhouse")

        // Verify nested commands exist
        val cassandraCmd = cmd.subcommands["cassandra"]
        assertThat(cassandraCmd).isNotNull
        val cassandraSubcommands = cassandraCmd!!.subcommands.keys
        assertThat(cassandraSubcommands).contains("start", "stop", "restart", "stress")

        // Verify deeply nested commands exist
        val stressCmd = cassandraCmd.subcommands["stress"]
        assertThat(stressCmd).isNotNull
        val stressSubcommands = stressCmd!!.subcommands.keys
        assertThat(stressSubcommands).contains("start", "stop", "status")
    }

    @Test
    fun `ShellCommands includes all expected commands`() {
        val cmd = CommandLine(ShellCommands::class.java, KoinCommandFactory())
        val subcommands = cmd.subcommands.keys

        // Verify key commands are present
        assertThat(subcommands).containsAll(
            listOf(
                "version",
                "clean",
                "ip",
                "hosts",
                "status",
                "init",
                "up",
                "cassandra",
                "spark",
                "clickhouse",
                "opensearch",
                "aws",
            ),
        )
    }
}
