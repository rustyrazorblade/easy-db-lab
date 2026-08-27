package com.rustyrazorblade.easydblab.profiling

import com.rustyrazorblade.easydblab.Constants
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/**
 * What the node's reconciler observed and did on its last pass — rewritten in full at the end of
 * every pass, and read by `cassandra profile status`.
 *
 * Nothing here is authoritative for liveness. The reconciler asks the live JVM
 * (`asprof status <pid>`) and discards any record whose [pid] differs from the current Cassandra
 * process, precisely because a stored record outlives the JVM it describes.
 *
 * Every field carries a default so a document written by an older reconciler still parses — which
 * also means a field renamed on one side of this contract fails *silently*, the reader simply
 * getting the default. [PROFILING_EFFECTIVE_STATE_KEYS] is what stops that, asserted against the
 * reconciler's own output in `edl-profiling-reconcile.test.sh`.
 */
@Serializable
data class ProfilingEffectiveState(
    val running: Boolean = false,
    val desiredEnabled: Boolean = false,
    val pid: Long = 0,
    val args: List<String> = emptyList(),
    val loopInterval: String = "",
    val retentionMinutes: Int = 0,
    val maxBytes: Long = 0,
    val pyroscopeUrl: String = "",
    val clusterName: String = "",
    val startedAt: Long = 0,
    val chunksPending: Int = 0,
    val chunksShipped: Long = 0,
    val chunksRejected: Long = 0,
    val shipFailures: Long = 0,
    val prunedForAge: Long = 0,
    val prunedForSize: Long = 0,
    val prunedUnshipped: Long = 0,
    val bytesOnDisk: Long = 0,
    val lastError: String = "",
    val attachFailures: Long = 0,
    val lastAttachError: String = "",
    /**
     * The node's last pass wanted to attach, detach or replace a session and declined, because the
     * database process was not yet ready to be signalled.
     *
     * async-profiler attaches with jattach, which signals SIGQUIT; a process that has not installed
     * a handler for it yet takes that signal's default disposition and dies. So the reconciler
     * waits, and this is how it says so. It is a normal state for a pass or two after any node
     * start — not a failure — and it is reported separately for exactly that reason.
     */
    val attachDeferred: Boolean = false,
    val configError: String = "",
    val updatedAt: Long = 0,
) {
    /**
     * True when the operator asked for profiling and the node has nothing attached.
     *
     * This is the state a failing attach leaves behind, and it is deliberately distinguishable from
     * a deliberate stop: both have `running = false`, and reporting them the same way sends an
     * operator whose profiler has been silently dead for hours looking in the wrong place.
     *
     * A node waiting for its database to finish starting is excluded: it also has
     * `desiredEnabled && !running`, and calling that a failed attach reports every node restart as
     * a fault while telling the operator nothing they can act on.
     */
    val attachFailed: Boolean get() = desiredEnabled && !running && !attachDeferred

    /**
     * True when the node's reconciler could not read its desired-state document on that pass.
     *
     * The pass still ships, prunes and reports — it simply declines to attach or detach anything on
     * the strength of a document it cannot read. That distinction is what [configError] exists to
     * carry: without it, `status` blamed the reconcile timer for a corrupt configuration file.
     */
    val configUnreadable: Boolean get() = configError.isNotEmpty()
}

/**
 * Every key the reconciler writes, in the order it writes them.
 *
 * The two tiers never share a process, so this is how the contract is pinned: the bash tier asserts
 * the reconciler's document has exactly these keys, and [ProfilingEffectiveState] parses a document
 * built from exactly this list. Renaming a field on either side now breaks a test instead of
 * silently returning a default — which for `updatedAt` alone disabled the staleness banner and the
 * `Event.Profiling.StateStale` event for every node.
 */
val PROFILING_EFFECTIVE_STATE_KEYS =
    listOf(
        "running",
        "desiredEnabled",
        "pid",
        "args",
        "loopInterval",
        "retentionMinutes",
        "maxBytes",
        "pyroscopeUrl",
        "clusterName",
        "startedAt",
        "chunksPending",
        "chunksShipped",
        "chunksRejected",
        "shipFailures",
        "prunedForAge",
        "prunedForSize",
        "prunedUnshipped",
        "bytesOnDisk",
        "lastError",
        "attachFailures",
        "lastAttachError",
        "attachDeferred",
        "configError",
        "updatedAt",
    )

/**
 * Parses an effective-state document.
 *
 * Tolerant by design: the reconciler rewrites this file on a timer while `status` may read it at any
 * instant, and an unclean shutdown can leave it truncated. A node whose state cannot be read is
 * reported as unknown — it is never a reason to fail the command.
 *
 * @param source what was being read, for the diagnostic logged when it cannot be parsed.
 * @return the parsed state, or null if the document is empty, truncated, or malformed.
 */
fun parseProfilingEffectiveState(
    document: String,
    source: String = "",
): ProfilingEffectiveState? = decodeProfilingDocumentOrNull(document, ProfilingEffectiveState.serializer(), source)

/**
 * How recently the node's reconciler left the document behind.
 *
 * Everything in [ProfilingEffectiveState] is a snapshot of one pass, not a live reading. If the
 * reconcile timer is masked, disabled, or its oneshot is being killed at `TimeoutStartSec`, the
 * document simply stops being rewritten — and every field in it keeps reporting the last healthy
 * pass. `attached: yes` stays `yes`, and the session age, computed against the current clock, keeps
 * growing convincingly while nothing is running.
 *
 * [ProfilingEffectiveState.updatedAt] is written every pass precisely so that case is detectable.
 */
sealed interface ProfilingFreshness {
    /** The document carries no timestamp, which means an older reconciler wrote it. */
    data object Unknown : ProfilingFreshness

    /** Written recently enough that the pass it describes is the current one. */
    data class Current(
        val age: Duration,
    ) : ProfilingFreshness

    /** Older than any healthy reconcile interval: the reconciler is not running. */
    data class Stale(
        val age: Duration,
    ) : ProfilingFreshness
}

/**
 * Reads [ProfilingEffectiveState.updatedAt] against [now].
 *
 * A node whose clock runs ahead of ours produces a negative age. That is clock skew, not a reason to
 * report the node as anything other than current, so it is clamped rather than surfaced.
 */
fun profilingFreshness(
    state: ProfilingEffectiveState,
    now: Instant,
): ProfilingFreshness {
    if (state.updatedAt <= 0) return ProfilingFreshness.Unknown
    val age = Duration.between(Instant.ofEpochSecond(state.updatedAt), now)
    return when {
        age.isNegative -> ProfilingFreshness.Current(Duration.ZERO)
        age.seconds >= Constants.Profiling.STALE_AFTER_SECONDS -> ProfilingFreshness.Stale(age)
        else -> ProfilingFreshness.Current(age)
    }
}
