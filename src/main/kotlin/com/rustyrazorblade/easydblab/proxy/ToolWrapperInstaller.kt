package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.PackagedExecutable
import java.io.File

/**
 * Writes and removes the workspace tool wrappers in `<workspace>/bin/`.
 *
 * Kit shell steps, hooks and `env.sh` shells put `<workspace>/bin` first on `PATH`, so `kubectl`,
 * `helm`, `cilium`, `curl`, `skopeo` and `k9s` resolve to the packaged wrapper, which routes each
 * call through the tunnel recorded in [ProxyEnvFile]. The wrappers are written at `up`, before every
 * kit process, and when a workspace is restored from a VPC, so an upgrade replaces stale copies.
 *
 * `bin/` belongs to easy-db-lab only when it holds [Constants.ToolWrappers.MARKER]. A `bin/` that
 * holds one of the tool names without the marker is someone else's, and installing fails without
 * writing anything rather than overwriting their file.
 *
 * @param wrapper the packaged wrapper script, read from the distribution's classpath
 * @param afterMarkerFoundMissing runs right after an install finds no marker; a test uses it to
 *   let another install finish in that window
 */
class ToolWrapperInstaller(
    private val wrapper: PackagedExecutable = PackagedExecutable.fromResource(Constants.ToolWrappers.RESOURCE),
    private val afterMarkerFoundMissing: () -> Unit = {},
) {
    /**
     * Writes the marker and the six wrappers into [workspace]'s `bin/`, leaving any copy that is
     * already up to date as it is.
     *
     * @throws IllegalStateException if `bin/` holds a tool file but not the marker
     */
    fun install(workspace: File) {
        val bin = binOf(workspace)
        val marker = File(bin, Constants.ToolWrappers.MARKER)
        if (!marker.exists()) {
            afterMarkerFoundMissing()
            val foreign = wrapperFiles(bin).filter { it.exists() }
            // Another process may have installed the wrappers since the marker was missing. It
            // writes the marker before any wrapper, so a wrapper it wrote is seen with its marker.
            check(foreign.isEmpty() || marker.exists()) {
                "${foreign.joinToString { it.path }} already exists and was not written by easy-db-lab. " +
                    "easy-db-lab writes its tool wrappers into ${bin.path}; use a new, empty directory as the workspace."
            }
            bin.mkdirs()
            // The marker goes first: a wrapper written without it would block the next install.
            marker.writeText("")
        }
        wrapperFiles(bin).forEach { wrapper.writeTo(it) }
    }

    /**
     * Deletes the six wrappers and the marker from [workspace]'s `bin/`, by name, and then `bin/`
     * itself if nothing else is left in it. A `bin/` without the marker is not easy-db-lab's, so
     * nothing in it is touched.
     */
    fun remove(workspace: File) {
        val bin = binOf(workspace)
        if (!File(bin, Constants.ToolWrappers.MARKER).exists()) return
        (wrapperFiles(bin) + File(bin, Constants.ToolWrappers.MARKER)).forEach { it.delete() }
        if (bin.isDirectory && bin.list().orEmpty().isEmpty()) bin.delete()
    }

    private fun binOf(workspace: File) = File(workspace, Constants.ToolWrappers.DIRECTORY)

    private fun wrapperFiles(bin: File) = Constants.ToolWrappers.TOOLS.map { File(bin, it) }
}
