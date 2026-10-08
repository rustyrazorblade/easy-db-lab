package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tests for [DefaultLocalCliRunner] against real processes: telling a missing binary apart from a
 * live one, and killing one that hangs. Preflight checks for local tools rest on these outcomes.
 */
class LocalCliRunnerTest {
    @Test
    fun `reports BinaryNotFound when the executable is not on PATH`() {
        val result = DefaultLocalCliRunner.run(listOf("easy-db-lab-no-such-binary"), Duration.ofSeconds(5))

        assertThat(result).isEqualTo(LocalCliResult.BinaryNotFound)
    }

    @Test
    fun `captures stdout and the exit code of a real process`() {
        val result = DefaultLocalCliRunner.run(listOf("echo", """{"BackendState":"Running"}"""), Duration.ofSeconds(5))

        assertThat(result).isInstanceOf(LocalCliResult.Completed::class.java)
        val completed = result as LocalCliResult.Completed
        assertThat(completed.exitCode).isZero()
        assertThat(completed.stdout.trim()).isEqualTo("""{"BackendState":"Running"}""")
    }

    @Test
    fun `keeps stderr apart from stdout`() {
        val result = DefaultLocalCliRunner.run(listOf("sh", "-c", "echo out; echo err >&2; exit 3"), Duration.ofSeconds(5))

        assertThat(result).isEqualTo(LocalCliResult.Completed(3, "out\n", "err\n"))
    }

    @Test
    fun `kills a process that outlives the timeout`() {
        val result = DefaultLocalCliRunner.run(listOf("sleep", "30"), Duration.ofMillis(200))

        assertThat(result).isEqualTo(LocalCliResult.TimedOut)
    }
}
