package com.rustyrazorblade.easydblab.profiling

import com.rustyrazorblade.easydblab.Constants

/**
 * A rotation interval: a whole number of seconds, or a number with an `s`, `m` or `h` unit.
 *
 * Deliberately narrower than what `asprof --loop` itself takes. async-profiler also accepts an
 * `hh:mm:ss` time of day, which rotates once a day at that time — see [requireProfilingLoopInterval]
 * for why that form is refused here rather than passed through.
 */
private val LOOP_INTERVAL_FORM = Regex("^([1-9][0-9]*)([smh]?)$", RegexOption.IGNORE_CASE)

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L

/**
 * The rotation interval in seconds, which is what every window derived from it is measured in.
 *
 * @throws IllegalArgumentException if the interval is not one this tool accepts. Callers validate
 *   with [requireProfilingLoopInterval] first; this is the same rule, not a second one.
 */
fun profilingLoopSeconds(loopInterval: String): Long {
    val match =
        requireNotNull(LOOP_INTERVAL_FORM.matchEntire(loopInterval)) {
            "Not a rotation interval: \"$loopInterval\"."
        }
    val number = match.groupValues[1].toLong()
    return when (match.groupValues[2].lowercase()) {
        "m" -> number * SECONDS_PER_MINUTE
        "h" -> number * SECONDS_PER_HOUR
        else -> number
    }
}

/**
 * Rejects retention and byte bounds the node cannot act on, at the CLI rather than after an SSH
 * round-trip.
 *
 * The node's contract is the stricter of the two and its failures are silent, which is why this
 * exists. The reconciler requires both numbers to match `^[0-9]+$` and refuses to act on the desired
 * state otherwise, so a negative bound would report "profiling enabled" on every node and then log
 * `config_unreadable` every pass while nothing ever attaches. Zero passes the node's own validation
 * and is worse: it puts the prune cutoff at the current instant, so each pass deletes every chunk
 * the previous one collected — profiling that can never produce a profile.
 *
 * ## Why the retention window is checked against the rotation interval
 *
 * The two flags were validated independently, and they interact. A chunk becomes eligible to ship
 * only once it is older than one rotation plus [Constants.Profiling.SHIP_GRACE_SECONDS], and it is
 * age-pruned once it is older than the retention window — with pruning running after shipping in the
 * same pass. If the window is shorter than the wait, the eligible band is empty and *nothing ever
 * ships*: every chunk is deleted on the pass before the one that would have uploaded it. Nothing
 * fails, no counter moves except the pruning ones, and Pyroscope simply stays empty.
 *
 * The rule adds one reconcile interval on top, because eligibility is only tested once per pass:
 * a chunk that becomes shippable a second after a pass has walked the directory waits a whole
 * interval for the next one, and must still be inside the window when it arrives.
 *
 * This was reachable with no unusual input at all — `--loop 1h` against the DEFAULT 60-minute
 * retention is 3610 seconds of waiting against a 3600-second window.
 *
 * ## Why the rotation interval has a floor
 *
 * The node ships at most [Constants.Profiling.SHIP_MAX_CHUNKS_PER_PASS] chunks per pass and runs a
 * pass every [Constants.Profiling.RECONCILE_INTERVAL_SECONDS], while the profiler produces one chunk
 * per rotation. Below [Constants.Profiling.MIN_LOOP_SECONDS] the arithmetic inverts: `--loop 5s`
 * produces twelve chunks in the sixty seconds a pass ships six of, so the queue grows by six every
 * pass forever and everything aging past the retention window is deleted having never shipped.
 *
 * The budget is not raisable to meet it. Six uploads at the node's 30-second upload timeout is 180
 * seconds inside the unit's 300-second `TimeoutStartSec`; twelve would be 360 and systemd would kill
 * the pass mid-upload instead. So the fast rotation is refused here rather than accepted and lost on
 * the node, where the only symptom is a per-pass truncation warning indistinguishable from a backlog
 * draining normally.
 *
 * @throws IllegalArgumentException naming the offending option, so the message points at the flag
 *   the operator typed rather than at a field name they never saw.
 */
fun requireProfilingBounds(
    retentionMinutes: Int,
    maxBytes: Long,
    loopInterval: String,
) {
    require(retentionMinutes >= Constants.Profiling.MIN_RETENTION_MINUTES) {
        "Refusing to profile: --retention must be at least " +
            "${Constants.Profiling.MIN_RETENTION_MINUTES} minute(s), was $retentionMinutes.\n" +
            "Anything lower prunes each chunk as fast as the node writes it, and a negative value " +
            "makes the node reject the whole request without attaching anything."
    }
    require(maxBytes >= Constants.Profiling.MIN_MAX_BYTES) {
        "Refusing to profile: --max-bytes must be at least " +
            "${Constants.Profiling.MIN_MAX_BYTES} byte(s), was $maxBytes.\n" +
            "A ceiling below one JFR chunk leaves the node deleting every chunk as it lands, so " +
            "profiling runs and produces nothing."
    }

    val loopSeconds = profilingLoopSeconds(loopInterval)
    require(loopSeconds >= Constants.Profiling.MIN_LOOP_SECONDS) {
        "Refusing to profile: --loop $loopInterval rotates faster than the node can ship.\n" +
            "A pass uploads at most ${Constants.Profiling.SHIP_MAX_CHUNKS_PER_PASS} chunks and runs " +
            "every ${Constants.Profiling.RECONCILE_INTERVAL_SECONDS}s, so anything below " +
            "${Constants.Profiling.MIN_LOOP_SECONDS}s produces chunks faster than they can be " +
            "drained. The queue then grows every pass and each chunk is deleted, unshipped, once it " +
            "ages past --retention — with nothing reported as failing.\n" +
            "The upload budget cannot simply be raised: it is what keeps a pass inside the unit's " +
            "start timeout. Use --loop ${Constants.Profiling.MIN_LOOP_SECONDS}s or slower."
    }
    val shippableAfter =
        loopSeconds + Constants.Profiling.SHIP_GRACE_SECONDS + Constants.Profiling.RECONCILE_INTERVAL_SECONDS
    require(retentionMinutes * SECONDS_PER_MINUTE >= shippableAfter) {
        "Refusing to profile: --retention ($retentionMinutes minute(s)) is shorter than the time a " +
            "chunk needs before it can ship at --loop $loopInterval.\n" +
            "A chunk is not complete until one rotation plus " +
            "${Constants.Profiling.SHIP_GRACE_SECONDS}s have passed, and the node only looks every " +
            "${Constants.Profiling.RECONCILE_INTERVAL_SECONDS}s, so it needs at least " +
            "$shippableAfter seconds of retention. A shorter window prunes every chunk before it " +
            "can ship, and Pyroscope stays empty with nothing reported as failing.\n" +
            "Raise --retention or lower --loop."
    }
}

/**
 * Rejects a chunk count that would not name a set of chunks.
 *
 * `--last` reaches the node as `head -n <count>`, where a negative value does not mean "none": GNU
 * `head -n -1` means "all but the last line", so `--last -1` quietly fetches or converts the entire
 * profile directory. Zero is the same shape of surprise from the other side. Neither is reachable by
 * typo alone from the CLI, but both are reachable over MCP, where the value arrives as a number
 * nobody typed.
 *
 * @throws IllegalArgumentException naming the option the caller supplied.
 */
fun requireChunkCount(last: Int) {
    require(last >= 1) {
        "Refusing to run: --last must be at least 1, was $last.\n" +
            "The node selects chunks with `head -n <count>`, where a value below 1 does not mean " +
            "\"none\" — a negative count selects everything but the newest chunks, so the whole " +
            "profile directory would be pulled or converted."
    }
}

/**
 * Rejects a rotation interval this tool cannot act on.
 *
 * Two separate reasons, and both are about silence.
 *
 * The value is not merely handed to `asprof --loop`. It is carried into the node's effective-state
 * document, which `cassandra profile status` parses, so a value that is not a duration at all
 * leaves the node describing a rotation nobody can reason about — including this tool, which sizes
 * both the chunk-completion grace window and the Pyroscope upload window from it.
 *
 * The `hh:mm:ss` form is refused even though async-profiler accepts it, because it is a time of day
 * rather than a duration: it rotates once daily. This tool ships every completed chunk continuously
 * and derives both windows from a fixed interval, so a daily rotation is a configuration that looks
 * entirely valid and produces no usable profiles — the day's single chunk is still open long after
 * retention has pruned it. The message says so, because anyone reaching for `02:30:00` copied it
 * from async-profiler's own documentation and needs to be told what to use instead, not merely
 * refused.
 *
 * Validated here rather than on the node because the value is also settable over MCP and is carried
 * forward by `stop`, so the CLI is the one place every route passes through.
 *
 * @throws IllegalArgumentException naming the option the operator typed.
 */
fun requireProfilingLoopInterval(loopInterval: String) {
    require(LOOP_INTERVAL_FORM.matches(loopInterval)) {
        "Refusing to profile: --loop must be a rotation interval, was \"$loopInterval\".\n" +
            "Use a whole number of seconds (30) or a number with a unit (30s, 5m, 1h).\n" +
            "async-profiler also accepts an hh:mm:ss time of day, which rotates once a day at that " +
            "time. easy-db-lab does not: it ships every completed chunk continuously and sizes the " +
            "completion and upload windows from a fixed interval, so a daily rotation yields no " +
            "usable profiles."
    }
}
