package com.rustyrazorblade.easydblab.compactorlease

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The lease state machine, driven one step at a time against the in-memory store with a fake
 * clock. Each test advances the clock by what [LeaseLoop.step] asks to wait, as the real loop does.
 */
class LeaseLoopTest {
    private val timing =
        LeaseTiming(
            ttl = 120.seconds,
            renewInterval = 15.seconds,
            renewDeadline = 60.seconds,
            standbyPoll = 15.seconds,
            gatePoll = 1.seconds,
            gateGrace = 20.seconds,
        )
    private val start = Instant.parse("2026-11-07T18:00:00Z")
    private val store = InMemoryConditionalObjects()

    /** A fake node clock: the monotonic and wall clocks move together, as on one node. */
    private class FakeClock(
        private val start: Instant,
    ) : LeaseClock {
        var elapsed: Duration = Duration.ZERO

        override fun monotonic(): Duration = elapsed

        override fun wall(): Instant = start.plusMillis(elapsed.inWholeMilliseconds)
    }

    /** Records every gate write and scratch wipe, in order. */
    private class RecordingGate : Gate {
        val events = mutableListOf<String>()
        var validUntil: Instant? = null

        override fun grant(validUntil: Instant) {
            this.validUntil = validUntil
            events += "grant $validUntil"
        }

        override fun revoke() {
            validUntil = null
            events += "revoke"
        }

        override fun ready() {
            events += "ready"
        }

        val wipe: () -> Unit = { events += "wipe" }
    }

    private inner class Node(
        val holder: String,
        objects: ConditionalObjects = store,
    ) {
        val clock = FakeClock(start)
        val gate = RecordingGate()
        val loop = LeaseLoop(objects, holder, timing, clock, gate, gate.wipe)
        private var wait = Duration.ZERO

        /** Waits what the last step asked, then runs the next step. */
        fun step() {
            clock.elapsed += wait
            wait = loop.step()
        }

        /** Runs steps until [total] has passed on this node's clock. */
        fun runFor(total: Duration) {
            val until = clock.elapsed + total
            while (clock.elapsed + wait <= until) step()
        }

        fun holds(): Boolean = loop.isHolder()
    }

    private fun lease(): LeaseRecord? = store.read(LeaseRecord.KEY)?.let { LeaseRecord.fromJson(it.content) }

    private fun otherHolderWrites(sequence: Long) {
        val current = store.read(LeaseRecord.KEY)
        val record = LeaseRecord("other/pod", sequence, 120, start)
        when (current) {
            null -> store.createIfAbsent(LeaseRecord.KEY, record.toJson())
            else -> store.replaceIfVersionMatches(LeaseRecord.KEY, record.toJson(), current.version)
        }
    }

    @Test
    fun `a node with no lease object creates it, wipes its scratch, then opens its gate`() {
        val node = Node("lab-a/pod-1")

        node.step()

        assertThat(node.holds()).isTrue()
        assertThat(lease()).isEqualTo(LeaseRecord("lab-a/pod-1", 0, 120, start))
        assertThat(node.gate.events).containsExactly("ready", "wipe", "grant ${start.plusSeconds(60)}")
    }

    @Test
    fun `the holder renews with a rising sequence and moves its valid-until with each renewal`() {
        val node = Node("lab-a/pod-1")
        node.step()

        node.step()
        node.step()

        assertThat(lease()?.sequence).isEqualTo(2)
        assertThat(lease()?.renewedAt).isEqualTo(start.plusSeconds(30))
        assertThat(node.gate.validUntil).isEqualTo(start.plusSeconds(30 + 60))
        assertThat(node.gate.events.count { it == "wipe" }).isEqualTo(1)
    }

    @Test
    fun `a standby that sees the version change never takes over, however long it waits`() {
        otherHolderWrites(0)
        val standby = Node("lab-b/pod-1")

        repeat(40) { round ->
            standby.step()
            otherHolderWrites(round + 1L)
        }

        assertThat(standby.clock.elapsed).isGreaterThan(timing.ttl * 4)
        assertThat(standby.holds()).isFalse()
        assertThat(lease()?.holder).isEqualTo("other/pod")
        assertThat(standby.gate.events).containsExactly("ready")
    }

    @Test
    fun `a standby takes over only once it has seen one version for the TTL on its own clock`() {
        otherHolderWrites(7)
        val standby = Node("lab-b/pod-1")
        standby.step()

        standby.runFor(timing.ttl - 1.seconds)
        assertThat(standby.holds()).describedAs("before the TTL").isFalse()
        assertThat(lease()?.holder).isEqualTo("other/pod")

        standby.runFor(timing.standbyPoll)
        assertThat(standby.holds()).describedAs("after the TTL").isTrue()
        assertThat(lease()?.holder).isEqualTo("lab-b/pod-1")
        assertThat(lease()?.sequence).isEqualTo(8)
        assertThat(standby.gate.events).containsExactly("ready", "wipe", "grant ${standby.clock.wall().plusSeconds(60)}")
    }

    @Test
    fun `a holder whose renewal finds another version closes its gate and stands by`() {
        val node = Node("lab-a/pod-1")
        node.step()
        otherHolderWrites(5)

        node.step()

        assertThat(node.holds()).isFalse()
        assertThat(node.gate.events.last()).isEqualTo("revoke")
        assertThat(lease()?.holder).isEqualTo("other/pod")
    }

    @Test
    fun `a holder whose renewal finds the lease missing closes its gate and stands by`() {
        val node = Node("lab-a/pod-1")
        node.step()
        store.remove(LeaseRecord.KEY)

        node.step()

        assertThat(node.holds()).isFalse()
        assertThat(node.gate.events.last()).isEqualTo("revoke")
    }

    @Test
    fun `a restarted pod does not take back its own lease before the TTL`() {
        store.createIfAbsent(LeaseRecord.KEY, LeaseRecord("lab-a/pod-1", 3, 120, start).toJson())
        val restarted = Node("lab-a/pod-1")
        restarted.step()

        restarted.runFor(timing.ttl - 1.seconds)
        assertThat(restarted.holds()).isFalse()

        restarted.runFor(timing.standbyPoll)
        assertThat(restarted.holds()).isTrue()
        assertThat(lease()?.sequence).isEqualTo(4)
        assertThat(restarted.gate.events).contains("wipe")
    }

    @Test
    fun `a holder that cannot reach the store closes its gate at the deadline and reopens it after a renewal`() {
        val flaky = FlakyObjects(store)
        val node = Node("lab-a/pod-1", flaky)
        node.step()
        val lastGrant = node.gate.validUntil
        flaky.failing = true

        node.runFor(timing.renewDeadline - 1.seconds)
        assertThat(node.gate.validUntil).describedAs("before the deadline").isEqualTo(lastGrant)

        node.runFor(timing.renewInterval)
        assertThat(node.gate.events.last()).describedAs("after the deadline").isEqualTo("revoke")
        assertThat(node.holds()).describedAs("its version is still the lease's").isTrue()

        flaky.failing = false
        node.step()
        assertThat(node.gate.validUntil).isEqualTo(node.clock.wall().plusSeconds(60))
        assertThat(node.gate.events.count { it == "wipe" }).isEqualTo(1)
    }

    @Test
    fun `a standby is not ready until it has read the lease once`() {
        val flaky = FlakyObjects(store).apply { failing = true }
        val node = Node("lab-a/pod-1", flaky)

        node.step()
        assertThat(node.gate.events).isEmpty()

        flaky.failing = false
        node.step()
        assertThat(node.gate.events.first()).isEqualTo("ready")
    }

    @Test
    fun `of two nodes on one store exactly one holds the lease at every step`() {
        val a = Node("lab-a/pod-1")
        val b = Node("lab-b/pod-1")

        repeat(50) {
            a.step()
            b.step()
            assertThat(listOf(a.holds(), b.holds()).count { it }).isEqualTo(1)
        }
        assertThat(a.holds()).describedAs("the first node to step creates the lease and keeps it").isTrue()
    }

    @Test
    fun `when the holder stops, the other node takes over after the TTL and opens its gate`() {
        val a = Node("lab-a/pod-1")
        val b = Node("lab-b/pod-1")
        a.step()
        b.step()
        val stoppedAt = b.clock.elapsed

        b.runFor(timing.ttl + timing.standbyPoll * 2)

        assertThat(b.holds()).isTrue()
        assertThat(b.gate.validUntil).isNotNull()
        assertThat(lease()?.holder).isEqualTo("lab-b/pod-1")
        assertThat(b.clock.elapsed - stoppedAt).isGreaterThanOrEqualTo(timing.ttl)
    }

    /** A store that throws while [failing], as S3 does when it cannot be reached. */
    private class FlakyObjects(
        private val delegate: ConditionalObjects,
    ) : ConditionalObjects {
        var failing = false

        private fun <T> unlessFailing(call: () -> T): T = if (failing) error("store unreachable") else call()

        override fun read(key: String) = unlessFailing { delegate.read(key) }

        override fun createIfAbsent(
            key: String,
            content: String,
        ) = unlessFailing { delegate.createIfAbsent(key, content) }

        override fun replaceIfVersionMatches(
            key: String,
            content: String,
            expected: ObjectVersion,
        ) = unlessFailing { delegate.replaceIfVersionMatches(key, content, expected) }
    }
}
