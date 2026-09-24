package com.rustyrazorblade.easydblab.providers.aws

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * [pollUntil] over [RetryUtil.createPollUntilRetryConfig]: polls at a fixed interval until the
 * result satisfies a condition, treats a thrown look as transient within the same budget, returns
 * the last result when the budget (attempts, or an optional wall-clock deadline) runs out, and
 * fails only when the last look throws. Runs with a zero interval so it does not sleep.
 */
class RetryUtilPollUntilTest {
    private val looks = AtomicInteger(0)

    private fun poll(
        maxAttempts: Int,
        results: List<() -> String>,
    ): String =
        pollUntil(
            operationName = "test-poll",
            maxAttempts = maxAttempts,
            interval = Duration.ZERO,
            done = { it == "ready" },
        ) { results[looks.getAndIncrement()]() }

    @Test
    fun `returns the first result that satisfies the condition and looks no further`() {
        val result = poll(maxAttempts = 5, results = listOf({ "pending" }, { "ready" }, { "never" }))

        assertThat(result).isEqualTo("ready")
        assertThat(looks.get()).isEqualTo(2)
    }

    @Test
    fun `returns the last result, without throwing, when the condition never holds`() {
        val result = poll(maxAttempts = 3, results = listOf({ "a" }, { "b" }, { "c" }, { "never" }))

        assertThat(result).isEqualTo("c")
        assertThat(looks.get()).isEqualTo(3)
    }

    @Test
    fun `a look that throws is retried within the budget`() {
        val result = poll(maxAttempts = 3, results = listOf({ error("connection reset") }, { "ready" }))

        assertThat(result).isEqualTo("ready")
        assertThat(looks.get()).isEqualTo(2)
    }

    @Test
    fun `fails with the last look's exception when the last look throws`() {
        assertThatThrownBy {
            poll(maxAttempts = 2, results = listOf({ "pending" }, { error("connection reset") }))
        }.isInstanceOf(IllegalStateException::class.java).hasMessage("connection reset")
        assertThat(looks.get()).isEqualTo(2)
    }

    /** A wall-clock deadline bounds the poll however many attempts remain: a slow look cannot stretch it. */
    @Test
    fun `a passed deadline ends the poll with the last result however many attempts remain`() {
        val result =
            pollUntil<String>(
                operationName = "test-poll",
                maxAttempts = Int.MAX_VALUE,
                interval = Duration.ZERO,
                deadline = Instant.now(),
                done = { it == "ready" },
            ) { listOf("pending", "ready")[looks.getAndIncrement()] }

        assertThat(result).isEqualTo("pending")
        assertThat(looks.get()).isEqualTo(1)
    }

    @Test
    fun `a look that throws after the deadline fails the poll`() {
        assertThatThrownBy {
            pollUntil<String>(
                operationName = "test-poll",
                maxAttempts = Int.MAX_VALUE,
                interval = Duration.ZERO,
                deadline = Instant.now(),
                done = { it == "ready" },
            ) {
                looks.incrementAndGet()
                error("connection reset")
            }
        }.isInstanceOf(IllegalStateException::class.java).hasMessage("connection reset")
        assertThat(looks.get()).isEqualTo(1)
    }
}
