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
 * The tunnel script [Constants.ToolWrappers.TUNNEL_SCRIPT], which keeps the SOCKS tunnel's ssh running,
 * is written and removed with them.
 *
 * `bin/` belongs to easy-db-lab only when it holds [Constants.ToolWrappers.MARKER]. A `bin/` that
 * holds one of the tool names, or the tunnel script, without the marker is someone else's, and
 * installing fails without writing anything rather than overwriting their file.
 *
 * @param wrapper the packaged wrapper script, read from the distribution's classpath
 * @param tunnel the packaged tunnel script, read from the distribution's classpath
 * @param afterMarkerFoundMissing runs right after an install finds no marker; a test uses it to
 *   let another install finish in that window
 */
class ToolWrapperInstaller(
    private val wrapper: PackagedExecutable = PackagedExecutable.fromResource(Constants.ToolWrappers.RESOURCE),
    private val tunnel: PackagedExecutable = PackagedExecutable.fromResource(Constants.ToolWrappers.TUNNEL_RESOURCE),
    private val afterMarkerFoundMissing: () -> Unit = {},
) {
    /**
     * Writes the marker, the six wrappers and the tunnel script into [workspace]'s `bin/`, leaving any
     * copy that is already up to date as it is.
     *
     * @throws IllegalStateException if `bin/` holds a tool file but not the marker
     */
    fun install(workspace: File) {
        val bin = binOf(workspace)
        val marker = File(bin, Constants.ToolWrappers.MARKER)
        if (!marker.exists()) {
            afterMarkerFoundMissing()
            val foreign = (wrapperFiles(bin) + tunnelFile(bin)).filter { it.exists() }
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
        tunnel.writeTo(tunnelFile(bin))
    }

    /**
     * Deletes the six wrappers, the tunnel script and the marker from [workspace]'s `bin/`, by name, and then `bin/`
     * itself if nothing else is left in it. A `bin/` without the marker is not easy-db-lab's, so
     * nothing in it is touched.
     */
    fun remove(workspace: File) {
        val bin = binOf(workspace)
        if (!File(bin, Constants.ToolWrappers.MARKER).exists()) return
        (wrapperFiles(bin) + tunnelFile(bin) + File(bin, Constants.ToolWrappers.MARKER)).forEach { it.delete() }
        if (bin.isDirectory && bin.list().orEmpty().isEmpty()) bin.delete()
    }

    private fun binOf(workspace: File) = File(workspace, Constants.ToolWrappers.DIRECTORY)

    private fun wrapperFiles(bin: File) = Constants.ToolWrappers.TOOLS.map { File(bin, it) }

    private fun tunnelFile(bin: File) = File(bin, Constants.ToolWrappers.TUNNEL_SCRIPT)
}
