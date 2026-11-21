package com.rustyrazorblade.easydblab.services.bringup

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * How [pollUntil] ends: an interrupt while it waits between reads is an [InterruptedException], not
 * resilience4j's `CancellationException`, and the wait never runs past the deadline.
 */
class PollingTest {
    @Test
    fun `an interrupt while waiting between reads is an InterruptedException with the flag set`() {
        var reads = 0
        try {
            Thread.currentThread().interrupt()

            assertThatThrownBy {
                pollUntil<Boolean>("interrupted", Duration.ofSeconds(10), Duration.ofSeconds(30), done = { it }) {
                    reads++
                    false
                }
            }.isInstanceOf(InterruptedException::class.java)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertThat(reads).isEqualTo(1)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `an interval longer than the time left does not stretch the wait past the deadline`() {
        val started = System.nanoTime()

        val last = pollUntil<Boolean>("capped", Duration.ofSeconds(10), Duration.ofMillis(500), done = { it }) { false }

        assertThat(last).isFalse()
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3))
    }
}
