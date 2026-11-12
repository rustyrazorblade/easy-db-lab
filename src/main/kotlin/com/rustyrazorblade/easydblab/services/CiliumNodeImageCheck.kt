package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService

/** A node whose AMI lacks some of the Cilium node fixes: its alias and the paths it lacks. */
data class NodeMissingCiliumFixes(
    val node: String,
    val missingFiles: List<String>,
)

/**
 * Checks that nodes were launched from an AMI carrying the Cilium node fixes the base image bakes:
 * the `edl-primary-eni-network` unit and script, the systemd-networkd drop-in that leaves every
 * other ENA link unmanaged, and the cloud-init no-hotplug setting.
 *
 * The unit writes the primary ENI's networkd file under `/run` on each boot, matched by the IMDS
 * primary MAC, so that file is not in the image and is not checked here; the unit and the script
 * that write it are. A node from an older AMI matches its primary by a fixed name, which is wrong
 * on families whose primary has another name, and it breaks once Cilium attaches a second ENI and
 * the OS takes it over, taking the node off the network well into `up`. Checking the files up
 * front turns that into an immediate, actionable failure.
 */
class CiliumNodeImageCheck(
    private val remoteOps: RemoteOperationsService,
    private val parallel: BoundedParallel = BoundedParallel(),
) {
    /**
     * The nodes among [hosts] that lack any of [REQUIRED_FILES], with what each lacks, in the order
     * of [hosts]. Nodes are checked in parallel on a bounded pool. A node that cannot be reached
     * fails the check rather than passing it.
     */
    fun nodesMissingFixes(hosts: List<ClusterHost>): List<NodeMissingCiliumFixes> =
        parallel
            .map(hosts) { host ->
                val output = remoteOps.executeRemotely(host = host.toHost(), command = CHECK_COMMAND, output = false).text
                // Only a required path counts: anything else the SSH session prints is noise.
                val missing = output.lines().map { it.trim() }.filter { it in REQUIRED_FILES }
                if (missing.isEmpty()) null else NodeMissingCiliumFixes(node = host.alias, missingFiles = missing)
            }.mapNotNull { it.getOrThrow() }

    companion object {
        /** The files the base AMI bakes for Cilium; see `configure_cilium_eni_networkd.sh`. */
        val REQUIRED_FILES: List<String> = Constants.Cilium.NODE_FIX_FILES

        // Prints each missing path and always exits 0, so the answer is in the output alone.
        private val CHECK_COMMAND =
            "for f in ${REQUIRED_FILES.joinToString(" ")}; do [ -e \"\$f\" ] || echo \"\$f\"; done; true"
    }
}
