package com.rustyrazorblade.easydblab.services.bringup.k3s

/**
 * An object [NodeCleanup] deleted was still present when the wait ended.
 *
 * K3s puts a `wrangler.cattle.io/node` finalizer on every Node, so a Node that stays after its
 * delete is held by a finalizer that nothing removed. The message carries the object's state read
 * after the timeout, so a failure shows what held it without a second look at the cluster.
 *
 * @property kind what was held, Node or Secret
 * @property name the object's name
 * @property uid the UID of the object that was deleted
 */
class NodeCleanupTimeoutException(
    val kind: String,
    val name: String,
    val uid: String,
    state: String,
    waitSeconds: Long,
) : RuntimeException("$kind $name (uid $uid) was still present ${waitSeconds}s after its delete: $state")
