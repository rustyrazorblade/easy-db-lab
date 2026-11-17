package com.rustyrazorblade.easydblab.services.bringup.k3s

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.K8sClientProvider
import java.util.concurrent.TimeUnit

/**
 * The K8s side of replacing a node (design D6 step 3): deletes the old instance's Node object and
 * its K3s node-password secret, so the replacement can register under the same alias. Without the
 * secret's removal K3s refuses the new instance ("Node password rejected"); the Node's removal
 * also lets the Cilium operator collect the old CiliumNode.
 *
 * Either may already be gone (the node never finished registering, or a re-run cleaned it), which
 * is not a failure. The client comes from [K8sClientProvider], so it is on the per-client tunnel.
 */
class NodeCleanup(
    private val clientProvider: K8sClientProvider,
) {
    /** Deletes [alias]'s Node and its node-password secret, and waits until both are gone. */
    fun clean(alias: String) {
        clientProvider.createClient().use { client ->
            client
                .nodes()
                .withName(alias)
                .delete()
            client
                .secrets()
                .inNamespace(NAMESPACE)
                .withName(passwordSecret(alias))
                .delete()
            client
                .nodes()
                .withName(alias)
                .waitUntilCondition({ it == null }, Constants.Scale.NODE_CLEANUP_DELETE_WAIT_SECONDS, TimeUnit.SECONDS)
            client
                .secrets()
                .inNamespace(NAMESPACE)
                .withName(passwordSecret(alias))
                .waitUntilCondition({ it == null }, Constants.Scale.NODE_CLEANUP_DELETE_WAIT_SECONDS, TimeUnit.SECONDS)
        }
    }

    /** Names. */
    companion object {
        /** Where K3s keeps each node's password secret. */
        const val NAMESPACE = "kube-system"

        /** The secret K3s stores [alias]'s node password in. */
        fun passwordSecret(alias: String): String = "$alias.node-password.k3s"
    }
}
