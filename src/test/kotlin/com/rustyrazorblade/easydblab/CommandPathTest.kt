package com.rustyrazorblade.easydblab

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import picocli.CommandLine
import picocli.CommandLine.Command

/** The command path a capability message names: what the user typed, without the root. */
class CommandPathTest {
    @Command(name = "use")
    class Use : Runnable {
        @CommandLine.Parameters(index = "0")
        var version = ""

        override fun run() = Unit
    }

    @Command(name = "cassandra", subcommands = [Use::class])
    class Group : Runnable {
        override fun run() = Unit
    }

    @Command(name = "easy-db-lab", subcommands = [Group::class])
    class Root : Runnable {
        override fun run() = Unit
    }

    @Test
    fun `a nested command is named by its group and its own name`() {
        var deepest = CommandLine(Root()).parseArgs("cassandra", "use", "5.0")
        while (deepest.hasSubcommand()) deepest = deepest.subcommand()

        assertThat(deepest.commandPath()).isEqualTo("cassandra use")
    }
}
