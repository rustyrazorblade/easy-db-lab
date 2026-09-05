package com.rustyrazorblade.easydblab.commands.profile

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.EasyDBLabCommand
import com.rustyrazorblade.easydblab.di.KoinCommandFactory
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Lifecycle-level guards for the `profile` command group.
 *
 * `ProfileShow.buildReport` is a pure function of three plain values, so it cannot observe the
 * shape of the picocli tree or the annotations a command carries. Everything asserted here is
 * invisible at that surface and would stay green through exactly the regressions it guards
 * against.
 */
class ProfileCommandGroupTest : BaseKoinTest() {
    private fun rootCommandLine() = CommandLine(EasyDBLabCommand::class.java, KoinCommandFactory())

    @Test
    fun `profile group exposes show and setup`() {
        val profileGroup = rootCommandLine().subcommands["profile"]

        assertThat(profileGroup).isNotNull
        assertThat(profileGroup!!.subcommands.keys).contains("show", "setup")
    }

    @Test
    fun `former top-level setup names no longer resolve`() {
        val topLevel = rootCommandLine().subcommands.keys

        assertThat(topLevel).contains("profile")
        assertThat(topLevel).doesNotContain("setup", "setup-profile")
    }

    @Test
    fun `bare profile prints usage listing its subcommands and exits zero`() {
        val commandLine = rootCommandLine()
        val output = StringWriter()
        commandLine.out = PrintWriter(output)

        val exitCode = commandLine.execute("profile")

        assertThat(exitCode).isZero()
        assertThat(output.toString()).contains("show")
        assertThat(output.toString()).contains("setup")
    }

    @Test
    fun `profile group is not a PicoCommand so the executor never routes it`() {
        // CommandLineParser's execution strategy routes a matched command through CommandExecutor
        // only when it is a PicoCommand; anything else falls through to RunLast, which is what
        // makes a bare `profile` print usage and exit 0 instead of running a command lifecycle.
        val profileGroup = rootCommandLine().subcommands.getValue("profile")

        assertThat(profileGroup.commandSpec.userObject()).isNotInstanceOf(PicoCommand::class.java)
        assertThat(profileGroup.commandSpec.userObject()).isInstanceOf(Runnable::class.java)
    }
}
