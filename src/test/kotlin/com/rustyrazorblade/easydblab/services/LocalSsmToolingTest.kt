package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tests for [DefaultLocalSsmTooling]: how each runner outcome maps to a [SsmToolFault]. How a real
 * process maps to those outcomes is covered by [LocalCliRunnerTest].
 */
class LocalSsmToolingTest {
    private val timeout = Duration.ofSeconds(1)

    private fun toolingWhere(results: Map<String, LocalCliResult>) =
        DefaultLocalSsmTooling(
            runner = { command, _ -> results.getValue(command.first()) },
            timeout = timeout,
        )

    @Test
    fun `reports nothing when both tools exit zero`() {
        val tooling =
            toolingWhere(
                mapOf(
                    "aws" to LocalCliResult.Completed(0, "aws-cli/2.17.0"),
                    "session-manager-plugin" to LocalCliResult.Completed(0, "1.2.650.0"),
                ),
            )

        assertThat(tooling.faults()).isEmpty()
    }

    @Test
    fun `a binary that is not on the PATH is reported as not found`() {
        val tooling =
            toolingWhere(
                mapOf(
                    "aws" to LocalCliResult.Completed(0, "aws-cli/2.17.0"),
                    "session-manager-plugin" to LocalCliResult.BinaryNotFound,
                ),
            )

        assertThat(tooling.faults()).containsExactly(SsmToolFault.NotFound(SsmTool.SessionManagerPlugin))
    }

    @Test
    fun `a tool that exits non-zero keeps its output, and one that hangs keeps the timeout`() {
        val tooling =
            toolingWhere(
                mapOf(
                    "aws" to LocalCliResult.Completed(1, "", "dyld: Library not loaded: libpython3.11.dylib"),
                    "session-manager-plugin" to LocalCliResult.TimedOut,
                ),
            )

        assertThat(tooling.faults()).containsExactly(
            SsmToolFault.Failed(SsmTool.AwsCli, 1, "dyld: Library not loaded: libpython3.11.dylib"),
            SsmToolFault.TimedOut(SsmTool.SessionManagerPlugin, timeout),
        )
    }
}
