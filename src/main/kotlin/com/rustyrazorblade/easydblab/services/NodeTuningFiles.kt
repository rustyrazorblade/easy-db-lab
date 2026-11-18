package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The node OS tuning files (node-os-tuning), as node setup uploads them.
 *
 * They are classpath resources, so they come from the distribution, never from a workspace or a
 * source checkout. [stage] copies them to a temporary local directory, so node setup can push
 * them to a node in one directory upload, and removes that directory once the upload is done.
 *
 * @param resourceDir the classpath directory that holds [files]
 * @param files the file names to stage; every one must exist under [resourceDir]
 */
class NodeTuningFiles(
    private val resourceDir: String = Constants.NodeTuning.RESOURCE_DIR,
    private val files: List<String> = Constants.NodeTuning.FILES,
) {
    /**
     * Copies every file to a new temporary directory, calls [upload] with it, then deletes it.
     *
     * @throws IllegalStateException naming the resource when one is missing, before [upload] runs
     */
    fun <T> stage(upload: (File) -> T): T {
        val dir = Files.createTempDirectory("edl-node-tuning-")
        try {
            files.forEach { name -> copy(name, dir.resolve(name)) }
            return upload(dir.toFile())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun copy(
        name: String,
        target: Path,
    ) {
        val resource = "$resourceDir/$name"
        val content =
            NodeTuningFiles::class.java.getResourceAsStream(resource)
                ?: error("Node tuning file missing from the distribution: $resource")
        content.use { input -> Files.newOutputStream(target).use { output -> input.copyTo(output) } }
    }
}
