package com.rustyrazorblade.easydblab.profiling

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Tests for the CLI-side bounds that keep a node from being handed a configuration it can only fail
 * at silently.
 *
 * Every case here has the same shape: the node accepts the value, does exactly what it was told, and
 * produces no profiles — with nothing anywhere reported as broken. That is what makes these worth
 * validating at the CLI rather than leaving to the reconciler.
 */
class ProfilingBoundsTest {
    @Test
    fun `converts every rotation interval spelling to seconds`() {
        assertThat(profilingLoopSeconds("30")).isEqualTo(30)
        assertThat(profilingLoopSeconds("30s")).isEqualTo(30)
        assertThat(profilingLoopSeconds("5m")).isEqualTo(300)
        assertThat(profilingLoopSeconds("1h")).isEqualTo(3600)
        assertThat(profilingLoopSeconds("1H")).isEqualTo(3600)
    }

    @Test
    fun `refuses a retention window shorter than the time a chunk needs to become shippable`() {
        // Reachable with no unusual input at all: an hourly rotation against the DEFAULT retention.
        // A chunk waits one rotation plus the grace before it is complete, and the node only looks
        // once per reconcile interval, so 3670 seconds of waiting sits inside a 3600-second window.
        // Nothing fails — every chunk is age-pruned on the pass before the one that would ship it,
        // and Pyroscope simply stays empty.
        assertThatThrownBy {
            requireProfilingBounds(
                retentionMinutes = Constants.Profiling.DEFAULT_RETENTION_MINUTES,
                maxBytes = Constants.Profiling.DEFAULT_MAX_BYTES,
                loopInterval = "1h",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--retention")
            .hasMessageContaining("--loop")
            .hasMessageContaining("prunes every chunk before it can ship")
    }

    @Test
    fun `refuses a one-minute window against a five-minute rotation`() {
        assertThatThrownBy {
            requireProfilingBounds(
                retentionMinutes = 1,
                maxBytes = Constants.Profiling.DEFAULT_MAX_BYTES,
                loopInterval = "5m",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--loop 5m")
    }

    @Test
    fun `accepts a window exactly long enough to hold the shipping delay`() {
        // 30s rotation + 10s grace + 60s reconcile interval is 100 seconds; two minutes clears it
        // and one minute does not. The boundary is asserted from both sides so a change to any of
        // the three terms has to be deliberate.
        assertThatCode {
            requireProfilingBounds(retentionMinutes = 2, maxBytes = Constants.Profiling.DEFAULT_MAX_BYTES, loopInterval = "30s")
        }.doesNotThrowAnyException()

        assertThatThrownBy {
            requireProfilingBounds(retentionMinutes = 1, maxBytes = Constants.Profiling.DEFAULT_MAX_BYTES, loopInterval = "30s")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `accepts the bounds a cluster is seeded with`() {
        assertThatCode {
            requireProfilingBounds(
                retentionMinutes = Constants.Profiling.DEFAULT_RETENTION_MINUTES,
                maxBytes = Constants.Profiling.DEFAULT_MAX_BYTES,
                loopInterval = Constants.Profiling.DEFAULT_LOOP_INTERVAL,
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `refuses a rotation faster than the shipper can drain`() {
        // A pass uploads at most SHIP_MAX_CHUNKS_PER_PASS chunks, and that cap cannot be raised: six
        // uploads at the node's 30-second upload timeout is 180s inside a 300s TimeoutStartSec.
        // A 5-second rotation produces twelve chunks per 60-second pass against that ceiling, so the
        // queue grows by six every pass forever and everything aging past retention is deleted
        // unshipped. Nothing reports a failure — the truncation warning reads as a draining backlog.
        assertThatThrownBy {
            requireProfilingBounds(
                retentionMinutes = 2,
                maxBytes = Constants.Profiling.DEFAULT_MAX_BYTES,
                loopInterval = "5s",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--loop")
            .hasMessageContaining("faster than the node can ship")
    }

    @Test
    fun `accepts the fastest rotation the shipper keeps up with`() {
        // Both sides of the boundary, so the floor cannot drift away from the budget it is derived
        // from. At 10s the pass produces exactly six chunks and ships exactly six; at 9s it does not.
        assertThat(Constants.Profiling.MIN_LOOP_SECONDS).isEqualTo(10)

        assertThatCode {
            requireProfilingBounds(retentionMinutes = 2, maxBytes = Constants.Profiling.DEFAULT_MAX_BYTES, loopInterval = "10s")
        }.doesNotThrowAnyException()

        assertThatThrownBy {
            requireProfilingBounds(retentionMinutes = 2, maxBytes = Constants.Profiling.DEFAULT_MAX_BYTES, loopInterval = "9s")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `refuses a retention window the node would prune everything under`() {
        assertThatThrownBy {
            requireProfilingBounds(retentionMinutes = 0, maxBytes = Constants.Profiling.DEFAULT_MAX_BYTES, loopInterval = "1m")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--retention")
    }

    @Test
    fun `refuses a byte ceiling that cannot hold a single chunk`() {
        // The byte bound loses data the same way a too-short window does: a ceiling below one JFR
        // chunk means every pass prunes what the last one collected.
        assertThatThrownBy {
            requireProfilingBounds(retentionMinutes = 60, maxBytes = 4096, loopInterval = "1m")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--max-bytes")
            .hasMessageContaining("deleting every chunk as it lands")
    }

    @Test
    fun `refuses a chunk count that would select the whole profile directory`() {
        // `--last` reaches the node as `head -n <count>`, and GNU head reads a negative count as
        // "all but the last N lines" — so -1 fetches everything rather than nothing. Not reachable
        // by typo from the CLI, but reachable over MCP where nobody typed it.
        assertThatThrownBy { requireChunkCount(-1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--last")
            .hasMessageContaining("head -n")

        assertThatThrownBy { requireChunkCount(0) }
            .isInstanceOf(IllegalArgumentException::class.java)

        assertThatCode { requireChunkCount(1) }.doesNotThrowAnyException()
    }
}
