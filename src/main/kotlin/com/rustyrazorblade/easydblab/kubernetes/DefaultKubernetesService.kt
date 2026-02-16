package com.rustyrazorblade.easydblab.kubernetes

import io.fabric8.kubernetes.api.model.Node
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Default implementation of KubernetesService using the Fabric8 Kubernetes Client.
 *
 * This service uses a lazy-initialized client that connects through a SOCKS proxy
 * for access to private K3s clusters.
 *
 * @property clientFactory Factory for creating Kubernetes clients
 * @property kubeconfigPath Path to the kubeconfig file
 */
class DefaultKubernetesService(
    private val clientFactory: KubernetesClientFactory,
    private val kubeconfigPath: Path,
) : KubernetesService {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val client: KubernetesClient by lazy {
        clientFactory.createClient(kubeconfigPath)
    }

    override fun listJobs(namespace: String?): Result<List<KubernetesJob>> =
        runCatching {
            log.debug { "Listing jobs${namespace?.let { " in namespace $it" } ?: " across all namespaces"}" }

            val jobs =
                if (namespace != null) {
                    client
                        .batch()
                        .v1()
                        .jobs()
                        .inNamespace(namespace)
                        .list()
                } else {
                    client
                        .batch()
                        .v1()
                        .jobs()
                        .inAnyNamespace()
                        .list()
                }

            jobs.items.map { it.toKubernetesJob() }
        }.onFailure { e ->
            log.warn(e) { "Failed to list jobs" }
        }

    override fun listPods(namespace: String?): Result<List<KubernetesPod>> =
        runCatching {
            log.debug { "Listing pods${namespace?.let { " in namespace $it" } ?: " across all namespaces"}" }

            val pods =
                if (namespace != null) {
                    client.pods().inNamespace(namespace).list()
                } else {
                    client.pods().inAnyNamespace().list()
                }

            pods.items.map { it.toKubernetesPod() }
        }.onFailure { e ->
            log.warn(e) { "Failed to list pods" }
        }

    override fun getNodes(): Result<List<KubernetesNode>> =
        runCatching {
            log.debug { "Getting cluster nodes" }

            val nodes = client.nodes().list()
            nodes.items.map { it.toKubernetesNode() }
        }.onFailure { e ->
            log.warn(e) { "Failed to get nodes" }
        }

    override fun isReachable(): Result<Boolean> =
        runCatching {
            log.debug { "Checking Kubernetes API reachability" }

            // Try to list namespaces - a lightweight call that works on any cluster
            client.namespaces().list()
            true
        }.onFailure { e ->
            log.debug(e) { "Kubernetes API not reachable" }
        }
}

/**
 * Convert Fabric8 Job to KubernetesJob
 */
private fun Job.toKubernetesJob(): KubernetesJob {
    val now = OffsetDateTime.now()
    val creationTime =
        metadata?.creationTimestamp?.let { parseK8sTimestamp(it) } ?: now
    val age = Duration.between(creationTime, now)

    val succeeded = status?.succeeded ?: 0
    val completions = spec?.completions ?: 1
    val completionsStr = "$succeeded/$completions"

    val jobStatus =
        when {
            status?.active != null && status?.active!! > 0 -> "Running"
            status?.succeeded != null && status?.succeeded!! > 0 -> "Completed"
            status?.failed != null && status?.failed!! > 0 -> "Failed"
            else -> "Unknown"
        }

    return KubernetesJob(
        namespace = metadata?.namespace ?: "default",
        name = metadata?.name ?: "unknown",
        status = jobStatus,
        completions = completionsStr,
        age = age,
    )
}

/**
 * Convert Fabric8 Pod to KubernetesPod
 */
private fun Pod.toKubernetesPod(): KubernetesPod {
    val now = OffsetDateTime.now()
    val creationTime =
        metadata?.creationTimestamp?.let { parseK8sTimestamp(it) } ?: now
    val age = Duration.between(creationTime, now)

    val containerStatuses = status?.containerStatuses ?: emptyList()
    val readyContainers = containerStatuses.count { it.ready == true }
    val totalContainers = containerStatuses.size.coerceAtLeast(spec?.containers?.size ?: 1)
    val readyStr = "$readyContainers/$totalContainers"

    val totalRestarts = containerStatuses.sumOf { it.restartCount ?: 0 }

    return KubernetesPod(
        namespace = metadata?.namespace ?: "default",
        name = metadata?.name ?: "unknown",
        status = status?.phase ?: "Unknown",
        ready = readyStr,
        restarts = totalRestarts,
        age = age,
    )
}

/**
 * Convert Fabric8 Node to KubernetesNode
 */
private fun Node.toKubernetesNode(): KubernetesNode {
    val labels = metadata?.labels ?: emptyMap()

    // Extract roles from labels (e.g., "node-role.kubernetes.io/control-plane" -> "control-plane")
    val roles =
        labels.keys
            .filter { it.startsWith("node-role.kubernetes.io/") }
            .map { it.removePrefix("node-role.kubernetes.io/") }
            .ifEmpty { listOf("<none>") }

    // Get node status from conditions
    val conditions = status?.conditions ?: emptyList()
    val isReady = conditions.any { it.type == "Ready" && it.status == "True" }
    val nodeStatus = if (isReady) "Ready" else "NotReady"

    return KubernetesNode(
        name = metadata?.name ?: "unknown",
        status = nodeStatus,
        roles = roles,
        version = status?.nodeInfo?.kubeletVersion ?: "unknown",
    )
}

/**
 * Parse Kubernetes ISO 8601 timestamp string to OffsetDateTime.
 * Handles both standard ISO format and Kubernetes format (with/without microseconds).
 */
private fun parseK8sTimestamp(timestamp: String): OffsetDateTime =
    try {
        OffsetDateTime.parse(timestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    } catch (_: Exception) {
        // Fallback for timestamps without offset (e.g., "2024-01-15T10:30:00Z")
        OffsetDateTime.parse(timestamp)
    }
