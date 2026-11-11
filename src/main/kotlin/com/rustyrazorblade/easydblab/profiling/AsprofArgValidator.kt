package com.rustyrazorblade.easydblab.profiling

/**
 * async-profiler CLI flags easy-db-lab reserves for itself.
 *
 * These four concepts — output file, output format, rotation, and session duration — are supplied
 * by the tool because the node-side chunk shipper depends on them: it needs JFR format, at a known
 * path, rotating on a known interval, from a session that does not terminate itself. A user-supplied
 * `-f` would silently redirect chunks somewhere the shipper never looks, leaving Pyroscope empty
 * with no error anywhere; a user-supplied `-d` would end the session, which the reconciler would
 * then restart, thrashing forever.
 *
 * ## Do not add a combined cpu+wall mode
 *
 * It is tempting to "helpfully" offer one recording that captures both CPU and wall-clock samples
 * (`-e cpu --wall 10ms`). **Do not.** Pyroscope's JFR ingest delegates to
 * `github.com/grafana/jfr-parser` (pinned at v0.18.0), whose `pprof/parser.go:56` declares the
 * sample value buffer once, outside the event loop:
 *
 * ```go
 * var values = [2]int64{1, 0}
 * ```
 *
 * `T_WALL_CLOCK_SAMPLE` assigns `values[0] = parser.WallClockSample.Samples` and never restores it,
 * and `T_EXECUTION_SAMPLE` passes `values[:1]` without re-initialising. After the first wall sample,
 * every subsequent CPU sample carries that wall event's batch count as its weight. Wall batching
 * coalesces idle threads up to 1000, and Cassandra runs hundreds of mostly-idle pool threads, so the
 * error reaches three orders of magnitude — silently. The contamination is scoped to one `parse()`
 * call, i.e. one upload, so separate sessions are each clean. Switch modes with stop/start instead.
 *
 * `--nobatch` is not a mitigation either, and is reserved separately — see [CORRUPTING_CLI_FLAGS].
 */
private val RESERVED_CLI_FLAGS =
    setOf("-f", "--file", "-o", "--output", "--loop", "-d", "--duration", "--timeout")

/**
 * async-profiler's wall-clock batching switch, in CLI spelling. Rejected for a different reason from
 * the output-plumbing flags above, so it carries its own message.
 *
 * `--nobatch` does not merely disable coalescing: it changes the emitted event type.
 * `wallClock.cpp:162` selects `WALL_LEGACY` under it, whose signal handler emits `ExecutionEvent`
 * rather than `WallClockEvent`. Wall samples then arrive as `jdk.ExecutionSample` — the same JFR type
 * CPU samples use — and Pyroscope merges them all into `process_cpu`. It causes the failure it
 * appears to prevent.
 *
 * That is the same ground `-o`/`--output` is reserved on: it changes *which JFR event types reach the
 * shipper*. This is not the tool policing async-profiler's parameters; it is the existing
 * output-format rationale applied consistently.
 *
 * Reserved here rather than left to async-profiler because upstream removed the backstop. At
 * async-profiler 4.3 `asprof` had no `--nobatch` in its CLI parser and exited 1; 4.5 adds it to the
 * usage string and the pass-through list, so it now reaches the profiler.
 */
private val CORRUPTING_CLI_FLAGS = setOf("--nobatch")

/**
 * The same switch in agent-option spelling — a bare token inside a comma-separated value, as in
 * `-e wall,nobatch`. Matched under the same flag-position rule as [OUTPUT_FORMAT_TOKENS].
 */
private val CORRUPTING_AGENT_TOKENS = setOf("nobatch")

/**
 * Reserved options in async-profiler's agent-option spelling — the comma-separated `key=value` form
 * `asprof` translates its CLI flags into. `flat` and `traces` appear here because their agent form
 * takes a count (`flat=10`), which sets the output format just as the bare token does.
 */
private val RESERVED_AGENT_KEYS =
    setOf("file", "loop", "timeout", "duration", "output", "flat", "traces")

/**
 * Bare words that set async-profiler's output format with no flag at all. Reserved for the same
 * reason as `-o`: the shipper needs JFR.
 */
private val OUTPUT_FORMAT_TOKENS =
    setOf("jfr", "collapsed", "flamegraph", "tree", "otlp", "flat", "traces", "text", "html")

/**
 * Every bare word that is rejected in flag position, whatever the reason. The two sets are kept apart
 * above only so a rejection can explain itself; the matching rule is identical.
 */
private val BARE_TOKENS = OUTPUT_FORMAT_TOKENS + CORRUPTING_AGENT_TOKENS

/** Characters that would break the node-side NUL-delimited argv contract. */
private val FORBIDDEN_CHARS = setOf('\n', '\r', '\u0000')

/**
 * Rejects user-supplied async-profiler arguments that change what reaches the node-side JFR shipper,
 * so the failure surfaces at the CLI rather than as a silently empty or silently wrong Pyroscope.
 *
 * Two grounds, one rule: the output plumbing easy-db-lab owns ([RESERVED_CLI_FLAGS]), and the
 * batching switch that changes which JFR event types are emitted ([CORRUPTING_CLI_FLAGS]). Each
 * carries its own rejection message, because the fixes differ.
 *
 * This is deliberately *not* a whitelist. Anything outside the reserved set passes through
 * untouched, including options added by async-profiler releases this code has never heard of — the
 * tool does not model async-profiler's option surface.
 *
 * Known limit, accepted: a bare output-format word is only rejected in "flag position" (the
 * preceding token does not start with `-`), because distinguishing perfectly would require knowing
 * which flags consume a value, i.e. enumerating the option surface. So `--include collapsed` is
 * correctly allowed and a hypothetical `--quiet collapsed` would be wrongly allowed. The tool
 * appending `-o jfr` last is the backstop.
 */
class AsprofArgValidator {
    /**
     * @return the first offending argument, or null if every argument is acceptable.
     */
    fun validate(args: List<String>): String? = args.withIndex().firstOrNull { (i, arg) -> isReserved(arg, args, i) }?.value

    /**
     * Renders the operator-facing explanation for a rejected argument.
     *
     * A control character gets its own message. It is rejected for a completely different reason
     * from a reserved flag — the node reads arguments NUL-delimited and expands them as argv — and
     * telling someone their newline "is reserved by easy-db-lab" and pointing them at `--loop`
     * describes neither the problem nor anything they can do about it.
     */
    fun rejectionMessage(argument: String): String =
        if (argument.any { it in FORBIDDEN_CHARS }) {
            "Refusing to profile: an async-profiler argument contains a newline or a NUL byte.\n" +
                "Arguments travel to the node as a NUL-delimited list and are expanded as argv " +
                "without being\nre-parsed, so those two characters cannot be represented. Remove " +
                "them from the argument."
        } else if (isCorrupting(argument)) {
            "Refusing to profile: '$argument' turns off wall-clock batching, which corrupts the " +
                "CPU profile.\nUnder --nobatch async-profiler emits wall-clock samples as " +
                "jdk.ExecutionSample, the JFR event\ntype CPU samples already use. The two become " +
                "indistinguishable, and Pyroscope merges them\nall into process_cpu. easy-db-lab " +
                "reserves it for the same reason it reserves -o: it changes\nwhich JFR event types " +
                "reach the shipper. Profile one mode at a time, and switch with stop\nthen start."
        } else {
            "Refusing to profile: '$argument' is reserved by easy-db-lab.\n" +
                "The tool supplies async-profiler's output file, output format, rotation interval, and\n" +
                "session duration, because the node-side JFR shipper depends on all four. Use the\n" +
                "command's own --loop option to change the rotation interval."
        }

    private fun isReserved(
        arg: String,
        args: List<String>,
        index: Int,
    ): Boolean {
        if (arg.any { it in FORBIDDEN_CHARS }) return true
        if (arg in RESERVED_CLI_FLAGS || arg in CORRUPTING_CLI_FLAGS) return true

        val fragments = arg.split(",")
        return fragments.any { fragment ->
            isReservedFragment(fragment, multiFragment = fragments.size > 1, flagPosition = inFlagPosition(args, index))
        }
    }

    private fun isReservedFragment(
        fragment: String,
        multiFragment: Boolean,
        flagPosition: Boolean,
    ): Boolean =
        if (fragment.contains('=')) {
            val key = fragment.substringBefore('=')
            key in RESERVED_CLI_FLAGS ||
                key in RESERVED_AGENT_KEYS ||
                key in CORRUPTING_CLI_FLAGS ||
                key in CORRUPTING_AGENT_TOKENS
        } else {
            // A bare token is only meaningful as an option when it is not a flag's value. Inside a
            // comma-joined value every fragment is already in agent-option form, where a bare token
            // always names an option.
            fragment in BARE_TOKENS && (multiFragment || flagPosition)
        }

    private fun inFlagPosition(
        args: List<String>,
        index: Int,
    ): Boolean = index == 0 || !args[index - 1].startsWith("-")

    /** Whether an argument carries the batching switch, in either spelling, at any comma position. */
    private fun isCorrupting(argument: String): Boolean =
        argument.split(",").any { fragment ->
            val token = fragment.substringBefore('=')
            token in CORRUPTING_CLI_FLAGS || token in CORRUPTING_AGENT_TOKENS
        }
}
