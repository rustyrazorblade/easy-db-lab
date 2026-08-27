package com.rustyrazorblade.easydblab

/** Characters safe to leave unquoted in a POSIX shell word. */
private val SHELL_SAFE = Regex("[a-zA-Z0-9_./:=@%+,-]+")

/**
 * Quotes a string for safe interpolation into a shell command line.
 *
 * Needed wherever a command has to be assembled as a single string before being handed to a remote
 * shell — `remoteOps.executeRemotely` takes a string, not argv, so any operator-supplied value
 * reaching it has to be quoted here or it is a shell injection.
 *
 * Single quotes rather than double, so nothing inside is expanded; an embedded single quote is
 * closed, escaped, and reopened, which is the only correct way to do it in `sh`.
 *
 * Where argv is available, prefer it: the profiling reconciler passes user arguments to `asprof` as
 * a NUL-delimited array precisely so this function is not in that path at all.
 */
fun String.shellQuote(): String =
    when {
        isEmpty() -> "''"
        matches(SHELL_SAFE) -> this
        else -> "'" + replace("'", "'\\''") + "'"
    }
