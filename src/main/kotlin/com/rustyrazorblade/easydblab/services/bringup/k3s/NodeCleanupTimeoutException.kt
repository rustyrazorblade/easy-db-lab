package com.rustyrazorblade.easydblab.services.bringup.k3s

/**
 * The Node that [NodeCleanup] deleted was still present when the wait ended.
 *
 * K3s puts a `wrangler.cattle.io/node` finalizer on every Node, so a Node that stays after its
 * delete is held by a finalizer that nothing removed. The message carries the Node's state read
 * after the timeout, so a failure shows what held it without a second look at the cluster.
 *
 * @property alias the node alias, which is the Node's name
 * @property uid the UID of the Node that was deleted
 */
class NodeCleanupTimeoutException(
    val alias: String,
    val uid: String,
    deletionTimestamp: String?,
    finalizers: List<String>,
    waitSeconds: Long,
) : RuntimeException(
        "Node $alias (uid $uid) was still present ${waitSeconds}s after its delete: " +
            "deletionTimestamp=$deletionTimestamp, finalizers=$finalizers",
    )
