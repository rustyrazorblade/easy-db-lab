package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.BaseKoinTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class TickLoopTest : BaseKoinTest() {
    /** A clock that moves only when a cycle runs or the loop sleeps. */
    private class FakeClock(
        var now: Instant = Instant.parse("2026-11-01T10:00:00Z"),
    ) : Clock() {
        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    private val clock = FakeClock()
    private val start = clock.now
    private val starts = mutableListOf<Long>()
    private val ends = mutableListOf<Long>()
    private val loop = TickLoop(clock = clock, sleeper = { clock.now = clock.now.plus(it) }, interval = Duration.ofSeconds(5))

    private fun seconds(): Long = Duration.between(start, clock.now).seconds

    /** Runs cycles that take [durations] seconds each, then stops. */
    private fun runCycles(vararg durations: Long) {
        var index = 0
        loop.run(isStopped = { index >= durations.size }) {
            starts += seconds()
            clock.now = clock.now.plusSeconds(durations[index++])
            ends += seconds()
            true
        }
    }

    @Test
    fun `cycles start on 5-second ticks of the start time`() {
        runCycles(1, 2, 0, 4)

        assertThat(starts).containsExactly(0L, 5L, 10L, 15L)
    }

    @Test
    fun `a cycle still running at the next tick skips that tick`() {
        runCycles(7, 1, 1)

        assertThat(starts).containsExactly(0L, 10L, 15L)
    }

    @Test
    fun `a cycle that ends exactly on a tick waits for the following one`() {
        runCycles(5, 1)

        assertThat(starts).containsExactly(0L, 10L)
    }

    @Test
    fun `cycles never overlap`() {
        runCycles(12, 3, 6, 1)

        starts.drop(1).zip(ends).forEach { (next, previousEnd) -> assertThat(next).isGreaterThanOrEqualTo(previousEnd) }
    }

    @Test
    fun `the loop ends when the cycle says so`() {
        var cycles = 0
        loop.run(isStopped = { false }) { ++cycles < 3 }

        assertThat(cycles).isEqualTo(3)
    }

    @Test
    fun `an interrupted sleep ends the loop`() {
        var cycles = 0
        TickLoop(clock = clock, sleeper = { throw InterruptedException() }, interval = Duration.ofSeconds(5))
            .run(isStopped = { false }) {
                cycles++
                true
            }

        assertThat(cycles).isEqualTo(1)
    }
}
