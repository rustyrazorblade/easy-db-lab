package com.rustyrazorblade.easydblab.commands.cassandra.profiler

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.profiling.ProfilingEffectiveState
import com.rustyrazorblade.easydblab.profiling.ProfilingFreshness
import com.rustyrazorblade.easydblab.profiling.profilingFreshness
import com.rustyrazorblade.easydblab.profiling.renderAsprofCommandLine
import picocli.CommandLine.Command
import java.time.Duration
import java.time.Instant

/**
 * Reports what each targeted Cassandra node is actually profiling.
 *
 * A read-only display command, so the report itself is printed rather than modelled as an event —
 * nothing happened, this is current state. Failures the node reported *are* domain facts an external
 * subscriber cares about, so those are emitted as typed [Event.Profiling] events.
 *
 * This is also the read side of the pull-based failure reporting: the reconciler runs on the node
 * hours after the CLI exited and cannot push an event, so it writes journald lines and counters, and
 * this command turns them into typed events when someone asks.
 */
@McpCommand
@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "status",
    description = ["Report profiling state for each Cassandra node"],
)
class ProfilingStatus : ProfilingHostCommand() {
    override fun execute() {
        // One reading of the clock for the whole report, so two nodes rendered a second apart are
        // still judged against the same instant.
        val now = Instant.now()
        forEachTarget { host ->
            val state = profilingService.readEffectiveState(host)
            println(render(host, state, now))
            state?.let { surfaceFailures(host, it, now) }
        }
    }

    /**
     * The whole observable contract of this command.
     *
     * Internal rather than private so its branches can be asserted directly — a node that has never
     * reconciled, a node with a session attached, and a node that wants one and does not have it are
     * three different reports, and the last two used to render identically.
     */
    internal fun render(
        host: Host,
        state: ProfilingEffectiveState?,
        now: Instant = Instant.now(),
    ): String {
        if (state == null) {
            return """
                ${host.alias}
                  state:    unknown (no readable profiling state on this node yet)
                """.trimIndent()
        }

        val commandLine =
            when {
                state.running -> renderAsprofCommandLine(state.args, state.loopInterval, state.pid)
                state.attachDeferred -> "(waiting for the database to become ready)"
                else -> "(no session attached)"
            }

        val freshness = profilingFreshness(state, now)

        // Desired and actual are reported as two separate lines on purpose. A single "enabled:
        // false" cannot tell an operator whether they turned profiling off or whether the node has
        // been failing to attach every 60s since the cluster came up.
        //
        // Assembled rather than written as one template: the stale banner is two lines, and
        // interpolating a multi-line value into a raw string would take part in trimIndent's
        // minimum-indent calculation and shift the whole report sideways.
        val details =
            """
            desired:  ${if (state.desiredEnabled) "enabled" else "disabled"}
            attached: ${attached(state)}
            pid:      ${state.pid}
            age:      ${sessionAge(state, now)}
            updated:  ${lastPass(freshness)}
            your args:  ${state.args.joinToString(" ").ifEmpty { "(none)" }}
            full command: $commandLine
            chunks:   ${state.chunksPending} pending, ${state.chunksShipped} shipped, ${state.chunksRejected} rejected
            pruned:   ${state.prunedForAge} for age, ${state.prunedForSize} for size, ${state.prunedUnshipped} never shipped
            on disk:  ${state.bytesOnDisk} bytes
            last ship error: ${state.lastError.ifEmpty { "(none)" }}
            last attach error: ${state.lastAttachError.ifEmpty { "(none)" }}
            """.trimIndent()

        return buildString {
            appendLine(host.alias)
            append(banner(state, freshness))
            append(details.prependIndent("  "))
        }
    }

    /**
     * Says up front what the rest of the report is not.
     *
     * Placed above every other line because an operator reading `attached: yes` has already stopped
     * reading. Two conditions can make the report misleading, and they have opposite answers, so a
     * node with an unreadable configuration never gets the timer-blaming banner: its reconciler is
     * running perfectly, and the file it reads is what is wrong.
     */
    private fun banner(
        state: ProfilingEffectiveState,
        freshness: ProfilingFreshness,
    ): String =
        when {
            state.configUnreadable -> configBanner(state.configError, freshness)
            freshness is ProfilingFreshness.Stale -> staleBanner(freshness)
            state.attachDeferred -> waitingBanner()
            else -> ""
        }

    /**
     * A node that wants to profile and is waiting for its database to be ready to attach to.
     *
     * Below the stale banner on purpose: if the pass that recorded this wait is itself hours old,
     * the wait is not what the operator should act on, and the timer is.
     *
     * It is a banner rather than only a line in the body because the body reads
     * `desired: enabled, attached: no` — which is what a broken node looks like too, and an operator
     * who has just restarted a node should not have to work out which of the two they are seeing.
     */
    private fun waitingBanner(): String =
        "  WAITING:  the database on this node is not yet ready to be attached to, so the node is " +
            "not\n            profiling yet. It attaches on its next pass once the database is up; " +
            "passes run\n            every ${Constants.Profiling.RECONCILE_INTERVAL_SECONDS}s. " +
            "This is normal shortly after a node starts.\n"

    /**
     * A node whose desired state cannot be read.
     *
     * The staleness note is folded in rather than printed as a second banner, because if both are
     * true the pass being old governs the age of this fact too — but the cause named is still the
     * document, not the timer.
     */
    private fun configBanner(
        reason: String,
        freshness: ProfilingFreshness,
    ): String {
        val age =
            when (freshness) {
                is ProfilingFreshness.Stale ->
                    "            That was ${humanize(freshness.age)} ago; passes run every " +
                        "${Constants.Profiling.RECONCILE_INTERVAL_SECONDS}s, so check " +
                        "${Constants.Profiling.TIMER_UNIT} as well.\n"

                else -> ""
            }
        return "  CONFIG:   the node could not read ${Constants.Profiling.DESIRED_STATE_PATH} ($reason).\n" +
            "            It is still shipping and pruning under its last known bounds, but will " +
            "not change\n            what it profiles. Run 'cassandra profile start' to rewrite it.\n" +
            age
    }

    private fun staleBanner(freshness: ProfilingFreshness.Stale): String =
        "  STALE:    last reconcile pass was ${humanize(freshness.age)} ago; the node runs one " +
            "every ${Constants.Profiling.RECONCILE_INTERVAL_SECONDS}s.\n" +
            "            Everything below describes that pass, not the present. " +
            "Check ${Constants.Profiling.TIMER_UNIT} on the node.\n"

    /**
     * Whether a session is attached, and if not, whether that is a wait rather than an absence.
     *
     * The reason belongs on this line and not only in the banner above: this is the line an operator
     * scanning several nodes reads, and "no" alone is the answer a dead profiler gives too.
     */
    private fun attached(state: ProfilingEffectiveState): String =
        when {
            state.running -> "yes"
            state.attachDeferred -> "no (waiting for the database to become ready)"
            else -> "no"
        }

    private fun lastPass(freshness: ProfilingFreshness): String =
        when (freshness) {
            is ProfilingFreshness.Unknown -> "unknown (written by a reconciler that did not record it)"
            is ProfilingFreshness.Current -> "${humanize(freshness.age)} ago"
            is ProfilingFreshness.Stale -> "${humanize(freshness.age)} ago (stale)"
        }

    /** Whole units only: this is a report an operator reads, not a measurement. */
    private fun humanize(age: Duration): String {
        val minute = Constants.Time.SECONDS_PER_MINUTE
        val hour = Constants.Time.SECONDS_PER_HOUR
        val seconds = age.seconds
        return when {
            seconds < minute -> "${seconds}s"
            seconds < hour -> "${seconds / minute}m${seconds % minute}s"
            else -> "${seconds / hour}h${(seconds % hour) / minute}m"
        }
    }

    private fun sessionAge(
        state: ProfilingEffectiveState,
        now: Instant,
    ): String =
        if (!state.running || state.startedAt <= 0) {
            "-"
        } else {
            val age = Duration.between(Instant.ofEpochSecond(state.startedAt), now)
            if (age.isNegative) "-" else humanize(age)
        }

    private fun surfaceFailures(
        host: Host,
        state: ProfilingEffectiveState,
        now: Instant,
    ) {
        val freshness = profilingFreshness(state, now)
        // StateStale asserts that the node's reconciler is not running. When the node has told us
        // its configuration is unreadable, that assertion is simply false — the reconciler wrote
        // this document — so the two are mutually exclusive rather than both fired.
        if (state.configUnreadable) {
            eventBus.emit(Event.Profiling.NodeConfigUnreadable(host = host.alias, reason = state.configError))
        } else if (freshness is ProfilingFreshness.Stale) {
            eventBus.emit(
                Event.Profiling.StateStale(
                    host = host.alias,
                    ageSeconds = freshness.age.seconds,
                    expectedIntervalSeconds = Constants.Profiling.RECONCILE_INTERVAL_SECONDS,
                ),
            )
        }
        if (state.prunedUnshipped > 0) {
            eventBus.emit(Event.Profiling.ChunksLost(host = host.alias, lost = state.prunedUnshipped))
        }
        if (state.shipFailures > 0) {
            eventBus.emit(
                Event.Profiling.ShippingFailed(
                    host = host.alias,
                    reason = state.lastError.ifEmpty { "unknown" },
                    failures = state.shipFailures,
                ),
            )
        }
        if (state.chunksRejected > 0) {
            eventBus.emit(Event.Profiling.ChunksRejected(host = host.alias, rejected = state.chunksRejected))
        }
        // Mutually exclusive with AttachFailed by construction: attachFailed excludes a deferred
        // attach. Both describe a node with profiling enabled and nothing running, and only one of
        // them is a problem — emitting both would make every node restart look like a fault.
        if (state.attachDeferred) {
            eventBus.emit(Event.Profiling.AttachDeferred(host = host.alias))
        }
        if (state.attachFailed) {
            eventBus.emit(Event.Profiling.AttachFailed(host = host.alias, reason = attachFailureReason(state)))
        }
    }

    /**
     * Why nothing is attached, in the operator's terms.
     *
     * The reconciler's captured message is the most specific answer; a missing process is the next
     * most specific and is common enough (a stopped database) to be worth naming.
     */
    private fun attachFailureReason(state: ProfilingEffectiveState): String =
        when {
            state.lastAttachError.isNotEmpty() -> state.lastAttachError
            state.pid == 0L -> "no Cassandra process on the node to attach to"
            else -> "unknown"
        }
}
