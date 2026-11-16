package com.rustyrazorblade.easydblab.providers.aws

import io.github.resilience4j.retry.Retry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.IOException
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

    /**
     * A look over HTTP fails with OkHttp's checked IOException when the SOCKS tunnel drops for a
     * moment. resilience4j's plain decorator never sees a checked exception, so this is the case
     * that proves the poll is decorated to retry it.
     */
    @Test
    fun `a look that throws a checked IOException is retried within the budget`() {
        val result = poll(maxAttempts = 3, results = listOf({ throw IOException("connection reset") }, { "ready" }))

        assertThat(result).isEqualTo("ready")
        assertThat(looks.get()).isEqualTo(2)
    }

    /** An interrupt asks the thread to stop, so it ends the poll at once and stays visible to the caller. */
    @Test
    fun `an interrupted look fails at once and keeps the interrupt`() {
        try {
            assertThatThrownBy {
                poll(maxAttempts = 5, results = listOf({ throw InterruptedException("stop") }, { "ready" }))
            }.isInstanceOf(InterruptedException::class.java)
            assertThat(looks.get()).isEqualTo(1)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
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

    /** A poll whose looks classify their own failures, as the SSH readiness wait does, must never retry a throw. */
    @Test
    fun `with exceptions not retried, a look that throws fails the poll at once`() {
        val config =
            RetryUtil.createPollUntilRetryConfig<String>(
                maxAttempts = 5,
                interval = Duration.ZERO,
                done = { it == "ready" },
                retryExceptions = false,
            )

        assertThatThrownBy {
            Retry
                .decorateCheckedSupplier(Retry.of("test-poll", config)) {
                    looks.incrementAndGet()
                    throw IOException("No such file or directory")
                }.get()
        }.isInstanceOf(IOException::class.java)
        assertThat(looks.get()).isEqualTo(1)
    }

    @Test
    fun `with exceptions not retried, a result that is not done is still retried`() {
        val config =
            RetryUtil.createPollUntilRetryConfig<String>(
                maxAttempts = 5,
                interval = Duration.ZERO,
                done = { it == "ready" },
                retryExceptions = false,
            )

        val result =
            Retry
                .decorateCheckedSupplier(Retry.of("test-poll", config)) {
                    listOf("pending", "ready")[looks.getAndIncrement()]
                }.get()

        assertThat(result).isEqualTo("ready")
        assertThat(looks.get()).isEqualTo(2)
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
