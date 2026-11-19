package com.rustyrazorblade.easydblab.services.bringup.k3s

import com.rustyrazorblade.easydblab.services.K8sClientProvider
import com.rustyrazorblade.easydblab.services.bringup.NodeTracker
import com.rustyrazorblade.easydblab.services.bringup.NodeWatch
import io.fabric8.kubernetes.api.model.Node
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import java.util.concurrent.ConcurrentHashMap

/**
 * The one watch on Node objects every node pipeline shares (design D9): a fabric8 informer, so one
 * list and one watch replace a list every few seconds per waiting node. The client comes from
 * [K8sClientProvider], which routes it through the SOCKS tunnel for this client only.
 *
 * A pipeline [watch]es its node by alias and private IP. Its `registered` future completes when a
 * Node with that name and that `InternalIP` exists, and `ready` when that Node's `Ready` condition
 * is `True`. Matching the IP as well as the name keeps a replacement, which reuses the alias, from
 * being completed by the Node of the instance it replaced.
 */
class NodeWatcher private constructor(
    private val client: KubernetesClient,
) : NodeTracker,
    AutoCloseable {
    private val watches = ConcurrentHashMap<String, NodeWatch>()
    private val informer: SharedIndexInformer<Node> = client.nodes().inform(Handler(), RESYNC_MS)

    /** A Node already in the informer's cache counts at once. */
    override fun watch(
        alias: String,
        privateIp: String,
    ): NodeWatch {
        val watch = NodeWatch(alias, privateIp)
        watches[alias] = watch
        informer.store.getByKey(alias)?.let(::observe)
        return watch
    }

    override fun unwatch(alias: String) {
        watches.remove(alias)
    }

    override fun close() {
        informer.close()
        client.close()
    }

    private fun observe(node: Node) {
        val name = node.metadata?.name ?: return
        val watch = watches[name] ?: return
        if (internalIp(node) != watch.privateIp) return
        watch.registered.complete(Unit)
        if (isReady(node)) watch.ready.complete(Unit)
    }

    private inner class Handler : ResourceEventHandler<Node> {
        override fun onAdd(node: Node) = observe(node)

        override fun onUpdate(
            old: Node,
            node: Node,
        ) = observe(node)

        override fun onDelete(
            node: Node,
            deletedFinalStateUnknown: Boolean,
        ) = Unit
    }

    /** Opening a watcher, and reading a Node's facts. */
    companion object {
        private const val RESYNC_MS = 0L
        private const val INTERNAL_IP = "InternalIP"
        private const val READY = "Ready"
        private const val TRUE = "True"

        /** Opens a watcher on a client of its own from [clientProvider]; close it to stop. */
        fun open(clientProvider: K8sClientProvider): NodeWatcher = NodeWatcher(clientProvider.createClient())

        internal fun internalIp(node: Node): String? =
            node.status
                ?.addresses
                ?.firstOrNull { it.type == INTERNAL_IP }
                ?.address

        internal fun isReady(node: Node): Boolean =
            node.status
                ?.conditions
                .orEmpty()
                .any { it.type == READY && it.status == TRUE }
    }
}
