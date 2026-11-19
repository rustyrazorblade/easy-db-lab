package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [BoundedParallel]'s contract with its caller: an action's failure comes back as that item's own
 * exception, and interrupting the caller (Ctrl-C during `up`) ends the fan-out promptly with an
 * [InterruptedException] instead of being turned into per-item failures or waiting on the tasks.
 */
class BoundedParallelTest {
    @Test
    fun `an action that throws keeps its own exception, not an ExecutionException`() {
        val results = BoundedParallel(maxConcurrency = 2).map(listOf(1, 2, 3)) { if (it == 2) error("node $it failed") else it }

        assertThat(results[0].getOrNull()).isEqualTo(1)
        assertThat(results[1].exceptionOrNull())
            .isExactlyInstanceOf(IllegalStateException::class.java)
            .hasMessage("node 2 failed")
        assertThat(results[2].getOrNull()).isEqualTo(3)
    }

    @Test
    fun `interrupting the caller while every task blocks ends the fan-out with InterruptedException`() {
        val started = CountDownLatch(2)
        val thrown = AtomicReference<Throwable?>()
        val returned = AtomicReference<List<Result<Unit>>?>()
        val caller =
            Thread {
                try {
                    returned.set(
                        BoundedParallel(maxConcurrency = 2).map(listOf(1, 2, 3)) {
                            started.countDown()
                            Thread.sleep(TimeUnit.MINUTES.toMillis(BLOCK_MINUTES))
                        },
                    )
                } catch (e: Throwable) {
                    thrown.set(e)
                }
            }
        caller.start()
        assertThat(started.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()

        caller.interrupt()
        caller.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))

        assertThat(caller.isAlive).describedAs("the caller is still waiting on the blocked tasks").isFalse()
        assertThat(returned.get()).isNull()
        assertThat(thrown.get()).isInstanceOf(InterruptedException::class.java)
    }

    private companion object {
        const val BLOCK_MINUTES = 10L
        const val WAIT_SECONDS = 5L
    }
}
