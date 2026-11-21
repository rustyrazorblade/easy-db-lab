package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import com.rustyrazorblade.easydblab.reason

/**
 * Resolves the node addresses a client connects to for one kit [KitEndpoint].
 *
 * A NodePort endpoint answers on every node of its node type, so the first node of that type serves
 * it. An endpoint served by one pod (it declares a `pod-selector`) is a `hostPort` on that pod, and
 * answers only on the node that runs it; the scheduler can place the pod on any node of the type.
 * For it the address is the host IP of a running, ready pod matching the selector, on a node of the
 * endpoint's node type, read from the cluster through [listPods] (selector, namespace). Nothing here
 * knows which kit or database serves the endpoint.
 */
class KitEndpointHost(
    private val listPods: (String, String) -> Result<List<KubernetesPod>>,
) {
    /** Where one endpoint is served. */
    sealed interface Lookup {
        /** The endpoint answers at each of [addresses]: none when the cluster has no node of its type. */
        data class Served(
            val addresses: List<String>,
        ) : Lookup

        /** The endpoint is served by one pod and no pod serves it now, for [reason]. */
        data class Unserved(
            val reason: String,
        ) : Lookup
    }

    /**
     * The address that serves [endpoint] of the kit instance [kitName], whose pods run in
     * [namespace], given the cluster's [hosts]. Fails with [EndpointNotServedException] when no node
     * of the endpoint's node type exists, or when no pod serves a one-pod endpoint (see [lookup]).
     */
    fun resolve(
        endpoint: KitEndpoint,
        kitName: String,
        namespace: String,
        hosts: Map<ServerType, List<ClusterHost>>,
    ): Result<String> =
        when (val found = lookup(endpoint, kitName, namespace, hosts)) {
            is Lookup.Served ->
                found.addresses.firstOrNull()?.let { Result.success(it) }
                    ?: Result.failure(EndpointNotServedException(endpoint.name, noNodesReason(endpoint)))
            is Lookup.Unserved -> Result.failure(EndpointNotServedException(endpoint.name, found.reason))
        }

    /**
     * Every address that serves [endpoint]: each node of its node type for a NodePort endpoint, or
     * the one node that runs its serving pod for an endpoint served by one pod. A one-pod endpoint
     * is [Lookup.Unserved] when the cluster has no node of its type (the pods are not asked), when
     * the pod query fails, when no running, ready pod matches, or when the pods that serve run only
     * on nodes of another type.
     */
    fun lookup(
        endpoint: KitEndpoint,
        kitName: String,
        namespace: String,
        hosts: Map<ServerType, List<ClusterHost>>,
    ): Lookup {
        val nodeIps = KitEndpointAddresses.privateIps(endpoint.nodeType, hosts)
        return when {
            !endpoint.servedByOnePod -> Lookup.Served(nodeIps)
            nodeIps.isEmpty() -> Lookup.Unserved(noNodesReason(endpoint))
            else -> servingPodHost(endpoint.servingPodSelectorFor(kitName), namespace, endpoint.nodeType, nodeIps)
        }
    }

    /**
     * A [KitEndpointHost] that lists the pods of each (selector, namespace) once and answers later
     * lookups from that list, so endpoints sharing a selector resolve to the same node and the
     * cluster is asked once. Make one per report; it never refreshes.
     */
    fun once(): KitEndpointHost {
        val seen = mutableMapOf<Pair<String, String>, Result<List<KubernetesPod>>>()
        return KitEndpointHost { selector, namespace ->
            seen.getOrPut(selector to namespace) { listPods(selector, namespace) }
        }
    }

    private fun servingPodHost(
        selector: String,
        namespace: String,
        nodeType: String,
        nodeIps: List<String>,
    ): Lookup {
        val pods =
            listPods(selector, namespace).getOrElse { e ->
                return Lookup.Unserved(
                    when (e) {
                        is ClusterUnreachableException -> e.reason
                        else -> "cannot list the pods matching $selector in namespace $namespace: ${e.reason()}"
                    },
                )
            }
        val serving = pods.filter { it.isServing() }
        val onTypeNode = newest(serving.filter { it.hostIp in nodeIps })
        return when {
            onTypeNode != null -> Lookup.Served(listOf(onTypeNode.hostIp))
            serving.isEmpty() -> Lookup.Unserved("no running, ready pod matches $selector in namespace $namespace")
            else ->
                Lookup.Unserved(
                    "the pods matching $selector in namespace $namespace run on " +
                        "${serving.joinToString { it.hostIp }}, which is not a node of type $nodeType",
                )
        }
    }

    companion object {
        /**
         * A [KitEndpointHost] that lists pods through the client [k8s] supplies. The client is
         * asked for only when an endpoint served by one pod is resolved, so a kit with only
         * NodePort endpoints never needs the cluster API.
         */
        fun of(k8s: () -> K8sReadOperations): KitEndpointHost =
            KitEndpointHost { selector, namespace -> k8s().listPodsByLabel(selector, namespace) }

        /**
         * A [KitEndpointHost] for when the cluster API cannot be reached, for [reason]: it never
         * asks, so every endpoint served by one pod is [Lookup.Unserved] with [reason] at once, and
         * NodePort endpoints still resolve from the cluster state.
         */
        fun unreachable(reason: String): KitEndpointHost = KitEndpointHost { _, _ -> Result.failure(ClusterUnreachableException(reason)) }

        /**
         * The private IP of the first node of [endpoint]'s node type: the address of an endpoint every
         * node of the type serves. Fails with [EndpointNotServedException] when the cluster has none.
         */
        fun firstHostOf(
            endpoint: KitEndpoint,
            hosts: Map<ServerType, List<ClusterHost>>,
        ): Result<String> =
            KitEndpointAddresses.privateIps(endpoint.nodeType, hosts).firstOrNull()?.let { Result.success(it) }
                ?: Result.failure(EndpointNotServedException(endpoint.name, noNodesReason(endpoint)))

        /**
         * The host IP of the pod in [pods] that takes connections: running, every container ready,
         * not being deleted, and scheduled. With several (a rollout keeps the old pod ready until
         * the new one is), the newest, so a client follows the rollout. Null when there is none.
         */
        fun servingHostIp(pods: List<KubernetesPod>): String? = newest(pods.filter { it.isServing() })?.hostIp

        private fun newest(pods: List<KubernetesPod>): KubernetesPod? = pods.minWithOrNull(compareBy({ it.age }, { it.name }))

        private fun KubernetesPod.isServing(): Boolean = isReady() && !terminating && hostIp.isNotBlank()

        private fun noNodesReason(endpoint: KitEndpoint): String =
            "No ${endpoint.nodeType} nodes found in cluster state. Is the cluster running?"
    }
}

/** The cluster API was not asked, because it cannot be reached, for [reason]. */
private class ClusterUnreachableException(
    val reason: String,
) : IllegalStateException(reason)

/**
 * No node serves the kit endpoint [endpointName], for [reason]: the cluster has no node of its type,
 * or no pod serves an endpoint served by one pod. A caller tells this apart from a failure of the
 * connection or the statement it would have run.
 */
class EndpointNotServedException(
    val endpointName: String,
    val reason: String,
) : IllegalStateException("Endpoint '$endpointName' is not served: $reason")
