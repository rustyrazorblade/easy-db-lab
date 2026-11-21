package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterState
import java.io.File

/**
 * Kotlin a kit names in `kit.yaml` `contributors:`, for work a kit script cannot do itself, such as
 * resolving a Cassandra version with the CLI's own resolver. The kit runner calls it before the
 * steps of `start` and of every declared command, never before `stop` or `uninstall`, and the kit
 * engine knows nothing of what it computes.
 *
 * Every failure is thrown before any step runs, and its message is shown as the command's error.
 */
interface KitVariableContributor {
    /** The name a kit lists under `contributors:`. */
    val name: String

    /** The extra variables and files for one command of the kit in [kitDir], given its resolved [args]. */
    fun contribute(
        args: Map<String, String>,
        state: ClusterState,
        kitDir: File,
    ): KitContribution
}

/**
 * What a [KitVariableContributor] adds to one command: [variables] in every step's environment
 * (under the cluster state variables, which always win) and [files], by path relative to the kit
 * directory, written before the first step.
 */
data class KitContribution(
    val variables: Map<String, String> = emptyMap(),
    val files: Map<String, String> = emptyMap(),
) {
    init {
        val escaping = files.keys.firstOrNull { it.isBlank() || File(it).isAbsolute || it.split('/', '\\').contains("..") }
        require(escaping == null) { "A contributed file must be a path inside the kit directory, not '$escaping'" }
    }
}
