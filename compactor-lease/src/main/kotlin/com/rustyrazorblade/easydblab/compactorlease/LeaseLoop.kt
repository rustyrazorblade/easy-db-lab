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
 * - A renewal that does not succeed is judged only after one read of the lease back. If the lease
 *   holds exactly the record this node just tried to write, the write landed and only its answer
 *   was lost (a timeout after S3 committed it, or S3's 409 for a conditional write in flight), so
 *   the node adopts the version it read and keeps the lease. The adopted write counts as a renewal,
 *   except that it never reopens a gate that is closed or past the renew deadline; only the next
 *   renewal that succeeds does.
 * - Otherwise a renewal that finds another version, or no lease, revokes the gate at once and
 *   stands by.
 * - A renewal that fails any other way keeps the version; once the renew deadline has passed since
 *   the last successful send it revokes the gate, and the next successful renewal grants it again.
 * - A standby takes over only once it has seen the same version for the TTL on its own monotonic
 *   clock, counted from when the first read that returned that version came back. It never
 *   compares clocks with the holder and never reads the renewal time. A pod that restarts waits
 *   like any standby, even for a lease it wrote itself.
 * - On acquiring the lease the node wipes its scratch directories before it opens its gate, so a
 *   compactor never starts on another holding's leftovers. If the wipe or the gate write fails, the
 *   gate stays closed and the node stands by on the version it wrote, waiting one extra TTL, so a
 *   healthy standby takes the lease first. It never opens the gate after a failed wipe.
 * - A lease body that does not parse is never taken over; the node logs it once per version and
 *   stands by. Deleting the lease object by hand lets a node create a new one.
 *
 * The lease is written only with [ConditionalObjects.createIfAbsent] and
 * [ConditionalObjects.replaceIfVersionMatches]. The loop has no thread of its own: [step] does one
 * round and returns how long to wait before the next, so tests drive it with a fake clock;
 * [runUntilInterrupted] is the agent's loop around it. An [InterruptedException] is never a failed
 * round: it closes the gate and propagates with the thread's interrupt flag set.
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

        /**
         * Another pod may hold the lease. [seenSince] is when the first read that returned [version]
         * came back, on this clock. [seenHolder] is the holder that version names, and
         * [retryingAcquire] marks this node's own version after a failed acquire; both are for the
         * log only.
         */
        data class Standby(
            val version: ObjectVersion?,
            val seenSince: Duration,
            val seenHolder: String? = null,
            val retryingAcquire: Boolean = false,
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

    /** The last lease version whose body did not parse, so its ERROR is logged once. */
    private var unparsedVersion: ObjectVersion? = null

    /** Whether this node holds the lease. */
    fun isHolder(): Boolean = state is State.Holding

    /**
     * Runs rounds until the thread is interrupted, waiting with [sleep] for what each round asks.
     * An interrupt, in a round or in the wait, closes the gate and returns with the interrupt flag
     * set, so the agent ends cleanly.
     */
    fun runUntilInterrupted(sleep: (Duration) -> Unit) {
        try {
            while (true) sleep(step())
        } catch (_: InterruptedException) {
            log.info { "Lease agent $holder interrupted; stopping compactors and exiting" }
            closeGate()
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Runs one round: a renewal while holding, otherwise a read of the lease and, when it is
     * missing or has expired on this clock, an attempt to acquire it. A store, gate or scratch
     * failure is logged with its cause and ends the round; it never ends the loop. A failure that
     * could leave a compactor running without a valid lease also closes the gate.
     *
     * @return how long to wait before the next round.
     * @throws InterruptedException when the thread is interrupted during the round, with the gate
     *   closed and the interrupt flag set.
     */
    fun step(): Duration =
        try {
            when (val current = state) {
                is State.Holding -> renew(current)
                State.Starting, is State.Standby -> watch(current)
            }
        } catch (e: InterruptedException) {
            closeGate()
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            log.error(e) { "Lease agent $holder failed a round; stopping compactors and standing by" }
            closeGate()
            if (state is State.Holding) state = State.Standby(null, clock.monotonic())
            timing.standbyPoll
        }

    private fun renew(holding: State.Holding): Duration {
        val sentAt = clock.monotonic()
        val sentWall = clock.wall()
        val record = holding.record.next(holder, timing.ttl.inWholeSeconds, sentWall)
        val result = attempt { objects.replaceIfVersionMatches(LeaseRecord.KEY, record.toJson(), holding.version) }
        val replaced = result.getOrNull()
        if (replaced is ReplaceResult.Replaced) {
            renewed(holding, replaced.version, record, sentAt)
            return timing.renewInterval
        }
        val landed = ownWriteInStore(record)
        when {
            landed != null -> adopt(holding, landed, record, sentAt, result)
            replaced == ReplaceResult.VersionMismatch -> {
                log.warn { "Lease lost by $holder: another pod wrote it, or it is gone; stopping compactors and standing by" }
                closeGate()
                state = State.Standby(null, sentAt)
            }
            else -> {
                log.warn(result.exceptionOrNull()) { "Lease renewal by $holder failed, ${sentAt - holding.sentAt} since the last renewal" }
                if (holding.granted && overdue(holding, sentAt)) revokeOverdue(holding)
            }
        }
        return timing.renewInterval
    }

    /** Whether a renewal sent at [sentAt] is past the renew deadline of [holding]'s last successful write. */
    private fun overdue(
        holding: State.Holding,
        sentAt: Duration,
    ): Boolean = sentAt - holding.sentAt >= timing.renewDeadline

    private fun revokeOverdue(holding: State.Holding) {
        log.warn { "Lease renewal by $holder is past the renew deadline; stopping compactors until a renewal succeeds" }
        closeGate()
        state = holding.copy(granted = false)
    }

    /**
     * The version the store holds [record] at, when it holds exactly [record]: a renewal that landed
     * although its answer said otherwise. It is one read, never retried, made in the same round; a
     * read that fails is logged and answers `null`, so the renewal is judged by what it reported.
     */
    private fun ownWriteInStore(record: LeaseRecord): ObjectVersion? {
        val current =
            attempt { objects.read(LeaseRecord.KEY) }
                .getOrElse { e ->
                    log.warn(e) { "Lease agent $holder could not read the lease back after a renewal that did not succeed" }
                    return null
                } ?: return null
        val inStore = attempt { LeaseRecord.fromJson(current.content) }.getOrNull()
        return current.version.takeIf { inStore == record }
    }

    /**
     * Keeps the lease at [version], [record]'s write, which landed although it answered [result].
     * The write was sent at [sentAt], so it is as safe as any renewal sent then. It renews the gate
     * only when the gate is open and the renew deadline has not passed; otherwise the gate stays
     * closed until the next renewal that succeeds.
     */
    private fun adopt(
        holding: State.Holding,
        version: ObjectVersion,
        record: LeaseRecord,
        sentAt: Duration,
        result: Result<ReplaceResult>,
    ) {
        log.warn(result.exceptionOrNull()) {
            "Lease renewal by $holder answered ${result.getOrNull() ?: "with a failure"}, but the lease holds its write at " +
                "sequence ${record.sequence}; keeping the lease"
        }
        when {
            holding.granted && !overdue(holding, sentAt) -> renewed(holding, version, record, sentAt)
            else -> {
                if (holding.granted) revokeOverdue(holding)
                state = State.Holding(version, record, sentAt, granted = false)
            }
        }
    }

    /** Moves the gate to a renewal that landed. If the gate write fails, the lease is kept with the gate closed. */
    private fun renewed(
        holding: State.Holding,
        version: ObjectVersion,
        record: LeaseRecord,
        sentAt: Duration,
    ) {
        val granted =
            attempt { gate.grant(record.renewedAt.plusMillis(timing.renewDeadline.inWholeMilliseconds)) }
                .onFailure { e ->
                    log.error(e) { "Lease renewed by $holder, but its gate could not be written; stopping compactors until it can" }
                    closeGate()
                }.isSuccess
        if (granted && !holding.granted) {
            log.info { "Lease renewed again by $holder at sequence ${record.sequence}; compactors may run" }
        }
        state = State.Holding(version, record, sentAt, granted)
    }

    private fun watch(current: State): Duration {
        val lease =
            attempt { objects.read(LeaseRecord.KEY) }
                .getOrElse { e ->
                    log.warn(e) { "Lease read by $holder failed" }
                    return timing.standbyPoll
                }
        // Taken after the read returns: the version it saw may have been written while the read was
        // in flight, and the TTL must not count from before that write.
        val now = clock.monotonic()
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
            standby.version == lease.version && now - standby.seenSince >= timing.ttl -> takeOver(lease, now, standby.retryingAcquire)
            standby.version == lease.version -> Unit
            else -> standBy(standby, lease, now)
        }
        return if (isHolder()) timing.renewInterval else timing.standbyPoll
    }

    /** Starts the TTL on a version this node has not seen. Only a new holder is worth an INFO line. */
    private fun standBy(
        previous: State.Standby,
        lease: VersionedObject,
        now: Duration,
    ) {
        val seenHolder = attempt { LeaseRecord.fromJson(lease.content).holder }.getOrNull()
        when {
            previous.version == null || seenHolder != previous.seenHolder ->
                log.info { "Lease agent $holder standing by; lease is ${lease.content}" }
            else -> log.debug { "Lease agent $holder saw a renewal; lease is ${lease.content}" }
        }
        state = State.Standby(lease.version, now, seenHolder)
    }

    private fun create(now: Duration) {
        val record = LeaseRecord(holder, 0, timing.ttl.inWholeSeconds, clock.wall())
        attempt { objects.createIfAbsent(LeaseRecord.KEY, record.toJson()) }
            .onSuccess { result ->
                when (result) {
                    is CreateResult.Created -> acquire(result.version, record, now, "created")
                    CreateResult.AlreadyExists -> {
                        log.info { "Lease agent $holder lost the race to create the lease; standing by" }
                        state = State.Standby(null, now)
                    }
                }
            }.onFailure { e -> log.warn(e) { "Lease create by $holder failed" } }
    }

    private fun takeOver(
        expired: VersionedObject,
        now: Duration,
        retryingAcquire: Boolean,
    ) {
        val previous =
            attempt { LeaseRecord.fromJson(expired.content) }
                .getOrElse { e ->
                    when (expired.version) {
                        unparsedVersion -> log.debug { "Lease agent $holder still cannot parse lease version ${expired.version.token}" }
                        else ->
                            log.error(e) {
                                "Lease agent $holder cannot parse the lease body, so it will not take it over; " +
                                    "delete ${LeaseRecord.KEY} by hand to recover: ${expired.content}"
                            }
                    }
                    unparsedVersion = expired.version
                    closeGate()
                    return
                }
        val sentAt = clock.monotonic()
        val record = previous.next(holder, timing.ttl.inWholeSeconds, clock.wall())
        val how =
            when {
                retryingAcquire -> "retrying after a failed acquire, ${timing.ttl * 2} after it"
                else -> "taken over from ${previous.holder} after ${timing.ttl} unchanged"
            }
        attempt { objects.replaceIfVersionMatches(LeaseRecord.KEY, record.toJson(), expired.version) }
            .onSuccess { result ->
                when (result) {
                    is ReplaceResult.Replaced -> acquire(result.version, record, sentAt, how)
                    ReplaceResult.VersionMismatch -> {
                        log.info { "Lease agent $holder lost the takeover race; standing by" }
                        state = State.Standby(null, now)
                    }
                }
            }.onFailure { e -> log.warn(e) { "Lease takeover by $holder failed" } }
    }

    /**
     * Wipes the scratch, then opens the gate. If either fails, the gate stays closed and the node
     * stands by on [version], the lease it wrote, with its TTL starting one TTL from now. No one
     * renews that version, so a healthy standby, which saw it within one standby poll of the write,
     * takes it over a TTL later, before this node can. A node alone tries again after two TTLs.
     */
    private fun acquire(
        version: ObjectVersion,
        record: LeaseRecord,
        sentAt: Duration,
        how: String,
    ) {
        log.info { "Lease acquired by $holder at sequence ${record.sequence} ($how); wiping scratch, then starting compactors" }
        // Once this node has held a lease, a body it could not parse before is news again: the same
        // body written later has the same ETag, and it is logged at ERROR once more.
        unparsedVersion = null
        attempt {
            wipeScratch()
            // The first window counts from the record's write time, before the wipe, so a slow wipe
            // shortens only this window; the first renewal comes one renew interval after the write.
            gate.grant(record.renewedAt.plusMillis(timing.renewDeadline.inWholeMilliseconds))
        }.onSuccess {
            state = State.Holding(version, record, sentAt, granted = true)
        }.onFailure { e ->
            log.error(e) {
                "Lease agent $holder could not wipe its scratch or open its gate; compactors stay stopped. " +
                    "Backing off ${timing.ttl} extra so a healthy standby takes the lease first; " +
                    "retrying in ${timing.ttl * 2} at the earliest"
            }
            closeGate()
            state = State.Standby(version, clock.monotonic() + timing.ttl, holder, retryingAcquire = true)
        }
    }

    /** Revokes the gate. A revoke that fails is logged; the gate's own expiry still stops the compactors. */
    private fun closeGate() {
        attempt { gate.revoke() }.onFailure { e -> log.error(e) { "Lease agent $holder could not revoke its gate" } }
    }

    /**
     * Runs [block] and answers any [Exception] it throws as a failure, except an
     * [InterruptedException], which propagates with the interrupt flag set. A JVM [Error] is not
     * caught.
     */
    private inline fun <T> attempt(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}
