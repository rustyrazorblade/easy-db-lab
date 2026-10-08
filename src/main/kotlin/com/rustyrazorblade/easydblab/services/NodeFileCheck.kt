package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService

/** A node that lacks some files its AMI should carry: its alias and the paths it lacks. */
data class NodeMissingFiles(
    val node: String,
    val missingFiles: List<String>,
)

/**
 * Asks each node over SSH which of a set of files it lacks. `up` uses it to find a node launched
 * from an AMI older than a fix the base image bakes, before that node breaks the cluster later.
 */
class NodeFileCheck(
    private val remoteOps: RemoteOperationsService,
) {
    /**
     * The nodes among [hosts] that lack any of [requiredFiles], with what each lacks. A node that
     * cannot be reached fails the check rather than passing it.
     */
    fun nodesMissing(
        hosts: List<ClusterHost>,
        requiredFiles: List<String>,
    ): List<NodeMissingFiles> {
        // Prints each missing path and always exits 0, so the answer is in the output alone.
        val command = "for f in ${requiredFiles.joinToString(" ")}; do [ -e \"\$f\" ] || echo \"\$f\"; done; true"
        return hosts.mapNotNull { host ->
            val output = remoteOps.executeRemotely(host = host.toHost(), command = command, output = false).text
            // Only a required path counts: anything else the SSH session prints is noise.
            val missing = output.lines().map { it.trim() }.filter { it in requiredFiles }
            if (missing.isEmpty()) null else NodeMissingFiles(node = host.alias, missingFiles = missing)
        }
    }
}
