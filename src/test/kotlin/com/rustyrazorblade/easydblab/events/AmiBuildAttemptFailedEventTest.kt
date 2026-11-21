package com.rustyrazorblade.easydblab.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Tests that [Event.Ami.BuildAttemptFailed] tells the user why no retry follows, and separately
 * that a builder may still be running: a stranded builder is never given as the reason when the
 * failure was not retryable anyway.
 */
class AmiBuildAttemptFailedEventTest {
    @ParameterizedTest(name = "dropped={0} attempt={1} willRetry={2} notCleared={3}")
    @CsvSource(
        delimiter = '|',
        nullValues = ["null"],
        value = [
            "true  | 1 | true  | null         | Running it again on a new build instance.",
            "true  | 1 | false | i-1 is stuck | Not retried while its build instance may still be running.",
            "true  | 3 | false | i-1 is stuck | No attempts left.",
            "true  | 3 | false | null         | No attempts left.",
            "false | 1 | false | i-1 is stuck | Not retried: only a lost session is.",
            "false | 1 | false | null         | Not retried: only a lost session is.",
        ],
    )
    fun `the next step gives the real reason, and a stranded builder is its own line`(
        sessionDropped: Boolean,
        attempt: Int,
        willRetry: Boolean,
        builderNotCleared: String?,
        expectedNext: String,
    ) {
        val lines =
            Event.Ami
                .BuildAttemptFailed(
                    template = "base.pkr.hcl",
                    attempt = attempt,
                    maxAttempts = 3,
                    sessionDropped = sessionDropped,
                    willRetry = willRetry,
                    reason = "EOF",
                    logPath = "/logs/packer.log",
                    builderNotCleared = builderNotCleared,
                ).toDisplayString()
                .lines()

        assertThat(lines.last()).isEqualTo("$expectedNext Packer log: /logs/packer.log")
        val strandedLine = builderNotCleared?.let { "The build instance may still be running: $it." }
        assertThat(lines.drop(1).dropLast(1)).isEqualTo(listOfNotNull(strandedLine))
    }
}
