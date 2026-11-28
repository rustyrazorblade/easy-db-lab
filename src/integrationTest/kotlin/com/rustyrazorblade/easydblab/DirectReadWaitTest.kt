package com.rustyrazorblade.easydblab

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Proves how [DirectReadWait.await] reports a wait whose reads fail: a read that keeps throwing
 * ends in an [AssertionError] that names the wait and keeps the read's exception as its cause, and
 * an interrupt (a set interrupt flag, or a read that throws [InterruptedException]) gets the read's
 * own exception back, unwrapped.
 *
 * The reads throw directly, so the test needs no cluster.
 */
class DirectReadWaitTest {
    @AfterEach
    fun clearInterrupt() {
        // Thread.interrupted() reads and clears the flag, so a set flag cannot leak into the next test.
        Thread.interrupted()
    }

    @Test
    fun `a read that always throws ends the wait in an error that names it and keeps the read's exception`() {
        val failure = IllegalStateException("the read failed")

        val thrown =
            catchThrowable {
                DirectReadWait.await<String>("the probe is ready", Duration.ofSeconds(1), { it }, { true }) { throw failure }
            }

        assertThat(thrown)
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("the probe is ready")
            .hasMessageContaining("last read failed")
        assertThat(thrown.cause).isSameAs(failure)
    }

    @Test
    fun `a read that throws while the thread is interrupted gets its own exception back`() {
        val failure = IllegalStateException("the read failed")
        Thread.currentThread().interrupt()

        val thrown =
            catchThrowable {
                DirectReadWait.await<String>("the probe is ready", Duration.ofSeconds(1), { it }, { true }) { throw failure }
            }

        assertThat(Thread.currentThread().isInterrupted).isTrue()
        assertThat(thrown).isSameAs(failure)
    }

    @Test
    fun `a read that throws InterruptedException gets that exception back`() {
        val interrupt = InterruptedException("the read was interrupted")

        val thrown =
            catchThrowable {
                DirectReadWait.await<String>("the probe is ready", Duration.ofSeconds(1), { it }, { true }) { throw interrupt }
            }

        assertThat(thrown).isSameAs(interrupt)
    }
}
