package com.rustyrazorblade.easydblab.profiling

import com.rustyrazorblade.easydblab.Constants

/**
 * Renders the `asprof` invocation the node's reconciler builds, for display by `profile status`.
 *
 * The operator needs to see what the tool added to their arguments on their behalf — the reserved
 * output plumbing is invisible otherwise, and "why is my `-o collapsed` not taking effect?" has no
 * answer without it. The user's arguments come first and the tool's last, matching the reconciler.
 *
 * This is display only. The real invocation is argv, built on the node; see
 * `packer/cassandra/bin/edl-profiling-reconcile`.
 */
fun renderAsprofCommandLine(
    userArgs: List<String>,
    loopInterval: String,
    pid: Long,
): String =
    (
        listOf("asprof", "start") + userArgs +
            listOf(
                "-o",
                "jfr",
                "--loop",
                loopInterval,
                "-f",
                "${Constants.Profiling.PROFILE_DIR}/cassandra-%p-%t.jfr",
                pid.toString(),
            )
    ).joinToString(" ")
