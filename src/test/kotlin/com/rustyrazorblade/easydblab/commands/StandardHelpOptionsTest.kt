package com.rustyrazorblade.easydblab.commands

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Parses with real picocli to prove [StandardHelpOptions] keeps `--help` working on a command
 * that owns a `--version` option, and keeps the command's own value binding intact.
 */
class StandardHelpOptionsTest {
    @Command(name = "owns-version")
    class OwnsVersion {
        @Option(names = ["--version"])
        var version = "current"
    }

    @Command(name = "plain")
    class Plain {
        @Option(names = ["--name"])
        var name = ""
    }

    private fun parse(
        command: Any,
        vararg args: String,
    ): CommandLine.ParseResult {
        val commandLine = CommandLine(command)
        StandardHelpOptions.apply(commandLine.commandSpec)
        return commandLine.parseArgs(*args)
    }

    @Test
    fun `help is recognised on a command that declares its own version option`() {
        assertThat(parse(OwnsVersion(), "--help").isUsageHelpRequested).isTrue()
        assertThat(parse(OwnsVersion(), "-h").isUsageHelpRequested).isTrue()
    }

    @Test
    fun `the command's own version option still binds its value`() {
        val command = OwnsVersion()

        val result = parse(command, "--version", "5.0")

        assertThat(command.version).isEqualTo("5.0")
        assertThat(result.isUsageHelpRequested).isFalse()
        assertThat(result.isVersionHelpRequested).isFalse()
    }

    @Test
    fun `a command without its own version option gets help and version help`() {
        assertThat(parse(Plain(), "--help").isUsageHelpRequested).isTrue()
        assertThat(parse(Plain(), "--version").isVersionHelpRequested).isTrue()
    }
}
