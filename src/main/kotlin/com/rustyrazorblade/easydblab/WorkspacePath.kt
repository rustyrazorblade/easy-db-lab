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
 * True when the directory holding [resolved], with every link in it followed, is inside the real
 * workspace. A parent that does not exist yet is checked through its nearest existing ancestor; a
 * link whose target is missing counts as outside.
 */
private fun parentStaysInside(
    root: Path,
    resolved: Path,
): Boolean {
    if (Files.notExists(root)) return true
    val realRoot = root.toRealPath()
    val nearestExisting =
        generateSequence(resolved.parent) { it.parent }
            .first { it == root || Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
    return runCatching { nearestExisting.toRealPath().startsWith(realRoot) }.getOrDefault(false)
}
