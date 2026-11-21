package com.rustyrazorblade.easydblab.kubernetes

import java.io.File
import java.nio.file.Path

/**
 * Checks, before any client is built, that the exec auth plugin of a kubeconfig context's user can
 * be found.
 *
 * Fabric8 raises nothing for a missing plugin: it runs the command through `sh -c`, logs a warning
 * when it exits 127, and sends the request with no credential. The user would then see an
 * unexplained 401 or an anonymous 403. The plugin is looked up as Fabric8 runs it: a bare name on
 * `PATH`, a command with a path separator against the kubeconfig's directory when relative.
 *
 * @param path the `PATH` to search, the process's own by default
 */
class ExecPluginCheck(
    private val path: String? = System.getenv("PATH"),
) {
    /** Throws [ExecPluginNotFoundException] when [context]'s user runs an exec plugin that cannot be found. */
    fun check(
        kubeconfig: Path,
        context: String,
    ) {
        val config = parseKubeconfig(kubeconfig)
        val userName =
            config.contexts
                .orEmpty()
                .firstOrNull { it.name == context }
                ?.context
                ?.user ?: return
        val exec =
            config.users
                .orEmpty()
                .firstOrNull { it.name == userName }
                ?.user
                ?.exec ?: return
        val command = exec.command?.takeIf { it.isNotBlank() } ?: return
        val searched = if (command.contains(File.separatorChar)) resolveRelative(kubeconfig, command) else path.orEmpty()
        if (!found(command, searched)) {
            throw ExecPluginNotFoundException(command, userName, context, searched, exec.installHint?.takeIf { it.isNotBlank() })
        }
    }

    private fun resolveRelative(
        kubeconfig: Path,
        command: String,
    ): String {
        val file = File(command)
        return (if (file.isAbsolute) file else File(kubeconfig.toAbsolutePath().parent.toFile(), command)).canonicalPath
    }

    private fun found(
        command: String,
        searched: String,
    ): Boolean =
        if (command.contains(File.separatorChar)) {
            File(searched).canExecute()
        } else {
            searched
                .split(File.pathSeparator)
                .filter { it.isNotEmpty() }
                .any { dir -> File(dir, command).let { it.isFile && it.canExecute() } }
        }
}

/**
 * A kubeconfig user's exec auth plugin that cannot be found, so no request would carry a credential.
 *
 * @property plugin the exec command as the kubeconfig names it
 * @property user the kubeconfig user that runs it
 * @property context the context being loaded
 * @property searched the `PATH` searched for a bare name, or the resolved file for a path
 * @property installHint the kubeconfig's `installHint`, when it has one
 */
class ExecPluginNotFoundException(
    val plugin: String,
    val user: String,
    val context: String,
    val searched: String,
    val installHint: String?,
) : IllegalStateException(
        "The exec auth plugin '$plugin' of kubeconfig user '$user' (context '$context') was not found " +
            (if (plugin.contains(File.separatorChar)) "at $searched" else "on PATH: $searched"),
    )
