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
            )

        /** Classifies a failed build from its debug [log], by the last `Build '...' errored` line. */
        fun fromLog(log: String): PackerFailure {
            val reason =
                log
                    .lineSequence()
                    .map { it.replace(ansiEscape, "").trimEnd() }
                    .mapNotNull { buildErrored.find(it)?.groupValues?.get(1) }
                    .lastOrNull()
                    ?: return BuildError(NO_ERROR_RECORDED)
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
                else -> lower == "eof" || sessionLost.any { it in lower }
            }
        }
    }
}
