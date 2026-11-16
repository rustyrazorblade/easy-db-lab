package com.rustyrazorblade.easydblab.services.bringup

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LimitersTest {
    /** Runs [callers] tasks that each hold a permit briefly; returns the most ever held at once. */
    private fun maxInFlight(
        callers: Int,
        hold: (() -> Unit) -> Unit,
    ): Int {
        val inFlight = AtomicInteger()
        val max = AtomicInteger()
        val start = CountDownLatch(1)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            repeat(callers) {
                executor.submit {
                    start.await()
                    hold {
                        max.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                        Thread.sleep(20)
                        inFlight.decrementAndGet()
                    }
                }
            }
            start.countDown()
        }
        return max.get()
    }

    @Test
    fun `40 nodes joining together never have more than the join limit in flight`() {
        val limiter = JoinLimiter(HealthLatch(), limit = 16)

        val max = maxInFlight(40) { work -> limiter.acquire().use { work() } }

        assertThat(max).isEqualTo(16)
    }

    @Test
    fun `200 pipelines never open more than the SSH session limit`() {
        val limiter = SshSessionLimiter(limit = 50)

        val max = maxInFlight(200) { work -> limiter.withSession(work) }

        assertThat(max).isEqualTo(50)
    }

    @Test
    fun `no join permit is given while the control plane is unhealthy, and one is when it recovers`() {
        val health = HealthLatch()
        val limiter = JoinLimiter(health, limit = 16)
        health.markUnhealthy()
        val acquired = CountDownLatch(1)

        Thread.ofVirtual().start { limiter.acquire().use { acquired.countDown() } }

        assertThat(acquired.await(200, TimeUnit.MILLISECONDS)).isFalse()
        health.markHealthy()
        assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun `a permit already held is kept when the control plane turns unhealthy`() {
        val health = HealthLatch()
        val limiter = JoinLimiter(health, limit = 1)
        val permit = limiter.acquire()

        health.markUnhealthy()

        assertThat(limiter.inFlight).isEqualTo(1)
        permit.close()
        assertThat(limiter.inFlight).isEqualTo(0)
    }

    @Test
    fun `closing a permit twice releases it once`() {
        val limiter = JoinLimiter(HealthLatch(), limit = 2)
        val first = limiter.acquire()
        limiter.acquire()

        first.close()
        first.close()

        assertThat(limiter.inFlight).isEqualTo(1)
    }

    @Test
    fun `the health latch counts each change to unhealthy, so a step can tell it was unhealthy during an attempt`() {
        val health = HealthLatch()
        val before = health.unhealthyPeriods

        health.markUnhealthy()
        health.markUnhealthy()
        health.markHealthy()
        health.markUnhealthy()

        assertThat(health.unhealthyPeriods - before).isEqualTo(2)
    }
}
