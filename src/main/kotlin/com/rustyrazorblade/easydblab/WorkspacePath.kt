package com.rustyrazorblade.easydblab

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Resolves [name] inside [workspace], never against the JVM current directory. This is the one
 * guard for workspace file names; `Context.workspaceFile` and the services that are handed the
 * workspace directory both use it.
 *
 * Fails fast unless the result is strictly inside the workspace, checked twice:
 * - by name: a blank or absolute name, or one whose `.` and `..` segments reach the workspace
 *   itself or above it, is refused;
 * - on disk: the directory that holds the result, with its links followed, must be inside the
 *   workspace's real path, so a name cannot pass through a link to a directory outside it.
 *
 * The last segment is never followed: a link there is returned as the link itself, which a
 * caller deletes with [deleteWithoutFollowingLinks] and so unlinks without touching its target.
 *
 * @throws IllegalArgumentException naming the workspace and [name]
 */
fun resolveInWorkspace(
    workspace: File,
    name: String,
): File {
    val root = workspace.toPath().normalize()
    val resolved = root.resolve(name).normalize()
    require(
        name.isNotBlank() &&
            !File(name).isAbsolute &&
            resolved.startsWith(root) &&
            resolved != root &&
            parentStaysInside(root, resolved),
    ) {
        "A workspace file must be inside the workspace $workspace: '$name'"
    }
    return resolved.toFile()
}

/**
 * Resolves [name] like [resolveInWorkspace], for a file the CLI is about to write. A symbolic
 * link at the final path is removed first, so the write creates a regular file inside the
 * workspace and the link's target is left unchanged. Every writer of a workspace file opens it
 * through this function (or writes by an atomic rename, which replaces a link without following it).
 *
 * @throws IllegalArgumentException naming the workspace and [name], as [resolveInWorkspace] does
 */
fun resolveInWorkspaceForWrite(
    workspace: File,
    name: String,
): File {
    val file = resolveInWorkspace(workspace, name)
    if (Files.isSymbolicLink(file.toPath())) Files.delete(file.toPath())
    return file
}

/**
 * Resolves [name] like [resolveInWorkspace], for a directory the CLI writes files into (`logs`,
 * `profiles`, the restored `k8s`, `bin`). The directory itself, with its links followed, must be
 * inside the workspace: [resolveInWorkspace] leaves the last segment alone, so without this check a
 * `logs` link to a directory outside would take every file written under it outside too.
 *
 * @throws IllegalArgumentException naming the workspace and [name] when the directory, or a link
 *   at its name, leads outside the workspace
 */
fun resolveDirInWorkspace(
    workspace: File,
    name: String,
): File {
    val dir = resolveInWorkspace(workspace, name)
    require(staysInside(workspace.toPath().normalize(), dir.toPath())) {
        "A workspace directory must be inside the workspace $workspace: '$name' leads outside it through a link"
    }
    return dir
}

/** True when the directory holding [resolved] stays inside the workspace, by [staysInside]. */
private fun parentStaysInside(
    root: Path,
    resolved: Path,
): Boolean = staysInside(root, resolved.parent)

/**
 * True when [path], with every link in it followed, is inside the real workspace [root]. A path
 * that does not exist yet is checked through its nearest existing ancestor; a link whose target is
 * missing counts as outside.
 */
private fun staysInside(
    root: Path,
    path: Path,
): Boolean {
    if (Files.notExists(root)) return true
    val realRoot = root.toRealPath()
    val nearestExisting =
        generateSequence(path) { it.parent }
            .first { it == root || Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
    return runCatching { nearestExisting.toRealPath().startsWith(realRoot) }.getOrDefault(false)
}
