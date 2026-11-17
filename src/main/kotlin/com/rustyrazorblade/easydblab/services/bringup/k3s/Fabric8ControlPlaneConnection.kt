package com.rustyrazorblade.easydblab.services.bringup.k3s

import com.rustyrazorblade.easydblab.services.K8sClientProvider
import com.rustyrazorblade.easydblab.services.bringup.ControlPlaneConnection
import com.rustyrazorblade.easydblab.services.bringup.ControlPlaneReader
import com.rustyrazorblade.easydblab.services.bringup.ControlPlaneSampler
import com.rustyrazorblade.easydblab.services.bringup.Fabric8ControlPlaneReader
import com.rustyrazorblade.easydblab.services.bringup.HealthSampler
import com.rustyrazorblade.easydblab.services.bringup.NodeCleanup
import com.rustyrazorblade.easydblab.services.bringup.NodeTracker
import com.rustyrazorblade.easydblab.services.bringup.NodeWatcher

/** Opens the coordinator's view of the control plane, once the workspace kubeconfig exists. */
fun interface ControlPlaneConnector {
    fun open(): ControlPlaneConnection
}

/**
 * [ControlPlaneConnector] over fabric8: the gate's reader, the health sampler and the node
 * watcher, each on a client of its own from [clientProvider] (so a slow read on one does not
 * hold up the others), and [NodeCleanup] for a replaced node. Every client is on the per-client
 * tunnel [K8sClientProvider] sets up.
 */
class Fabric8ControlPlaneConnector(
    private val clientProvider: K8sClientProvider,
) : ControlPlaneConnector {
    override fun open(): ControlPlaneConnection {
        val opened = mutableListOf<AutoCloseable>()
        try {
            val reader = Fabric8ControlPlaneReader.open(clientProvider).also(opened::add)
            val sampler = ControlPlaneSampler.open(clientProvider).also(opened::add)
            val nodes = NodeWatcher.open(clientProvider).also(opened::add)
            return Connection(reader, sampler, nodes, NodeCleanup(clientProvider))
        } catch (e: Exception) {
            opened.forEach { runCatching { it.close() } }
            throw e
        }
    }

    private class Connection(
        private val fabric8Reader: Fabric8ControlPlaneReader,
        private val fabric8Sampler: ControlPlaneSampler,
        private val watcher: NodeWatcher,
        private val nodeCleanup: NodeCleanup,
    ) : ControlPlaneConnection {
        override val reader: ControlPlaneReader get() = fabric8Reader
        override val sampler: HealthSampler get() = fabric8Sampler
        override val nodes: NodeTracker get() = watcher

        override fun cleanup(alias: String) = nodeCleanup.clean(alias)

        override fun close() {
            listOf(watcher, fabric8Sampler, fabric8Reader).forEach { runCatching { it.close() } }
        }
    }
}
