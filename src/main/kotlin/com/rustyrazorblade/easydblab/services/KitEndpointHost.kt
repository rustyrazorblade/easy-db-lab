package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import com.rustyrazorblade.easydblab.reason
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Resolves the node address a client connects to for one kit [KitEndpoint].
 *
 * A NodePort endpoint answers on every node of its node type, so the first node of that type serves
 * it. An endpoint with a `pod-selector` is a `hostPort` on one pod, and answers only on the node that
 * runs that pod; the scheduler can place the pod on any node of the type. For it the address is the
 * host IP of a running, ready pod matching the selector, read from the cluster through [listPods]
 * (selector, namespace). Nothing here knows which kit or database serves the endpoint.
 */
class KitEndpointHost(
    private val listPods: (String, String) -> Result<List<KubernetesPod>>,
) {
    /**
     * The address that serves [endpoint] of the kit instance [kitName], whose pods run in
     * [namespace], given the cluster's [hosts]. Fails when no node of the endpoint's node type
     * exists, when the pod query fails, or when no running, ready pod matches the selector.
     */
    fun resolve(
        endpoint: KitEndpoint,
        kitName: String,
        namespace: String,
        hosts: Map<ServerType, List<ClusterHost>>,
    ): Result<String> =
        firstHostOf(endpoint, hosts).mapCatching { firstHost ->
            when (val selector = withKitName(endpoint.podSelector, kitName)) {
                "" -> firstHost
                else -> servingHost(endpoint, selector, namespace)
            }
        }

    /**
     * Every address that serves [endpoint]: each node of its node type for a NodePort endpoint
     * (empty when the cluster has none, or the node type is unknown), or the one node that runs
     * its serving pod for an endpoint with a `pod-selector`. Fails only for a `pod-selector`
     * endpoint whose pod cannot be found; see [resolve].
     */
    fun addresses(
        endpoint: KitEndpoint,
        kitName: String,
        namespace: String,
        hosts: Map<ServerType, List<ClusterHost>>,
    ): Result<List<String>> =
        when (endpoint.podSelector) {
            "" -> Result.success(KitEndpointAddresses.privateIps(endpoint.nodeType, hosts))
            else -> resolve(endpoint, kitName, namespace, hosts).map { listOf(it) }
        }

    /**
     * The addresses a display shows for [endpoint]: [addresses], or none when the serving pod
     * cannot be found. The failure is logged, so a display never shows a node that does not serve.
     */
    fun displayAddresses(
        endpoint: KitEndpoint,
        kitName: String,
        namespace: String,
        hosts: Map<ServerType, List<ClusterHost>>,
    ): List<String> =
        addresses(endpoint, kitName, namespace, hosts).getOrElse { e ->
            log.warn { "Kit $kitName: endpoint ${endpoint.name} is not shown: ${e.reason()}" }
            emptyList()
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

    private fun servingHost(
        endpoint: KitEndpoint,
        selector: String,
        namespace: String,
    ): String {
        val pods =
            listPods(selector, namespace).getOrElse { e ->
                error("Cannot find the pod serving endpoint '${endpoint.name}' ($selector in namespace $namespace): ${e.reason()}")
            }
        return servingHostIp(pods)
            ?: error(
                "No running, ready pod serves endpoint '${endpoint.name}' ($selector in namespace $namespace). " +
                    "Is the kit started?",
            )
    }

    companion object {
        private val log = KotlinLogging.logger {}

        /**
         * A [KitEndpointHost] that lists pods through the client [k8s] supplies. The client is
         * asked for only when a `pod-selector` endpoint is resolved, so a kit with only NodePort
         * endpoints never needs the cluster API.
         */
        fun of(k8s: () -> K8sReadOperations): KitEndpointHost =
            KitEndpointHost { selector, namespace -> k8s().listPodsByLabel(selector, namespace) }

        /**
         * The private IP of the first node of [endpoint]'s node type: the address of an endpoint every
         * node of the type serves. Fails when the cluster has no such node.
         */
        fun firstHostOf(
            endpoint: KitEndpoint,
            hosts: Map<ServerType, List<ClusterHost>>,
        ): Result<String> =
            runCatching {
                val serverType = ServerType.from(endpoint.nodeType)
                hosts[serverType]?.firstOrNull()?.privateIp
                    ?: error("No ${serverType.serverType} nodes found in cluster state. Is the cluster running?")
            }

        /**
         * The host IP of a pod in [pods] that can take a connection: running, every container
         * ready, not being deleted, and scheduled. With several, the first by name, so repeated
         * calls agree. Null when there is none.
         */
        fun servingHostIp(pods: List<KubernetesPod>): String? =
            pods
                .filter { it.isServing() }
                .minByOrNull { it.name }
                ?.hostIp

        private fun KubernetesPod.isServing(): Boolean {
            val (ready, total) = ready.split("/").let { it.firstOrNull() to it.getOrNull(1) }
            return status == RUNNING_PHASE &&
                !terminating &&
                hostIp.isNotBlank() &&
                ready != null &&
                ready == total &&
                ready != "0"
        }

        private const val RUNNING_PHASE = "Running"
    }
}
