package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Puts a dashboard tree where a local stack's Grafana reads it, `<stack>/dashboards/current`.
 *
 * The tree is written to a staging directory beside `current`, inside the `dashboards/` directory
 * Grafana mounts, so the renames stay on one file system. The old tree is renamed aside, the staged
 * one renamed into place, and the old one removed: Grafana never reads a half-written tree, and a
 * dashboard dropped from the tree disappears from Grafana. The old tree is the tool's own
 * rendering, not the operator's data; nothing outside `dashboards/` is touched. A path that is not
 * `<folder>/<file>.json` is refused before anything is written. When the new tree cannot be renamed
 * into place, the old one is renamed back; an old tree an earlier, stopped install left aside is
 * removed first.
 */
object DashboardTreeInstaller {
    private const val STAGING_PREFIX = ".staging-"
    private const val PREVIOUS_PREFIX = ".previous-"

    /**
     * Replaces the provisioned tree of the stack in [stackDirectory] with [tree], by `<folder>/<file>.json`.
     * [moveIntoPlace] renames the staged tree to `current`; a test makes it fail.
     */
    fun install(
        stackDirectory: File,
        tree: Map<String, String>,
        moveIntoPlace: (Path, Path) -> Unit = { from, to -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE) },
    ) {
        val misplaced = tree.keys.filterNot(::isFolderFile)
        require(misplaced.isEmpty()) { "Not a <folder>/<file>.json dashboard path: ${misplaced.joinToString(", ")}" }
        val parent = File(stackDirectory, Constants.LocalStack.DASHBOARDS_DIR).apply { mkdirs() }
        val current = File(parent, Constants.LocalStack.DASHBOARDS_TREE)
        // An install stopped between the two renames leaves the old tree aside; it is the tool's own.
        parent.listFiles { file -> file.name.startsWith(PREVIOUS_PREFIX) }.orEmpty().forEach { it.deleteRecursively() }
        val staging = Files.createTempDirectory(parent.toPath(), STAGING_PREFIX).toFile()
        try {
            tree.forEach { (path, json) -> File(staging, path).apply { parentFile.mkdirs() }.writeText(json) }
            val previous = if (current.exists()) moveAside(current, parent) else null
            try {
                moveIntoPlace(staging.toPath(), current.toPath())
            } catch (e: Exception) {
                // Grafana keeps the tree it had.
                previous?.let { Files.move(it.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE) }
                throw e
            }
            previous?.deleteRecursively()
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun isFolderFile(path: String): Boolean {
        val segments = path.split('/')
        return segments.size == 2 &&
            segments.none { it.isEmpty() || it == "." || it == ".." } &&
            segments.last().endsWith(".json")
    }

    private fun moveAside(
        current: File,
        parent: File,
    ): File {
        val aside = File(parent, "$PREVIOUS_PREFIX${System.nanoTime()}")
        Files.move(current.toPath(), aside.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return aside
    }
}
