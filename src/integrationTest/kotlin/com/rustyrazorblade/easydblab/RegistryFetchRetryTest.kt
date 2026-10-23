package com.rustyrazorblade.easydblab

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Checks [RegistryFetchRetry]: a registry read that stalls (Jib reports a blob read timeout
 * wrapped in an [ExecutionException]) is tried again, and any other failure fails at once.
 */
class RegistryFetchRetryTest {
    private val retry = RegistryFetchRetry(maxAttempts = 3, interval = Duration.ofMillis(1))

    @Test
    fun `a fetch that times out reading the registry is tried again`() {
        val attempts = AtomicInteger()

        val result =
            retry.run("busybox:1.36") {
                if (attempts.incrementAndGet() < 3) throw ExecutionException(SocketTimeoutException("Read timed out"))
                "fetched"
            }

        assertThat(result).isEqualTo("fetched")
        assertThat(attempts.get()).isEqualTo(3)
    }

    @Test
    fun `a fetch that keeps timing out fails after the last attempt with the timeout`() {
        val attempts = AtomicInteger()

        assertThatThrownBy {
            retry.run("busybox:1.36") {
                attempts.incrementAndGet()
                throw SocketTimeoutException("Read timed out")
            }
        }.isInstanceOf(SocketTimeoutException::class.java)
        assertThat(attempts.get()).isEqualTo(3)
    }

    @Test
    fun `a failure that is not a registry read error is not tried again`() {
        val attempts = AtomicInteger()

        assertThatThrownBy {
            retry.run("busybox:1.36") {
                attempts.incrementAndGet()
                error("incomplete archive")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(attempts.get()).isEqualTo(1)
    }
}
