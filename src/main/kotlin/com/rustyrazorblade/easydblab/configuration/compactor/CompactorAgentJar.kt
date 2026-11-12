package com.rustyrazorblade.easydblab.configuration.compactor

import com.rustyrazorblade.easydblab.Constants

/**
 * The lease agent jar as the control node holds it: at a path named by its SHA-256, so a new jar
 * is a new path in the pod template, and the pod rolls exactly when the jar changes.
 *
 * @property sha256 the jar's SHA-256, as 64 lowercase hex digits.
 * @throws IllegalArgumentException when [sha256] is not 64 lowercase hex digits.
 */
@JvmInline
value class CompactorAgentJar(
    val sha256: String,
) {
    init {
        require(sha256.matches(SHA256)) { "Not a SHA-256: $sha256" }
    }

    /** Where the jar lives on the control node. */
    val hostPath: String get() = "$AGENT_DIR/$sha256.jar"

    companion object {
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        /** The control node's directory of agent jars. */
        const val AGENT_DIR = "${Constants.Compactor.HOST_ROOT}/agent"
    }
}
