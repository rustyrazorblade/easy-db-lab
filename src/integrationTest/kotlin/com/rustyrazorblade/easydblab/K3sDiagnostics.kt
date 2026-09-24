package com.rustyrazorblade.easydblab

import io.fabric8.kubernetes.client.KubernetesClient

/**
 * Renders a K3s TestContainer's node, pod and event state as text, for appending to a failed wait.
 *
 * A wait that times out in a K3s test otherwise says only that the condition never held. Whether
 * the pod was unscheduled, stuck pulling its image, or running but unready is the first thing
 * needed to tell a real failure from an environmental one, and it is gone once the container stops.
 */
object K3sDiagnostics {
    /** Node conditions, pod and container states, and events in [namespace], one per line. */
    fun clusterDiagnostics(
        client: KubernetesClient,
        namespace: String,
    ): String {
        val nodes =
            client.nodes().list().items.map { node ->
                val conditions =
                    node.status
                        ?.conditions
                        .orEmpty()
                        .joinToString { "${it.type}=${it.status}" }
                "node ${node.metadata.name}: $conditions"
            }
        val pods =
            client.pods().inNamespace(namespace).list().items.flatMap { pod ->
                val conditions =
                    pod.status
                        ?.conditions
                        .orEmpty()
                        .joinToString { "${it.type}=${it.status}(${it.reason ?: ""})" }
                val containers =
                    pod.status?.containerStatuses.orEmpty().map { cs ->
                        val state =
                            cs.state?.waiting?.let { "waiting ${it.reason}: ${it.message}" }
                                ?: cs.state?.terminated?.let { "terminated ${it.reason}: ${it.message}" }
                                ?: cs.state?.running?.let { "running since ${it.startedAt}" }
                        "  container ${cs.name} image=${cs.image} ready=${cs.ready} restarts=${cs.restartCount} $state"
                    }
                listOf("pod ${pod.metadata.name} phase=${pod.status?.phase} $conditions") + containers
            }
        val events =
            client
                .v1()
                .events()
                .inNamespace(namespace)
                .list()
                .items
                .sortedBy { it.lastTimestamp ?: it.eventTime?.time ?: "" }
                .map { "event ${it.lastTimestamp} ${it.involvedObject?.name} ${it.reason} x${it.count}: ${it.message}" }
        return (nodes + pods + events).joinToString("\n")
    }

    /** Runs [wait]; if it fails, rethrows with [client]'s state in [namespace] appended to the message. */
    fun <T> withClusterDiagnostics(
        client: KubernetesClient,
        namespace: String,
        wait: () -> T,
    ): T =
        try {
            wait()
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}\n${clusterDiagnostics(client, namespace)}", e)
        } catch (e: Exception) {
            throw AssertionError("${e.message}\n${clusterDiagnostics(client, namespace)}", e)
        }
}
