package com.rustyrazorblade.easydblab.containers

/**
 * Why a `packer build` failed, read from Packer's debug log.
 *
 * The distinction that matters is whether the build lost its SSH connection to the builder. That
 * happens when the stream under SSH dies: under the `ssm` transport, a Session Manager session that
 * ends (`unexpected EOF`) or goes silent (the read/write timeout fires). Such a build failed through
 * no fault of its scripts, so it may run again on a new builder. Every other failure (a script that
 * exited non-zero, an AWS error, a bad template) would fail the same way again and is final.
 */
sealed interface PackerFailure {
    /** The build's own error, without terminal colours. */
    val reason: String

    /** The SSH session to the builder dropped while a provisioner ran. */
    data class SessionDropped(
        override val reason: String,
    ) : PackerFailure

    /** Any failure that is not a dropped session. */
    data class BuildError(
        override val reason: String,
    ) : PackerFailure

    companion object {
        /** The reason given when the log holds no `Build '...' errored` line. */
        const val NO_ERROR_RECORDED = "Packer recorded no build error; see its log"

        private val ansiEscape = Regex("\u001B\\[[0-9;]*m")
        private val buildErrored = Regex("""Build '[^']+' errored after [^:]+: (.*)$""")

        /**
         * Error texts that mean the SSH stream ended or stalled. The shell provisioner reports a
         * session that ended mid-script as "Script disconnected unexpectedly" (or "Disconnect while
         * removing temporary script"); an upload or a command start reports the raw transport error.
         */
        private val sessionLost =
            listOf(
                "script disconnected unexpectedly",
                "disconnect while removing temporary script",
                "i/o timeout",
                "unexpected eof",
                "connection reset by peer",
                "broken pipe",
                "use of closed network connection",
                "handshake failed",
            )

        /**
         * A refused connection to the local end of the Session Manager port forward: the plugin's
         * listener on 127.0.0.1 went away with its session. A refused connection anywhere else is
         * not the SSH stream's.
         */
        private val localForwardRefused = Regex("""127\.0\.0\.1:\d+: connect: connection refused""")

        /** A line Packer's logger wrote itself, as opposed to the continuation of a multi-line message. */
        private val timestamped = Regex("""^\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2} """)

        /**
         * Classifies a failed build from its debug [log], by the last `Build '...' errored` line. The
         * reason keeps the lines that continue that error (a wrapped AWS error's `caused by:` lines),
         * up to the next blank or timestamped line.
         */
        fun fromLog(log: String): PackerFailure {
            val lines = log.lines().map { it.replace(ansiEscape, "").trimEnd() }
            val last = lines.indexOfLast { buildErrored.containsMatchIn(it) }
            if (last < 0) return BuildError(NO_ERROR_RECORDED)
            val first =
                buildErrored
                    .find(lines[last])
                    ?.groupValues
                    ?.get(1)
                    .orEmpty()
            val continuation = lines.drop(last + 1).takeWhile { it.isNotBlank() && !timestamped.containsMatchIn(it) }
            val reason = (listOf(first) + continuation).joinToString("\n")
            return when {
                isSessionLost(reason) -> SessionDropped(reason)
                else -> BuildError(reason)
            }
        }

        /**
         * Marks of an AWS API call that failed: the SDK v1 `RequestError` and the SDK v2
         * `operation error`. Their transport words (`i/o timeout`, `connection reset`) come from the
         * API connection, not the SSH session, so such a failure is a build error.
         */
        private val awsApiCall = listOf("requesterror", "operation error")

        private fun isSessionLost(reason: String): Boolean {
            val lower = reason.lowercase()
            return when {
                awsApiCall.any { it in lower } -> false
                lower == "eof" || lower.endsWith(": eof") -> true
                localForwardRefused.containsMatchIn(lower) -> true
                else -> sessionLost.any { it in lower }
            }
        }
    }
}
