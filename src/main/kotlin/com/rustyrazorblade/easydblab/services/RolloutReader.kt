package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.Node
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.client.KubernetesClient

/**
 * The cluster reads one rollout look needs, in one namespace.
 *
 * It exists as a seam: [RolloutLooker] decides how often and what to read, and a test drives those
 * decisions through an in-memory reader that counts its calls, without a cluster and without
 * mocking K8sService. [Fabric8RolloutReader] is the real one.
 */
interface RolloutReader {
    /** The Deployment named [name], or null when it does not exist. */
    fun deployment(name: String): Deployment?

    /** The DaemonSet named [name], or null when it does not exist. */
    fun daemonSet(name: String): DaemonSet?

    /** Every pod in the namespace. */
    fun pods(): List<Pod>

    /** Every ReplicaSet in the namespace. */
    fun replicaSets(): List<ReplicaSet>

    /** The node named [name], or null when the cluster no longer has it. */
    fun node(name: String): Node?
}

/** Reads a rollout look's objects from [namespace] through [client]. */
class Fabric8RolloutReader(
    private val client: KubernetesClient,
    private val namespace: String,
) : RolloutReader {
    override fun deployment(name: String): Deployment? =
        client
            .apps()
            .deployments()
            .inNamespace(namespace)
            .withName(name)
            .get()

    override fun daemonSet(name: String): DaemonSet? =
        client
            .apps()
            .daemonSets()
            .inNamespace(namespace)
            .withName(name)
            .get()

    override fun pods(): List<Pod> =
        client
            .pods()
            .inNamespace(namespace)
            .list()
            .items

    override fun replicaSets(): List<ReplicaSet> =
        client
            .apps()
            .replicaSets()
            .inNamespace(namespace)
            .list()
            .items

    override fun node(name: String): Node? = client.nodes().withName(name).get()
}
