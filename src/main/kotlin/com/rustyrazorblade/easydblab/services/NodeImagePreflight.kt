package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus

/**
 * The node-image checks `up` runs before K3s starts. A node launched from an AMI that predates a
 * fix the base image bakes joins the cluster normally and breaks only later, far into `up` or in a
 * pod; checking here names the nodes and the remedy instead.
 *
 * Every cluster needs the kubelet ECR credential provider ([EcrCredentialProviderNodeCheck]); a
 * Cilium cluster also needs the Cilium node fixes ([CiliumNodeImageCheck]).
 */
class NodeImagePreflight(
    private val credentialProvider: EcrCredentialProviderNodeCheck,
    private val cilium: CiliumNodeImageCheck,
    private val eventBus: EventBus,
) {
    /**
     * Checks every node in [hosts] for a cluster using [cni].
     *
     * @throws IllegalStateException after emitting [Event.K3s.NodeImageMissingCredentialProvider]
     *   or [Event.Cilium.NodeImageMissingFixes], when any node lacks what it needs.
     */
    fun verify(
        hosts: List<ClusterHost>,
        cni: CniMode?,
    ) {
        val lackingProvider = credentialProvider.nodesMissingProvider(hosts)
        if (lackingProvider.isNotEmpty()) {
            fail(
                Event.K3s.NodeImageMissingCredentialProvider(
                    nodes = lackingProvider.map { it.node },
                    missingFiles = lackingProvider.flatMap { it.missingFiles }.distinct(),
                ),
            )
        }
        if (cni != CniMode.Cilium) return
        val lackingFixes = cilium.nodesMissingFixes(hosts)
        if (lackingFixes.isNotEmpty()) {
            fail(
                Event.Cilium.NodeImageMissingFixes(
                    nodes = lackingFixes.map { it.node },
                    missingFiles = lackingFixes.flatMap { it.missingFiles }.distinct(),
                ),
            )
        }
    }

    private fun fail(event: Event): Nothing {
        eventBus.emit(event)
        error(event.toDisplayString())
    }
}
