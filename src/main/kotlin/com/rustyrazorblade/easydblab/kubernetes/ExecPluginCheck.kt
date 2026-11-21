package com.rustyrazorblade.easydblab.kubernetes

import io.fabric8.kubernetes.api.model.Config
import java.io.File
import java.nio.file.Path

/**
 * Checks, before any client is built, that the exec auth plugin of a kubeconfig context's user can
 * be found.
 *
 * Fabric8 raises nothing for a missing plugin: it runs the command through `sh -c`, logs a warning
 * when it exits 127, and sends the request with no credential. The user would then see an
 * unexplained 401 or an anonymous 403. The plugin is looked up as Fabric8 runs it: a bare name on
 * `PATH`, a command with a path separator against the kubeconfig's directory when relative. Either
 * way it must be a regular file with the execute bit, as `sh -c` needs.
 *
 * @param path the `PATH` to search, the process's own by default
 */
class ExecPluginCheck(
    // The JVM's own PATH, because Fabric8's `sh -c` inherits it and resolves a bare name with it.
    private val path: String? = System.getenv("PATH"),
) {
    /**
     * Throws [ExecPluginNotFoundException] when [context]'s user in [config], the parsed form of the
     * kubeconfig at [kubeconfig], runs an exec plugin that cannot be found. The caller passes the
     * kubeconfig it has already parsed, so the file is not read again.
     */
    fun check(
        kubeconfig: Path,
        config: Config,
        context: String,
    ) {
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
        val searched = if (command.isPathStyle()) resolveRelative(kubeconfig, command) else path.orEmpty()
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
        if (command.isPathStyle()) {
            File(searched).isRunnable()
        } else {
            searched
                .split(File.pathSeparator)
                .filter { it.isNotEmpty() }
                .any { dir -> File(dir, command).isRunnable() }
        }

    private fun File.isRunnable(): Boolean = isFile && canExecute()
}

/** True when an exec command names a path rather than a bare name looked up on `PATH`. */
internal fun String.isPathStyle(): Boolean = contains(File.separatorChar)
