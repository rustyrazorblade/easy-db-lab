package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.DefaultHelpTopicService
import com.rustyrazorblade.easydblab.services.HelpTopicService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Tests for the [Help] command across its three paths: no-argument listing, known-topic body,
 * and unknown-topic clean error with a non-zero exit.
 *
 * The command discovers topics through a real [DefaultHelpTopicService] pointed at a test fixture
 * package, and the process exit is captured through the injectable exit seam so an unknown topic
 * does not terminate the test JVM.
 */
class HelpTest : BaseKoinTest() {
    private val stdout = ByteArrayOutputStream()
    private val originalOut = System.out
    private var exitCode: Int? = null

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<HelpTopicService> {
                    DefaultHelpTopicService(resourcePackage = "com.rustyrazorblade.easydblab.helptopictest")
                }
            },
        )

    @BeforeEach
    fun captureStdout() {
        System.setOut(PrintStream(stdout))
    }

    @AfterEach
    fun restoreStdout() {
        System.setOut(originalOut)
    }

    private fun command(): Help = Help(exit = { exitCode = it })

    private fun output(): String = stdout.toString()

    @Test
    fun `no argument lists every topic with its description and exits zero`() {
        val cmd = command()

        cmd.execute()

        val out = output()
        val lines = out.lines()
        // The usage header is flush-left, not ragged from a bad trimIndent common-indent calc.
        assertThat(lines).contains("Usage: easy-db-lab help <topic>")
        // Each topic renders as exactly "  <name>\t<description>", consistently indented.
        assertThat(lines).contains("  alpha\tThe alpha test topic")
        assertThat(lines).contains("  beta\tThe beta test topic")
        assertThat(exitCode).isNull()
    }

    @Test
    fun `known topic prints its body and exits zero`() {
        val cmd = command()
        cmd.topic = "alpha"

        cmd.execute()

        assertThat(output()).contains("How to do the alpha operation")
        assertThat(exitCode).isNull()
    }

    @Test
    fun `topic matching is case-insensitive`() {
        val cmd = command()
        cmd.topic = "AlPhA"

        cmd.execute()

        assertThat(output()).contains("How to do the alpha operation")
        assertThat(exitCode).isNull()
    }

    @Test
    fun `unknown topic names the bad topic, lists valid ones, and exits non-zero`() {
        val cmd = command()
        cmd.topic = "nonsense"

        cmd.execute()

        val out = output()
        assertThat(out).contains("nonsense")
        assertThat(out).contains("alpha", "beta")
        // No Java exception-class prefix leaks to the terminal.
        assertThat(out).doesNotContain("Exception")
        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
    }
}
