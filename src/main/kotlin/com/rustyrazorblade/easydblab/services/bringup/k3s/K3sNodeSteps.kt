package com.rustyrazorblade.easydblab.services.bringup.k3s

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.providers.ssh.isSshNotReady
import com.rustyrazorblade.easydblab.services.AgentStartMode
import com.rustyrazorblade.easydblab.services.CiliumNodeImageCheck
import com.rustyrazorblade.easydblab.services.K3sClusterService
import com.rustyrazorblade.easydblab.services.K3sServer
import com.rustyrazorblade.easydblab.services.NodeSetupContext
import com.rustyrazorblade.easydblab.services.NodeSetupService
import com.rustyrazorblade.easydblab.services.RegistryService
import com.rustyrazorblade.easydblab.services.bringup.ClusterStateWriter
import com.rustyrazorblade.easydblab.services.bringup.ControlPlaneAccess
import com.rustyrazorblade.easydblab.services.bringup.ImageDefect
import com.rustyrazorblade.easydblab.services.bringup.NodeSteps

/**
 * [NodeSteps] for a K3s cluster reached over SSH: the SSH probe, the node's setup, the Cilium image
 * check, registry TLS, the K3s agent join and its journal.
 *
 * Every cluster fact a step needs (db0 and the control node's private IPs, the bucket the
 * registry certificate is in) is read from [state] when the step runs, so a step on a
 * replacement sees the cluster as it is then.
 */
class K3sNodeSteps(
    private val remoteOps: RemoteOperationsService,
    private val nodeSetup: NodeSetupService,
    private val imageCheck: CiliumNodeImageCheck,
    private val registry: RegistryService,
    private val k3s: K3sClusterService,
    private val state: ClusterStateWriter,
) : NodeSteps {
    override fun sshAnswers(host: ClusterHost): Boolean =
        try {
            // Only that the host answers matters, so its output is not echoed.
            remoteOps.executeRemotely(host.toHost(), SSH_PROBE, output = false)
            true
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            if (!isSshNotReady(e)) throw e
            false
        }

    override fun setup(
        serverType: ServerType,
        host: ClusterHost,
    ) = nodeSetup.setup(serverType, host.toHost(), state.read(NodeSetupContext::from))

    /** Under Cilium, the Cilium node fixes the image must carry; Flannel needs nothing of the image. */
    override fun checkImage(host: ClusterHost): ImageDefect? {
        if (state.read(::cniOf) != CniMode.Cilium) return null
        val missing = imageCheck.nodesMissingFixes(listOf(host)).flatMap { it.missingFiles }
        if (missing.isEmpty()) return null
        return ImageDefect(
            reason = "its image lacks the Cilium node fixes (missing ${missing.joinToString(", ")}); rebuild the images",
            report = Event.Cilium.NodeImageMissingFixes(listOf(host.alias), missing),
        )
    }

    override fun configureRegistryTls(host: ClusterHost) {
        val (registryHost, bucket) =
            state.read { cluster ->
                val control = checkNotNull(cluster.getControlHost()) { "The cluster has no control node" }
                control.privateIp to checkNotNull(cluster.s3Bucket) { "The S3 bucket must be configured before registry TLS" }
            }
        registry.configureTlsOnNode(host.toHost(), registryHost, bucket)
    }

    override fun join(
        host: ClusterHost,
        serverType: ServerType,
        access: ControlPlaneAccess,
        attempt: Int,
    ) = k3s.joinAgent(
        host,
        serverType,
        K3sServer(access.url, access.token),
        if (attempt == 1) AgentStartMode.START else AgentStartMode.RESTART,
    )

    override fun joinLog(host: ClusterHost): String = remoteOps.executeRemotely(host.toHost(), JOIN_LOG, output = false).text

    private companion object {
        const val SSH_PROBE = "echo 1"
        const val JOIN_LOG = "sudo journalctl --unit=k3s-agent --no-pager --output=cat --lines=${Constants.Scale.JOIN_LOG_LINES}"
    }
}
