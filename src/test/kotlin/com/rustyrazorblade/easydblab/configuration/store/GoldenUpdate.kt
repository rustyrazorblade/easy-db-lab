package com.rustyrazorblade.easydblab.configuration.store

/**
 * Whether a golden test rewrites its golden files, read from the environment.
 *
 * `UPDATE_GOLDEN=true` rewrites them, which is the only way a golden changes. A CI run must only
 * compare: a rewrite there would make every golden pass against whatever the code now renders, so
 * the two together are refused rather than ignored.
 */
object GoldenUpdate {
    const val UPDATE_VARIABLE = "UPDATE_GOLDEN"
    const val CI_VARIABLE = "CI"

    /**
     * True when [env] asks for a rewrite.
     *
     * @throws IllegalStateException when [env] asks for a rewrite in CI.
     */
    fun requested(env: Map<String, String>): Boolean {
        val update = env[UPDATE_VARIABLE] == "true"
        check(!(update && !env[CI_VARIABLE].isNullOrEmpty())) {
            "$UPDATE_VARIABLE=true rewrites the golden files; it must never be set in CI ($CI_VARIABLE is set)."
        }
        return update
    }
}
