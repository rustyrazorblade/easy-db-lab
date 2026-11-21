package com.rustyrazorblade.easydblab.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Tests that [Event.Ami.BuildAttemptFailed] tells the user what follows a failed attempt: a retry,
 * no retry because its builder may still be running, no attempts left, or no retry for a failure
 * that is not a lost session.
 */
class AmiBuildAttemptFailedEventTest {
    @ParameterizedTest(name = "dropped={0} willRetry={1} notCleared={2}")
    @CsvSource(
        delimiter = '|',
        nullValues = ["null"],
        value = [
            "true  | true  | null           | Running it again on a new build instance.",
            "true  | false | i-1 still up   | Not retried: i-1 still up.",
            "false | false | i-1 still up   | Not retried: i-1 still up.",
            "true  | false | null           | No attempts left.",
            "false | false | null           | Not retried: only a lost session is.",
        ],
    )
    fun `the next step follows the attempt's outcome and its builder's cleanup`(
        sessionDropped: Boolean,
        willRetry: Boolean,
        builderNotCleared: String?,
        expectedNext: String,
    ) {
        val event =
            Event.Ami.BuildAttemptFailed(
                template = "base.pkr.hcl",
                attempt = 1,
                maxAttempts = 3,
                sessionDropped = sessionDropped,
                willRetry = willRetry,
                reason = "EOF",
                logPath = "/logs/packer.log",
                builderNotCleared = builderNotCleared,
            )

        assertThat(event.toDisplayString().lines().last()).isEqualTo("$expectedNext Packer log: /logs/packer.log")
    }
}
