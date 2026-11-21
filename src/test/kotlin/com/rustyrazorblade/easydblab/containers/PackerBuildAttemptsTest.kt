package com.rustyrazorblade.easydblab.containers

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.time.Duration
import java.util.stream.Stream

/**
 * Tests for [PackerBuildAttempts]: a build that loses its SSH session runs again up to the attempt
 * limit once its builder is gone, any other failure ends the build at once, a builder that may
 * still be running stops the retry, and every failure is reported after its cleanup with whether
 * another attempt follows.
 */
class PackerBuildAttemptsTest {
    companion object {
        private val log = File("packer.log")
        private val dropped = PackerBuildOutcome.Failed(PackerFailure.SessionDropped("EOF"), log)
        private val scriptFailed = PackerBuildOutcome.Failed(PackerFailure.BuildError("Script exited with non-zero exit status: 1"), log)
        private val terminated = BuilderCleanup.Terminated
        private val notFound = BuilderCleanup.NotFound
        private val lookupFailed = BuilderCleanup.LookupFailed("throttled")
        private val terminateFailed = BuilderCleanup.TerminateFailed(listOf("i-1", "i-2"), "denied")

        /**
         * Each row: the outcomes the attempts return, the cleanup of each failed attempt, the
         * expected result, and per failure the `retryWanted` cleanup was given and the reported
         * `willRetry`.
         */
        @JvmStatic
        fun sequences(): Stream<Case> =
            Stream.of(
                row("first try succeeds", 3, listOf(PackerBuildOutcome.Succeeded), emptyList(), PackerBuildOutcome.Succeeded),
                row(
                    "a drop then success",
                    3,
                    listOf(dropped, PackerBuildOutcome.Succeeded),
                    listOf(terminated),
                    PackerBuildOutcome.Succeeded,
                    listOf(true to true),
                ),
                row(
                    "a drop with no builder found then success",
                    3,
                    listOf(dropped, PackerBuildOutcome.Succeeded),
                    listOf(notFound),
                    PackerBuildOutcome.Succeeded,
                    listOf(true to true),
                ),
                row(
                    "two drops then success",
                    3,
                    listOf(dropped, dropped, PackerBuildOutcome.Succeeded),
                    listOf(terminated, terminated),
                    PackerBuildOutcome.Succeeded,
                    listOf(true to true, true to true),
                ),
                row(
                    "every attempt drops",
                    3,
                    listOf(dropped, dropped, dropped),
                    listOf(terminated, terminated, terminated),
                    dropped,
                    listOf(true to true, true to true, false to false),
                ),
                row(
                    "a script failure is never retried",
                    3,
                    listOf(scriptFailed),
                    listOf(terminated),
                    scriptFailed,
                    listOf(false to false),
                ),
                row(
                    "a script failure after a drop ends the build",
                    3,
                    listOf(dropped, scriptFailed),
                    listOf(terminated, terminated),
                    scriptFailed,
                    listOf(true to true, false to false),
                ),
                row("one attempt allowed", 1, listOf(dropped), listOf(terminated), dropped, listOf(false to false)),
            )

        /** Rows where the failed builder may still be running, which stops the retry. */
        @JvmStatic
        fun unresolvedCleanups(): Stream<Case> =
            Stream.of(
                row(
                    "a failed builder lookup stops the retry",
                    3,
                    listOf(dropped),
                    listOf(lookupFailed),
                    dropped,
                    listOf(true to false),
                ),
                row(
                    "a failed terminate stops the retry",
                    3,
                    listOf(dropped),
                    listOf(terminateFailed),
                    dropped,
                    listOf(true to false),
                ),
                row(
                    "a failed terminate after a good retry stops the next",
                    3,
                    listOf(dropped, dropped),
                    listOf(terminated, terminateFailed),
                    dropped,
                    listOf(true to true, true to false),
                ),
            )

        private fun row(
            name: String,
            maxAttempts: Int,
            outcomes: List<PackerBuildOutcome>,
            cleanups: List<BuilderCleanup>,
            expected: PackerBuildOutcome,
            retryWantedAndWillRetry: List<Pair<Boolean, Boolean>> = emptyList(),
        ): Case = Case(name, maxAttempts, outcomes, cleanups, expected, retryWantedAndWillRetry)
    }

    /** One attempt sequence and what [PackerBuildAttempts.run] must make of it. */
    data class Case(
        val name: String,
        val maxAttempts: Int,
        val outcomes: List<PackerBuildOutcome>,
        val cleanups: List<BuilderCleanup>,
        val expected: PackerBuildOutcome,
        val retryWantedAndWillRetry: List<Pair<Boolean, Boolean>>,
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
    @MethodSource("sequences", "unresolvedCleanups")
    fun `a build runs again only after a session drop whose builder is gone, within the limit`(case: Case) {
        val remaining = ArrayDeque(case.outcomes)
        val cleanupResults = ArrayDeque(case.cleanups)
        val retryWanted = mutableListOf<Boolean>()
        val reported = mutableListOf<Reported>()

        val result =
            PackerBuildAttempts(case.maxAttempts, Duration.ZERO).run(
                build = { remaining.removeFirst() },
                cleanUp = { _, _, wanted ->
                    retryWanted.add(wanted)
                    cleanupResults.removeFirst()
                },
                report = { attempt, _, willRetry, cleanup -> reported.add(Reported(attempt, willRetry, cleanup)) },
            )

        assertThat(result).`as`(case.name).isEqualTo(case.expected)
        assertThat(remaining).`as`("builds left unrun").isEmpty()
        assertThat(retryWanted).`as`("retryWanted per cleanup").isEqualTo(case.retryWantedAndWillRetry.map { it.first })
        assertThat(reported.map { it.willRetry }).`as`("willRetry per failure").isEqualTo(case.retryWantedAndWillRetry.map { it.second })
        assertThat(reported.map { it.cleanup }).`as`("reported cleanups").isEqualTo(case.cleanups)
        assertThat(reported.map { it.attempt }).`as`("attempt numbers").isEqualTo((1..reported.size).toList())
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["the attempt", "its cleanup", "its report"])
    fun `a throw ends the build without another attempt`(thrower: String) {
        var attempts = 0
        val attemptsRunner = PackerBuildAttempts(3, Duration.ZERO)

        assertThatThrownBy {
            attemptsRunner.run(
                build = {
                    attempts++
                    if (thrower == "the attempt") error("boom") else dropped
                },
                cleanUp = { _, _, _ -> if (thrower == "its cleanup") error("boom") else BuilderCleanup.Terminated },
                report = { _, _, _, _ -> if (thrower == "its report") error("boom") },
            )
        }.hasMessage("boom")
        assertThat(attempts).`as`(thrower).isEqualTo(1)
    }
}
