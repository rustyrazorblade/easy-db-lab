package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import java.io.File

/**
 * The single source of truth for which working-directory subdirectories are installed kits.
 *
 * A directory qualifies if, and only if, it contains a `kit.yaml` descriptor. Every kit
 * installed through `kit install` carries one, so the rule is an invariant rather than a
 * heuristic. Kits registered in the tool in code are not discovered from the filesystem and
 * never reach this class.
 *
 * A `bin/` directory does **not** qualify a directory. Cassandra checkouts, virtualenvs, and
 * unrelated project trees all hold one, and treating that as proof of a kit registered them as
 * top-level CLI commands, where a directory name could shadow a core command.
 *
 * Both dynamic subcommand registration and the `=== KITS ===` section of `status` read this
 * class, so the two share one discovery rule. Registration can still drop a discovered kit
 * afterwards — on a name collision with a core command, or when building its command group
 * fails — so the listings are not guaranteed identical.
 */
class WorkspaceKitScanner(
    private val context: Context,
) {
    /** Returns true when [dir] is a directory holding a `kit.yaml` file. */
    fun isInstalledKit(dir: File): Boolean = dir.isDirectory && File(dir, Constants.Kit.CONFIG_FILE).isFile

    /**
     * Returns every installed kit directory in the working directory, in filesystem order.
     * Callers that display the result sort it themselves.
     */
    fun discover(): List<File> =
        context.workingDirectory
            .listFiles()
            .orEmpty()
            .filter { isInstalledKit(it) }
}
