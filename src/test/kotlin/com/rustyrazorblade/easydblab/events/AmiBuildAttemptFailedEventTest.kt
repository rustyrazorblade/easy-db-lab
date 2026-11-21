package com.rustyrazorblade.easydblab.events

import com.rustyrazorblade.easydblab.events.Event.Ami.BuilderState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Tests that [Event.Ami.BuildAttemptFailed] tells the user why no retry follows, what became of the
 * build instances (with the command to clear one that may still be running), and where the log is;
 * and that it is an error only when no attempt follows.
 */
class AmiBuildAttemptFailedEventTest {
    @ParameterizedTest(name = "dropped={0} attempt={1} willRetry={2}")
    @CsvSource(
        delimiter = '|',
        value = [
            "true  | 1 | true  | Running it again on a new build instance.",
            "true  | 1 | false | Not retried while its build instance may still be running.",
            "true  | 3 | false | No attempts left.",
            "false | 1 | false | Not retried: the build itself failed (only a lost SSH session is retried).",
        ],
    )
    fun `the next step gives the real reason`(
        sessionDropped: Boolean,
        attempt: Int,
        willRetry: Boolean,
        expectedNext: String,
    ) {
        val lines = event(sessionDropped, attempt, willRetry).toDisplayString().lines()

        assertThat(lines.first()).startsWith("AMI build base.pkr.hcl attempt $attempt of 3 ").endsWith(": EOF")
        assertThat(lines.last()).isEqualTo("$expectedNext Packer log: /logs/packer.log")
    }

    /** One builder outcome and the line that must report it. */
    data class BuilderCase(
        val name: String,
        val event: Event.Ami.BuildAttemptFailed,
        val expected: String,
    ) {
        override fun toString(): String = name
    }

    companion object {
        private const val LOOK_FOR =
            "Look for instances tagged easy_db_lab_build_id=0123456789ab in the easy-db-lab-packer VPC in eu-west-1 and terminate them."
        private const val TERMINATE = "aws ec2 terminate-instances --region eu-west-1 --instance-ids i-1 i-2"
        private val two = listOf("i-1", "i-2")

        private fun event(
            sessionDropped: Boolean = true,
            attempt: Int = 1,
            willRetry: Boolean = false,
            builderState: BuilderState = BuilderState.TERMINATED,
            builderInstanceIds: List<String> = listOf("i-1"),
            builderError: String? = null,
            keepOnError: Boolean = false,
            logPath: String? = "/logs/packer.log",
        ) = Event.Ami.BuildAttemptFailed(
            template = "base.pkr.hcl",
            buildId = "0123456789ab",
            region = "eu-west-1",
            attempt = attempt,
            maxAttempts = 3,
            sessionDropped = sessionDropped,
            willRetry = willRetry,
            reason = "EOF",
            logPath = logPath,
            builderState = builderState,
            builderInstanceIds = builderInstanceIds,
            builderError = builderError,
            keepOnError = keepOnError,
        )

        @JvmStatic
        fun builderCases(): Stream<BuilderCase> =
            Stream.of(
                BuilderCase(
                    "none found",
                    event(builderState = BuilderState.NOT_FOUND, builderInstanceIds = emptyList()),
                    "No build instance of this build was found.",
                ),
                BuilderCase(
                    "terminated on the final failure",
                    event(builderInstanceIds = two),
                    "Build instance i-1 i-2 terminated (set --keep-on-error to keep it for debugging).",
                ),
                BuilderCase("terminated before a retry", event(willRetry = true), "Build instance i-1 terminated."),
                BuilderCase(
                    "terminated before a retry under keep-on-error",
                    event(willRetry = true, keepOnError = true),
                    "Build instance i-1 terminated so the build can run again; --keep-on-error keeps only the last failed attempt's instance.",
                ),
                BuilderCase(
                    "kept",
                    event(builderState = BuilderState.KEPT, builderInstanceIds = two, keepOnError = true),
                    "Build instance i-1 i-2 left running (--keep-on-error). Terminate it when done: $TERMINATE",
                ),
                BuilderCase(
                    "lookup failed",
                    event(builderState = BuilderState.LOOKUP_FAILED, builderInstanceIds = emptyList(), builderError = "throttled"),
                    "This build's instances could not be listed (throttled). $LOOK_FOR",
                ),
                BuilderCase(
                    "terminate failed",
                    event(builderState = BuilderState.TERMINATE_FAILED, builderInstanceIds = two, builderError = "denied"),
                    "Build instance i-1 i-2 could not be terminated (denied). Terminate it: $TERMINATE",
                ),
                BuilderCase(
                    "cleanup failed",
                    event(builderState = BuilderState.CLEANUP_FAILED, builderInstanceIds = emptyList(), builderError = "boom"),
                    "Cleaning up this build's instances failed (boom). $LOOK_FOR",
                ),
            )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("builderCases")
    fun `the builder line says what became of the build instances`(case: BuilderCase) {
        assertThat(case.event.toDisplayString().lines()[1]).isEqualTo(case.expected)
    }

    @Test
    fun `an attempt that wrote no log says so`() {
        assertThat(event(logPath = null).toDisplayString().lines().last()).endsWith("No Packer log was written.")
    }

    @ParameterizedTest(name = "willRetry={0}")
    @CsvSource("true, false", "false, true")
    fun `a failure is an error only when no attempt follows`(
        willRetry: Boolean,
        isError: Boolean,
    ) {
        assertThat(event(willRetry = willRetry).isError()).isEqualTo(isError)
    }
}
