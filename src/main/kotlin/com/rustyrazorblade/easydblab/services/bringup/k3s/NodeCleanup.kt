package com.rustyrazorblade.easydblab.services.bringup.k3s

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.messageOrString
import com.rustyrazorblade.easydblab.runCatchingException
import com.rustyrazorblade.easydblab.services.K8sClientProvider
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException
import io.fabric8.kubernetes.client.dsl.Resource
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
     * @throws NodeCleanupTimeoutException when a deleted object is still there after the wait
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
            nodeUid?.let { awaitGone("Node", alias, it, node) }
            secretUid?.let { awaitGone("Secret", passwordSecret(alias), it, secret) }
        }
    }

    /**
     * Waits until [resource] is gone or has a UID other than [uid]. After a timeout it reads the
     * object fresh: one with another UID is a replacement, so the deleted object is gone; one with
     * [uid] is what holds it, and the error says how.
     */
    private fun <T : HasMetadata> awaitGone(
        kind: String,
        name: String,
        uid: String,
        resource: Resource<T>,
    ) {
        try {
            resource.waitUntilCondition({ it == null || it.metadata.uid != uid }, waitSeconds, TimeUnit.SECONDS)
        } catch (e: KubernetesClientTimeoutException) {
            val state =
                runCatchingException { resource.get() }.fold(
                    { held -> held?.takeIf { it.metadata.uid == uid }?.let(::describe) },
                    { "state unreadable: ${it.messageOrString()}" },
                )
            if (state != null) throw NodeCleanupTimeoutException(kind, name, uid, state, waitSeconds).apply { addSuppressed(e) }
        }
    }

    private fun describe(held: HasMetadata): String =
        "deletionTimestamp=${held.metadata.deletionTimestamp ?: "not set (delete not applied)"}, " +
            "finalizers=${held.metadata.finalizers.orEmpty()}"

    /** Names. */
    companion object {
        /** Where K3s keeps each node's password secret. */
        const val NAMESPACE = "kube-system"

        /** The secret K3s stores [alias]'s node password in. */
        fun passwordSecret(alias: String): String = "$alias.node-password.k3s"
    }
}
