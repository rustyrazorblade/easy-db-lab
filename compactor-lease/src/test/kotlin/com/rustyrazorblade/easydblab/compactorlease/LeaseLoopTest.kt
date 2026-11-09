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

    /**
     * A fake node clock: the monotonic and wall clocks move together, as on one node. With
     * [failNextRead] set, the next monotonic read throws, as an unexpected collaborator failure.
     */
    private class FakeClock(
        private val start: Instant,
    ) : LeaseClock {
        var elapsed: Duration = Duration.ZERO
        var failNextRead = false

        override fun monotonic(): Duration {
            if (failNextRead) {
                failNextRead = false
                error("clock unavailable")
            }
            return elapsed
        }

        override fun wall(): Instant = start.plusMillis(elapsed.inWholeMilliseconds)
    }

    /** Records every gate write and scratch wipe, in order. With [failNextGrant] set, the next grant throws. */
    private class RecordingGate : Gate {
        val events = mutableListOf<String>()
        var validUntil: Instant? = null
        var failNextGrant = false

        override fun grant(validUntil: Instant) {
            if (failNextGrant) {
                failNextGrant = false
                error("gate file not writable")
            }
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
        val gate: RecordingGate = RecordingGate(),
        wipe: () -> Unit = {},
    ) {
        val clock = FakeClock(start)
        val loop =
            LeaseLoop(objects, holder, timing, clock, gate) {
                gate.wipe()
                wipe()
            }
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

    @Test
    fun `a standby counts the TTL from when a slow read returned, not from when it was sent`() {
        otherHolderWrites(0)
        val slow = InterleavingObjects(store)
        val standby = Node("lab-b/pod-1", slow)
        standby.step()
        // The next read takes 60 s, and the holder renews while it is in flight.
        slow.onRead = {
            slow.onRead = null
            standby.clock.elapsed += 60.seconds
            otherHolderWrites(1)
        }
        standby.step()
        val readReturned = standby.clock.elapsed

        standby.runFor(timing.ttl - 1.seconds)
        assertThat(standby.holds()).describedAs("before a TTL since the read returned").isFalse()
        assertThat(lease()?.holder).isEqualTo("other/pod")

        standby.runFor(timing.standbyPoll)
        assertThat(standby.holds()).isTrue()
        assertThat(standby.clock.elapsed - readReturned).isGreaterThanOrEqualTo(timing.ttl)
    }

    @Test
    fun `a node that finds no lease but loses the create to another pod neither wipes nor opens its gate`() {
        val racing = InterleavingObjects(store)
        racing.beforeWrite = { otherHolderWrites(0) }
        val node = Node("lab-a/pod-1", racing)

        node.step()

        assertThat(node.holds()).isFalse()
        assertThat(node.gate.events).doesNotContain("wipe").noneMatch { it.startsWith("grant") }
        assertThat(lease()?.holder).isEqualTo("other/pod")
    }

    @Test
    fun `a standby whose takeover loses to another pod neither wipes nor opens its gate`() {
        otherHolderWrites(0)
        val racing = InterleavingObjects(store)
        val standby = Node("lab-b/pod-1", racing)
        standby.step()
        racing.beforeWrite = {
            racing.beforeWrite = null
            otherHolderWrites(1)
        }

        standby.runFor(timing.ttl + timing.standbyPoll)

        assertThat(racing.writes).describedAs("the takeover was attempted").isEqualTo(1)
        assertThat(standby.holds()).isFalse()
        assertThat(standby.gate.events).doesNotContain("wipe").noneMatch { it.startsWith("grant") }
        assertThat(lease()?.holder).isEqualTo("other/pod")
        assertThat(lease()?.sequence).isEqualTo(1)
    }

    @Test
    fun `a lease body that does not parse keeps the node standing by with its gate closed`() {
        store.createIfAbsent(LeaseRecord.KEY, "not a lease")
        val node = Node("lab-b/pod-1")

        node.runFor(timing.ttl * 3)

        assertThat(node.holds()).isFalse()
        assertThat(node.gate.events).doesNotContain("wipe").noneMatch { it.startsWith("grant") }
        assertThat(node.gate.events.last()).isEqualTo("revoke")
        assertThat(store.read(LeaseRecord.KEY)?.content).isEqualTo("not a lease")
    }

    @Test
    fun `a node whose scratch wipe fails never opens its gate, and acquires again once the wipe works`() {
        var wipeFails = true
        val gate = RecordingGate()
        val clock = FakeClock(start)
        val loop =
            LeaseLoop(store, "lab-a/pod-1", timing, clock, gate) {
                gate.events += "wipe"
                if (wipeFails) error("disk full")
            }

        loop.step()

        assertThat(gate.events).containsExactly("ready", "wipe", "revoke")
        assertThat(loop.isHolder()).isFalse()

        wipeFails = false
        var wait = timing.standbyPoll
        while (!loop.isHolder() && clock.elapsed < timing.ttl * 5) {
            clock.elapsed += wait
            wait = loop.step()
        }
        assertThat(loop.isHolder()).isTrue()
        assertThat(gate.events.takeLast(2)).containsExactly("wipe", "grant ${clock.wall().plusSeconds(60)}")
        assertThat(clock.elapsed)
            .describedAs("it backs off a TTL, then waits a TTL on the version it wrote")
            .isGreaterThanOrEqualTo(timing.ttl * 2)
    }

    @Test
    fun `a node whose wipe always fails lets a healthy standby take the lease and open its gate`() {
        val broken = Node("lab-a/pod-1", wipe = { error("disk full") })
        val healthy = Node("lab-b/pod-1")

        repeat(80) {
            broken.step()
            healthy.step()
        }

        assertThat(healthy.holds()).isTrue()
        assertThat(lease()?.holder).isEqualTo("lab-b/pod-1")
        assertThat(healthy.gate.validUntil).isNotNull()
        assertThat(broken.gate.events).noneMatch { it.startsWith("grant") }
    }

    @Test
    fun `a renewal that lands while the gate write fails closes the gate, keeps the lease, and opens it at the next renewal`() {
        val node = Node("lab-a/pod-1")
        val gate = node.gate
        node.step()
        gate.failNextGrant = true

        node.step()

        assertThat(gate.events.last()).isEqualTo("revoke")
        assertThat(node.holds()).isTrue()
        node.step()
        assertThat(gate.events.last()).isEqualTo("grant ${node.clock.wall().plusSeconds(60)}")
        assertThat(node.holds()).isTrue()
    }

    @Test
    fun `an unexpected failure in a round closes the gate, stands the holder by, and the next round runs`() {
        val node = Node("lab-a/pod-1")
        node.step()
        assertThat(node.holds()).isTrue()

        node.clock.failNextRead = true
        node.step()

        assertThat(node.gate.events.last()).isEqualTo("revoke")
        assertThat(node.holds()).isFalse()
        node.step()
        assertThat(node.gate.events.last()).describedAs("the next round reads the lease and stands by").isEqualTo("revoke")
        assertThat(node.holds()).isFalse()
        node.runFor(timing.ttl + timing.standbyPoll * 2)
        assertThat(node.holds()).describedAs("its own lease, unrenewed, expires and it takes it back").isTrue()
        assertThat(node.gate.validUntil).isNotNull()
    }

    /**
     * Runs [onRead] inside a read, before it answers, and [beforeWrite] before each conditional
     * write reaches the store, so a test can put another pod's write between the loop's read and its
     * write, or inside a slow read.
     */
    private class InterleavingObjects(
        private val delegate: ConditionalObjects,
    ) : ConditionalObjects {
        var onRead: (() -> Unit)? = null
        var beforeWrite: (() -> Unit)? = null
        var writes = 0

        override fun read(key: String): VersionedObject? {
            onRead?.invoke()
            return delegate.read(key)
        }

        override fun createIfAbsent(
            key: String,
            content: String,
        ): CreateResult {
            beforeWrite?.invoke()
            writes++
            return delegate.createIfAbsent(key, content)
        }

        override fun replaceIfVersionMatches(
            key: String,
            content: String,
            expected: ObjectVersion,
        ): ReplaceResult {
            beforeWrite?.invoke()
            writes++
            return delegate.replaceIfVersionMatches(key, content, expected)
        }
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
