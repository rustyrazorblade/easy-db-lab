package com.rustyrazorblade.easydblab.profiling

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [renderAsprofCommandLine] — what `profile status` shows an operator so they can see
 * exactly what the tool added to their arguments on their behalf.
 */
class AsprofCommandLineTest {
    @Test
    fun `renders the user's arguments first and the tool's plumbing after`() {
        val rendered =
            renderAsprofCommandLine(
                userArgs = listOf("-e", "wall", "-i", "10ms"),
                loopInterval = "30s",
                pid = 4242,
            )

        assertThat(rendered).startsWith("asprof start -e wall -i 10ms ")
        assertThat(rendered).contains("-o jfr")
        assertThat(rendered).contains("--loop 30s")
        assertThat(rendered).contains("cassandra-%p-%t.jfr")
        assertThat(rendered).endsWith(" 4242")
    }

    @Test
    fun `renders cleanly when the user supplied no arguments of their own`() {
        val rendered = renderAsprofCommandLine(userArgs = emptyList(), loopInterval = "1m", pid = 7)

        assertThat(rendered).startsWith("asprof start -o jfr")
        assertThat(rendered).doesNotContain("  ")
    }
}
