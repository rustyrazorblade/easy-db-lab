package com.rustyrazorblade.easydblab.containers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.time.Duration
import java.util.stream.Stream

/**
 * Tests for [PackerBuildAttempts]: a build that loses its SSH session runs again up to the attempt
 * limit, any other failure ends the build at once, and every failure is reported with whether
 * another attempt follows.
 */
class PackerBuildAttemptsTest {
    companion object {
        private val log = File("packer.log")
        private val dropped = PackerBuildOutcome.Failed(PackerFailure.SessionDropped("EOF"), log)
        private val scriptFailed = PackerBuildOutcome.Failed(PackerFailure.BuildError("Script exited with non-zero exit status: 1"), log)

        @JvmStatic
        fun sequences(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    "first try succeeds",
                    3,
                    listOf(PackerBuildOutcome.Succeeded),
                    PackerBuildOutcome.Succeeded,
                    emptyList<Boolean>(),
                ),
                Arguments.of(
                    "a drop then success",
                    3,
                    listOf(dropped, PackerBuildOutcome.Succeeded),
                    PackerBuildOutcome.Succeeded,
                    listOf(true),
                ),
                Arguments.of(
                    "two drops then success",
                    3,
                    listOf(dropped, dropped, PackerBuildOutcome.Succeeded),
                    PackerBuildOutcome.Succeeded,
                    listOf(true, true),
                ),
                Arguments.of("every attempt drops", 3, listOf(dropped, dropped, dropped), dropped, listOf(true, true, false)),
                Arguments.of("a script failure is never retried", 3, listOf(scriptFailed), scriptFailed, listOf(false)),
                Arguments.of(
                    "a script failure after a drop ends the build",
                    3,
                    listOf(dropped, scriptFailed),
                    scriptFailed,
                    listOf(true, false),
                ),
                Arguments.of("one attempt allowed", 1, listOf(dropped), dropped, listOf(false)),
            )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sequences")
    fun `a build runs again only after a session drop, within the limit`(
        case: String,
        maxAttempts: Int,
        outcomes: List<PackerBuildOutcome>,
        expected: PackerBuildOutcome,
        expectedWillRetry: List<Boolean>,
    ) {
        val remaining = ArrayDeque(outcomes)
        val failures = mutableListOf<Triple<Int, PackerBuildOutcome.Failed, Boolean>>()

        val result =
            PackerBuildAttempts(maxAttempts, Duration.ZERO).run(
                build = { remaining.removeFirst() },
                afterFailure = { attempt, failed, willRetry -> failures.add(Triple(attempt, failed, willRetry)) },
            )

        assertThat(result).`as`(case).isEqualTo(expected)
        assertThat(remaining).`as`("builds left unrun").isEmpty()
        assertThat(failures.map { it.third }).`as`("willRetry per failure").isEqualTo(expectedWillRetry)
        assertThat(failures.map { it.first }).`as`("attempt numbers").isEqualTo((1..failures.size).toList())
    }
}
