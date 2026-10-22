package com.rustyrazorblade.easydblab.export.query

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The progress of a long phase, such as a trace export's fetches, is reported every so many items or
 * every so many seconds, whichever comes first, and always once at the end, never twice for one count
 * and never out of order, however many threads advance it.
 */
class ProgressThrottleTest {
    /** A clock a test moves by hand. */
    private class ManualClock(
        var now: Instant = Instant.parse("2026-10-16T12:00:00Z"),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = ManualClock()
    private val reported = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())

    private fun throttle(
        total: Int,
        everyItems: Int = 3,
        interval: Duration = Duration.ofHours(1),
    ) = ProgressThrottle(total.toLong(), everyItems.toLong(), interval, clock) { done, all -> reported += done.toInt() to all.toInt() }

    @Test
    fun `progress is reported every so many items, and at the end`() {
        val progress = throttle(total = 10)

        repeat(10) { progress.advance() }

        assertThat(reported).containsExactly(3 to 10, 6 to 10, 9 to 10, 10 to 10)
    }

    @Test
    fun `progress is reported once the interval has passed, even with fewer items`() {
        val progress = throttle(total = 100, everyItems = 50, interval = Duration.ofSeconds(30))

        progress.advance()
        clock.now = clock.now.plusSeconds(29)
        progress.advance()
        clock.now = clock.now.plusSeconds(1)
        progress.advance()
        progress.advance()
        clock.now = clock.now.plusSeconds(30)
        progress.advance()

        assertThat(reported).containsExactly(3 to 100, 5 to 100)
    }

    @Test
    fun `an end that falls on the item step is reported once`() {
        val progress = throttle(total = 6)

        repeat(6) { progress.advance() }

        assertThat(reported).containsExactly(3 to 6, 6 to 6)
    }

    @Test
    fun `many threads advancing report each count at most once, in order, ending at the total`() {
        val progress = throttle(total = 8000, everyItems = 100)
        val pool = Executors.newFixedThreadPool(8)

        repeat(8000) { pool.submit { progress.advance() } }
        pool.shutdown()
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue()

        val counts = reported.map { it.first }
        assertThat(counts).isSorted().doesNotHaveDuplicates().endsWith(8000)
        assertThat(counts).hasSize(80)
    }
}
