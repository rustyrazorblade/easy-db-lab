package com.rustyrazorblade.easydblab.services.bringup.k3s

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.K8sClientProvider
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException
import java.util.concurrent.TimeUnit

/**
 * The K8s side of replacing a node (design D6 step 3): deletes the old instance's Node object and
 * its K3s node-password secret, so the replacement can register under the same alias. Without the
 * secret's removal K3s refuses the new instance ("Node password rejected"); the Node's removal
 * also lets the Cilium operator collect the old CiliumNode.
 *
 * Either may already be gone (the node never finished registering, or a re-run cleaned it), which
 * is not a failure. The client comes from [K8sClientProvider], so it is on the per-client tunnel.
 *
 * @param waitSeconds how long to wait for each deleted object to go
 * @param afterDelete runs between the deletes and the wait; a test uses it to put a replacement in place
 */
class NodeCleanup(
    private val clientProvider: K8sClientProvider,
    private val waitSeconds: Long = Constants.Scale.NODE_CLEANUP_DELETE_WAIT_SECONDS,
    private val afterDelete: () -> Unit = {},
) {
    /**
     * Deletes [alias]'s Node and its node-password secret, and waits until the objects deleted are
     * gone. The wait is on each object's UID, so a replacement that registers under [alias] during
     * the wait ends it. An object that did not exist is not waited for.
     *
     * @throws NodeCleanupTimeoutException when the deleted Node is still there after the wait
     */
    fun clean(alias: String) {
        clientProvider.createClient().use { client ->
            val node = client.nodes().withName(alias)
            val secret = client.secrets().inNamespace(NAMESPACE).withName(passwordSecret(alias))
            val nodeUid = node.get()?.metadata?.uid
            val secretUid = secret.get()?.metadata?.uid
            node.delete()
            secret.delete()
            afterDelete()
            if (nodeUid != null) {
                try {
                    node.waitUntilCondition({ it == null || it.metadata.uid != nodeUid }, waitSeconds, TimeUnit.SECONDS)
                } catch (e: KubernetesClientTimeoutException) {
                    // Read fresh: a Node with another UID is a replacement, so the deleted one is gone.
                    val stuck = node.get()?.takeIf { it.metadata.uid == nodeUid }
                    if (stuck != null) {
                        throw NodeCleanupTimeoutException(
                            alias,
                            nodeUid,
                            stuck.metadata.deletionTimestamp,
                            stuck.metadata.finalizers.orEmpty(),
                            waitSeconds,
                        ).apply { addSuppressed(e) }
                    }
                }
            }
            if (secretUid != null) {
                secret.waitUntilCondition({ it == null || it.metadata.uid != secretUid }, waitSeconds, TimeUnit.SECONDS)
            }
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
