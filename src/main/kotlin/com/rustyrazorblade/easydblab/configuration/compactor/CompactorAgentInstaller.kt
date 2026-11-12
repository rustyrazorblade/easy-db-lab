package com.rustyrazorblade.easydblab.configuration.compactor

import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService

/**
 * Prepares a cluster's control node for the `account-compactor` pod, on every cluster, telemetry
 * redirect included.
 *
 * It creates the scratch directories on the data mount and gives each to the user its compactor
 * runs as, and puts the lease agent jar at its SHA-256 path unless a jar is already there. The jar
 * is uploaded to a staging file the SSH user can write, then moved into place with sudo, so a
 * half-uploaded jar is never at the path the pod mounts.
 *
 * @property remoteOps runs the commands on the control node over SSH.
 * @property eventBus receives [Event.Grafana.CompactorAgentPrepared].
 */
class CompactorAgentInstaller(
    private val remoteOps: RemoteOperationsService,
    private val eventBus: EventBus,
) {
    private companion object {
        const val PRESENT = "present"
    }

    /**
     * Prepares [controlNode] and returns where the jar is on it.
     *
     * @throws Exception when a remote command fails (provider-specific).
     */
    fun install(
        controlNode: Host,
        jar: CompactorAgentJarFile,
    ): CompactorAgentJar {
        val remote = jar.remote
        val directories = Scratch.entries.map { it.hostPath } + CompactorAgentJar.AGENT_DIR
        val ownership = Scratch.entries.joinToString(" && ") { "sudo chown -R ${it.owner}:${it.owner} ${it.hostPath}" }
        remoteOps.executeRemotely(controlNode, "sudo mkdir -p ${directories.joinToString(" ")} && $ownership")

        val present =
            remoteOps
                .executeRemotely(controlNode, "test -f ${remote.hostPath} && echo $PRESENT || echo absent", output = false)
                .text
                .trim() == PRESENT
        if (!present) {
            val staged = "compactor-lease-${remote.sha256}.jar"
            remoteOps.upload(controlNode, jar.path, staged)
            remoteOps.executeRemotely(controlNode, "sudo mv $staged ${remote.hostPath} && sudo chmod 0644 ${remote.hostPath}")
        }
        eventBus.emit(Event.Grafana.CompactorAgentPrepared(remote.hostPath, uploaded = !present))
        return remote
    }
}
