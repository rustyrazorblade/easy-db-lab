package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Puts a dashboard tree where a local stack's Grafana reads it, `<stack>/dashboards/current`.
 *
 * The provisioned directory is kept, never renamed or removed: right after a rename of it, Docker
 * Desktop's file sharing can show the container the old, removed directory, empty, and Grafana
 * 13.2.2's reload of that view deletes every provisioned dashboard and answers 200. So each
 * dashboard is written in place instead: to a temporary file in its own folder directory, renamed
 * over the old copy, so Grafana never reads a half-written file. Folder directories are created as
 * needed, and every file and folder of the previous tree that is not in the new one is removed, so
 * a dashboard dropped from the tree disappears from Grafana. The tree is the tool's own rendering,
 * not the operator's data; nothing outside `current` is touched. A path that is not
 * `<folder>/<file>.json` is refused before anything is written. Temporary names do not end in
 * `.json`, which Grafana's provider skips, and one a stopped install left is removed by the next.
 */
object DashboardTreeInstaller {
    private const val TEMP_SUFFIX = ".tmp"

    /** Replaces the provisioned tree of the stack in [stackDirectory] with [tree], by `<folder>/<file>.json`. */
    fun install(
        stackDirectory: File,
        tree: Map<String, String>,
    ) {
        val misplaced = tree.keys.filterNot(::isFolderFile)
        require(misplaced.isEmpty()) { "Not a <folder>/<file>.json dashboard path: ${misplaced.joinToString(", ")}" }
        val current = File(File(stackDirectory, Constants.LocalStack.DASHBOARDS_DIR), Constants.LocalStack.DASHBOARDS_TREE)
        current.mkdirs()
        tree.forEach { (path, json) -> writeInPlace(File(current, path), json) }
        removeAllBut(current, tree.keys)
    }

    private fun writeInPlace(
        target: File,
        json: String,
    ) {
        val folder = target.parentFile.apply { mkdirs() }
        val temp = Files.createTempFile(folder.toPath(), ".${target.name}.", TEMP_SUFFIX)
        try {
            Files.writeString(temp, json)
            Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** Removes everything under [current] that is neither a file of [kept] nor a folder holding one. */
    private fun removeAllBut(
        current: File,
        kept: Set<String>,
    ) {
        val folders = kept.map { it.substringBefore('/') }.toSet()
        current.listFiles().orEmpty().forEach { entry ->
            if (entry.isDirectory && entry.name in folders) {
                val dropped = entry.listFiles().orEmpty().filterNot { "${entry.name}/${it.name}" in kept }
                dropped.forEach { it.deleteRecursively() }
            } else {
                entry.deleteRecursively()
            }
        }
    }

    private fun isFolderFile(path: String): Boolean {
        val segments = path.split('/')
        return segments.size == 2 &&
            segments.none { it.isEmpty() || it == "." || it == ".." } &&
            segments.last().endsWith(".json")
    }
}
