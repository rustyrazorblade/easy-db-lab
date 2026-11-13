package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants

/**
 * What one rejected dry-run probe Pod says about the capability it tests. The API server's status
 * code and message decide it, so the decision is a pure function, kept apart from the client calls.
 *
 * - [Refused]: admission or validation turned the Pod away (HTTP 403 or 422), or a webhook matched it
 *   that does not support dry run (HTTP 400, "admission webhook "<name>" does not support dry run").
 *   The capability is absent; [Refused.note] says why.
 * - [Unexpected]: anything else (a missing namespace, a server error, an unrelated bad request). The
 *   probe cannot attribute it to the tested field, so it fails rather than guess.
 */
sealed interface ProbeRejection {
    /** The capability is absent; [note] carries the API server's reason. */
    data class Refused(
        val note: String,
    ) : ProbeRejection

    /** The rejection says nothing about the capability; [message] is the API server's. */
    data class Unexpected(
        val message: String,
    ) : ProbeRejection

    companion object {
        /** The API server's message when a webhook with side effects refuses a dry-run request. */
        private val DRY_RUN_UNSUPPORTED = Regex("admission webhook \"([^\"]+)\" does not support dry run")

        /** Classifies a rejection with HTTP status [code] and the API server's [message]. */
        fun classify(
            code: Int,
            message: String,
        ): ProbeRejection {
            val webhook = DRY_RUN_UNSUPPORTED.find(message)?.groupValues?.get(1)
            return when {
                code == Constants.HttpStatus.FORBIDDEN || code == Constants.Capabilities.HTTP_UNPROCESSABLE -> Refused(message)
                code == Constants.HttpStatus.BAD_REQUEST && webhook != null ->
                    Refused("admission webhook $webhook does not support dry run, so it cannot be probed")
                else -> Unexpected(message)
            }
        }
    }
}
