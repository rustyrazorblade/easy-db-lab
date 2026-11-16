package com.rustyrazorblade.easydblab

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * VictoriaMetrics and VictoriaLogs are gone: Mimir and Loki replaced them. A leftover reference is a
 * config, command, dashboard or script that still talks to a backend that no longer exists, or a
 * comment, doc page or agent skill that misdirects the next reader or agent, including one that
 * sends a query to their old ports, 8428 and 9428. The dated design records under `docs/plans/`
 * describe the tool as it was when they were written and are left as they are.
 */
class NoVictoriaReferencesTest {
    private val victoria = Regex("victoria[ _-]?(metrics|logs)|:(8428|9428)\\b", RegexOption.IGNORE_CASE)

    private val roots =
        listOf(
            "src/main",
            "dashboards",
            "packer",
            "bin",
            "docs",
            "test-plans",
            ".claude/agents",
            ".claude/commands",
            ".claude/skills",
        )

    /** Dated design records: history, not a description of the current tool. */
    private val historical = "docs/plans/"

    @Test
    fun `nothing in the source tree names VictoriaMetrics or VictoriaLogs `() {
        val files =
            roots.flatMap { root -> File(root).walkTopDown().filter { it.isFile }.toList() } + File("build.gradle.kts") + File("CLAUDE.md")
        assertThat(files).isNotEmpty()

        val references =
            files
                .filterNot { it.invariantSeparatorsPath.startsWith(historical) }
                .flatMap { file ->
                    runCatching { file.readLines() }
                        .getOrDefault(emptyList())
                        .withIndex()
                        .filter { (_, line) -> victoria.containsMatchIn(line) }
                        .map { (index, line) -> "${file.invariantSeparatorsPath}:${index + 1}: ${line.trim().take(MAX_SHOWN)}" }
                }
        assertThat(references).isEmpty()
    }

    private companion object {
        const val MAX_SHOWN = 120
    }
}
