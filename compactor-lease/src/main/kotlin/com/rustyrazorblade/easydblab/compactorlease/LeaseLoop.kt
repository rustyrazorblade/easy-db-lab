package com.rustyrazorblade.easydblab.compactorlease

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration

/**
 * The account compactor's lease state machine: at most one pod per store holds the lease, and only
 * the holder's gate is open.
 *
 * - With no lease object, the node creates it.
 * - The holder renews every renew interval with a conditional replace on its last version. Its gate
 *   is valid until the wall time of the last successful renewal's send plus the renew deadline.
 * - A renewal that finds another version, or no lease, revokes the gate at once and stands by.
 * - A renewal that fails any other way keeps the version; once the renew deadline has passed since
 *   the last successful send it revokes the gate, and the next successful renewal grants it again.
 * - A standby takes over only once it has seen the same version for the TTL on its own monotonic
 *   clock. It never compares clocks with the holder and never reads the renewal time. A pod that
 *   restarts waits like any standby, even for a lease it wrote itself.
 * - On acquiring the lease the node wipes its scratch directories before it opens its gate, so a
 *   compactor never starts on another holding's leftovers.
 *
 * The lease is written only with [ConditionalObjects.createIfAbsent] and
 * [ConditionalObjects.replaceIfVersionMatches]. The loop has no thread of its own: [step] does one
 * round and returns how long to wait before the next, so tests drive it with a fake clock.
 *
 * @property objects the store holding [LeaseRecord.KEY].
 * @property holder this pod's identity, written into the lease.
 * @property timing the lease's timing.
 * @property clock this node's clocks.
 * @property gate the compactor containers' gate.
 * @property wipeScratch empties the compactors' scratch directories.
 */
class LeaseLoop(
    private val objects: ConditionalObjects,
    private val holder: String,
    private val timing: LeaseTiming,
    private val clock: LeaseClock,
    private val gate: Gate,
    private val wipeScratch: () -> Unit,
) {
    private val log = KotlinLogging.logger {}

    /** What this node knows about the lease. */
    private sealed interface State {
        /** The lease has not been read yet; the agent is not ready. */
        data object Starting : State

        /** Another pod may hold the lease. [seenSince] is when [version] was first read, on this clock. */
        data class Standby(
            val version: ObjectVersion?,
            val seenSince: Duration,
        ) : State

        /** This node holds the lease at [version]; [sentAt] is the send time of its last successful write. */
        data class Holding(
            val version: ObjectVersion,
            val record: LeaseRecord,
            val sentAt: Duration,
            val granted: Boolean,
        ) : State
    }

    private var state: State = State.Starting

    /** Whether this node holds the lease. */
    fun isHolder(): Boolean = state is State.Holding

    /**
     * Runs one round: a renewal while holding, otherwise a read of the lease and, when it is
     * missing or has expired on this clock, an attempt to acquire it. A store failure is logged and
     * the round ends; it never ends the loop.
     *
     * @return how long to wait before the next round.
     */
    fun step(): Duration =
        when (val current = state) {
            is State.Holding -> renew(current)
            State.Starting, is State.Standby -> watch(current)
        }

    private fun renew(holding: State.Holding): Duration {
        val sentAt = clock.monotonic()
        val sentWall = clock.wall()
        val record = holding.record.next(holder, sentWall)
        val result = runCatching { objects.replaceIfVersionMatches(LeaseRecord.KEY, record.toJson(), holding.version) }
        result
            .onSuccess { replaced ->
                when (replaced) {
                    is ReplaceResult.Replaced -> {
                        if (!holding.granted) {
                            log.info {
                                "Lease renewed again by $holder at sequence ${record.sequence}; compactors may run"
                            }
                        }
                        gate.grant(sentWall.plusMillis(timing.renewDeadline.inWholeMilliseconds))
                        state = State.Holding(replaced.version, record, sentAt, granted = true)
                    }
                    ReplaceResult.VersionMismatch -> {
                        log.warn { "Lease lost by $holder: another pod wrote it, or it is gone; stopping compactors and standing by" }
                        gate.revoke()
                        state = State.Standby(null, sentAt)
                    }
                }
            }.onFailure { e ->
                val overdue = sentAt - holding.sentAt >= timing.renewDeadline
                log.warn { "Lease renewal by $holder failed, ${sentAt - holding.sentAt} since the last renewal: ${e.message}" }
                if (overdue && holding.granted) {
                    log.warn { "Lease renewal by $holder is past the renew deadline; stopping compactors until a renewal succeeds" }
                    gate.revoke()
                    state = holding.copy(granted = false)
                }
            }
        return timing.renewInterval
    }

    private fun watch(current: State): Duration {
        val now = clock.monotonic()
        val lease =
            runCatching { objects.read(LeaseRecord.KEY) }
                .getOrElse { e ->
                    log.warn { "Lease read by $holder failed: ${e.message}" }
                    return timing.standbyPoll
                }
        val standby =
            when (current) {
                is State.Standby -> current
                else -> {
                    gate.ready()
                    log.info { "Lease agent $holder read the lease: ${lease?.content ?: "none"}" }
                    State.Standby(null, now).also { state = it }
                }
            }
        when {
            lease == null -> create(now)
            standby.version == lease.version && now - standby.seenSince >= timing.ttl -> takeOver(lease)
            standby.version == lease.version -> Unit
            else -> {
                log.info { "Lease agent $holder standing by; lease is ${lease.content}" }
                state = State.Standby(lease.version, now)
            }
        }
        return if (isHolder()) timing.renewInterval else timing.standbyPoll
    }

    private fun create(now: Duration) {
        val record = LeaseRecord(holder, 0, timing.ttl.inWholeSeconds, clock.wall())
        val created = runCatching { objects.createIfAbsent(LeaseRecord.KEY, record.toJson()) }
        created
            .onSuccess { result ->
                when (result) {
                    is CreateResult.Created -> acquire(result.version, record, now, "created")
                    CreateResult.AlreadyExists -> {
                        log.info { "Lease agent $holder lost the race to create the lease; standing by" }
                        state = State.Standby(null, now)
                    }
                }
            }.onFailure { e -> log.warn { "Lease create by $holder failed: ${e.message}" } }
    }

    private fun takeOver(expired: VersionedObject) {
        val sentAt = clock.monotonic()
        val previous = LeaseRecord.fromJson(expired.content)
        val record = previous.next(holder, clock.wall())
        val replaced = runCatching { objects.replaceIfVersionMatches(LeaseRecord.KEY, record.toJson(), expired.version) }
        replaced
            .onSuccess { result ->
                when (result) {
                    is ReplaceResult.Replaced ->
                        acquire(result.version, record, sentAt, "taken over from ${previous.holder} after ${timing.ttl} unchanged")
                    ReplaceResult.VersionMismatch -> {
                        log.info { "Lease agent $holder lost the takeover race; standing by" }
                        state = State.Standby(null, sentAt)
                    }
                }
            }.onFailure { e -> log.warn { "Lease takeover by $holder failed: ${e.message}" } }
    }

    private fun acquire(
        version: ObjectVersion,
        record: LeaseRecord,
        sentAt: Duration,
        how: String,
    ) {
        log.info { "Lease acquired by $holder at sequence ${record.sequence} ($how); wiping scratch, then starting compactors" }
        wipeScratch()
        gate.grant(record.renewedAt.plusMillis(timing.renewDeadline.inWholeMilliseconds))
        state = State.Holding(version, record, sentAt, granted = true)
    }
}
