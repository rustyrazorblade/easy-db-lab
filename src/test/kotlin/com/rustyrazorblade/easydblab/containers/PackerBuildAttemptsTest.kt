package com.rustyrazorblade.easydblab.containers

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.time.Duration
import java.util.stream.Stream

/**
 * Tests for [PackerBuildAttempts]: a build that loses its SSH session runs again up to the attempt
 * limit once its builder is gone, any other failure ends the build at once, a builder that may
 * still be running (or was kept) stops the retry, only the last failed attempt's builders are
 * inspected or kept, and every failure is reported after its cleanup with whether another attempt
 * follows.
 */
class PackerBuildAttemptsTest {
    companion object {
        private val log = File("packer.log")
        private val dropped = PackerBuildOutcome.Failed(PackerFailure.SessionDropped("EOF"), log)
        private val scriptFailed = PackerBuildOutcome.Failed(PackerFailure.BuildError("Script exited with non-zero exit status: 1"), log)
        private val terminated = BuilderCleanup.Terminated(listOf("i-1"))
        private val kept = BuilderCleanup.Kept(listOf("i-1"))
        private val notFound = BuilderCleanup.NotFound
        private val lookupFailed = BuilderCleanup.LookupFailed("throttled")
        private val terminateFailed = BuilderCleanup.TerminateFailed(listOf("i-1", "i-2"), "denied")

        /** Tear down without inspecting: the plan of an attempt that runs again. */
        private val retryPlan = CleanupPlan(keep = false, inspect = false)

        /** Inspect, then tear down: the last failed attempt without --keep-on-error. */
        private val finalPlan = CleanupPlan(keep = false, inspect = true)

        /** Inspect, then keep: the last failed attempt under --keep-on-error. */
        private val keepPlan = CleanupPlan(keep = true, inspect = true)

        /**
         * Each row: --keep-on-error, the attempt limit, the outcomes the attempts return, the
         * cleanup of each failed attempt, the expected result, and per failure the plan the cleanup
         * was given and the reported `willRetry`.
         */
        @JvmStatic
        fun sequences(): Stream<Case> =
            Stream.of(
                Case("first try succeeds", false, 3, listOf(PackerBuildOutcome.Succeeded), emptyList(), PackerBuildOutcome.Succeeded),
                Case(
                    "a drop then success",
                    false,
                    3,
                    listOf(dropped, PackerBuildOutcome.Succeeded),
                    listOf(terminated),
                    PackerBuildOutcome.Succeeded,
                    listOf(retryPlan to true),
                ),
                Case(
                    "a drop with no builder found then success",
                    false,
                    3,
                    listOf(dropped, PackerBuildOutcome.Succeeded),
                    listOf(notFound),
                    PackerBuildOutcome.Succeeded,
                    listOf(retryPlan to true),
                ),
                Case(
                    "every attempt drops",
                    false,
                    3,
                    listOf(dropped, dropped, dropped),
                    listOf(terminated, terminated, terminated),
                    dropped,
                    listOf(retryPlan to true, retryPlan to true, finalPlan to false),
                ),
                Case(
                    "a script failure is never retried",
                    false,
                    3,
                    listOf(scriptFailed),
                    listOf(terminated),
                    scriptFailed,
                    listOf(
                        finalPlan to false,
                    ),
                ),
                Case(
                    "a script failure after a drop ends the build",
                    false,
                    3,
                    listOf(dropped, scriptFailed),
                    listOf(terminated, terminated),
                    scriptFailed,
                    listOf(retryPlan to true, finalPlan to false),
                ),
                Case("one attempt allowed", false, 1, listOf(dropped), listOf(terminated), dropped, listOf(finalPlan to false)),
            )

        /** Rows under --keep-on-error, which keeps only the last failed attempt's builder. */
        @JvmStatic
        fun keepOnErrorSequences(): Stream<Case> =
            Stream.of(
                Case(
                    "under keep-on-error a drop is still torn down and retried",
                    true,
                    3,
                    listOf(dropped, PackerBuildOutcome.Succeeded),
                    listOf(terminated),
                    PackerBuildOutcome.Succeeded,
                    listOf(retryPlan to true),
                ),
                Case(
                    "under keep-on-error only the final failure is kept",
                    true,
                    3,
                    listOf(dropped, scriptFailed),
                    listOf(terminated, kept),
                    scriptFailed,
                    listOf(retryPlan to true, keepPlan to false),
                ),
                Case(
                    "under keep-on-error the last drop is kept",
                    true,
                    2,
                    listOf(dropped, dropped),
                    listOf(terminated, kept),
                    dropped,
                    listOf(retryPlan to true, keepPlan to false),
                ),
            )

        /** Rows where the failed builder may still be running, which stops the retry. */
        @JvmStatic
        fun unresolvedCleanups(): Stream<Case> =
            Stream.of(
                Case(
                    "a failed builder lookup stops the retry",
                    false,
                    3,
                    listOf(dropped),
                    listOf(lookupFailed),
                    dropped,
                    listOf(
                        retryPlan to false,
                    ),
                ),
                Case(
                    "a failed terminate stops the retry",
                    false,
                    3,
                    listOf(dropped),
                    listOf(terminateFailed),
                    dropped,
                    listOf(
                        retryPlan to false,
                    ),
                ),
                Case(
                    "a failed terminate after a good retry stops the next",
                    false,
                    3,
                    listOf(dropped, dropped),
                    listOf(terminated, terminateFailed),
                    dropped,
                    listOf(retryPlan to true, retryPlan to false),
                ),
                Case(
                    "a builder kept while a retry is wanted stops the retry",
                    true,
                    3,
                    listOf(dropped),
                    listOf(kept),
                    dropped,
                    listOf(retryPlan to false),
                ),
            )
    }

    /** One attempt sequence and what [PackerBuildAttempts.run] must make of it. */
    data class Case(
        val name: String,
        val keepOnError: Boolean,
        val maxAttempts: Int,
        val outcomes: List<PackerBuildOutcome>,
        val cleanups: List<BuilderCleanup>,
        val expected: PackerBuildOutcome,
        val planAndWillRetry: List<Pair<CleanupPlan, Boolean>> = emptyList(),
    ) {
        override fun toString(): String = name
    }

    /** One failed attempt as [PackerBuildAttempts.run] reported it. */
    private data class Reported(
        val attempt: Int,
        val willRetry: Boolean,
        val cleanup: BuilderCleanup,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("sequences", "keepOnErrorSequences", "unresolvedCleanups")
    fun `a build runs again only after a session drop whose builder is gone, within the limit`(case: Case) {
        val remaining = ArrayDeque(case.outcomes)
        val cleanupResults = ArrayDeque(case.cleanups)
        val plans = mutableListOf<CleanupPlan>()
        val reported = mutableListOf<Reported>()

        val result =
            PackerBuildAttempts(case.keepOnError, case.maxAttempts, Duration.ZERO).run(
                build = { remaining.removeFirst() },
                cleanUp = { _, _, plan ->
                    plans.add(plan)
                    cleanupResults.removeFirst()
                },
                report = { attempt, _, willRetry, cleanup -> reported.add(Reported(attempt, willRetry, cleanup)) },
            )

        assertThat(result).`as`(case.name).isEqualTo(case.expected)
        assertThat(remaining).`as`("builds left unrun").isEmpty()
        assertThat(plans).`as`("plan per cleanup").isEqualTo(case.planAndWillRetry.map { it.first })
        assertThat(reported.map { it.willRetry }).`as`("willRetry per failure").isEqualTo(case.planAndWillRetry.map { it.second })
        assertThat(reported.map { it.cleanup }).`as`("reported cleanups").isEqualTo(case.cleanups)
        assertThat(reported.map { it.attempt }).`as`("attempt numbers").isEqualTo((1..reported.size).toList())
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["the attempt", "its report"])
    fun `a throw ends the build without another attempt`(thrower: String) {
        var attempts = 0
        val attemptsRunner = PackerBuildAttempts(keepOnError = false, maxAttempts = 3, delay = Duration.ZERO)

        assertThatThrownBy {
            attemptsRunner.run(
                build = {
                    attempts++
                    if (thrower == "the attempt") error("boom") else dropped
                },
                cleanUp = { _, _, _ -> terminated },
                report = { _, _, _, _ -> if (thrower == "its report") error("boom") },
            )
        }.hasMessage("boom")
        assertThat(attempts).`as`(thrower).isEqualTo(1)
    }

    @Test
    fun `a cleanup that throws is still reported, and stops the retry`() {
        var attempts = 0
        val reported = mutableListOf<Reported>()

        val result =
            PackerBuildAttempts(keepOnError = false, maxAttempts = 3, delay = Duration.ZERO).run(
                build = {
                    attempts++
                    dropped
                },
                cleanUp = { _, _, _ -> error("boom") },
                report = { attempt, _, willRetry, cleanup -> reported.add(Reported(attempt, willRetry, cleanup)) },
            )

        assertThat(result).isEqualTo(dropped)
        assertThat(attempts).isEqualTo(1)
        assertThat(reported).containsExactly(Reported(1, false, BuilderCleanup.CleanupFailed("boom")))
    }
}
