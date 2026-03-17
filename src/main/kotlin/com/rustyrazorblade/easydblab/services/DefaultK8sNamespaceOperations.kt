package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.observability.TelemetryNames
import com.rustyrazorblade.easydblab.observability.TelemetryProvider
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * Implementation of namespace-related K8s operations: observability status,
 * pod readiness, namespace deletion, resource deletion by label, and rollout restarts.
 */
class DefaultK8sNamespaceOperations(
    private val clientProvider: K8sClientProvider,
    private val telemetryProvider: TelemetryProvider,
    private val eventBus: EventBus,
) : K8sNamespaceOperations {
    override fun getObservabilityStatus(controlHost: ClusterHost): Result<String> =
        runCatching {
            log.debug { "Getting observability status via SOCKS proxy" }

            clientProvider.createClient(controlHost).use { client ->
                formatPodStatus(client, Constants.K8s.NAMESPACE)
            }
        }

    override fun deleteObservability(controlHost: ClusterHost): Result<Unit> =
        runCatching {
            log.debug { "Deleting observability namespace via SOCKS proxy" }

            eventBus.emit(Event.K8s.ObservabilityNamespaceDeleting)

            clientProvider.createClient(controlHost).use { client ->
                val namespace = client.namespaces().withName(Constants.K8s.NAMESPACE).get()
                if (namespace != null) {
                    client.namespaces().withName(Constants.K8s.NAMESPACE).delete()
                    log.info { "Deleted namespace: ${Constants.K8s.NAMESPACE}" }
                } else {
                    log.info { "Namespace ${Constants.K8s.NAMESPACE} does not exist, nothing to delete" }
                }
            }

            eventBus.emit(Event.K8s.ObservabilityNamespaceDeleted)
        }

    override fun waitForPodsReady(
        controlHost: ClusterHost,
        timeoutSeconds: Int,
    ): Result<Unit> =
        runCatching {
            log.debug { "Waiting for pods to be ready in ${Constants.K8s.NAMESPACE} namespace" }

            eventBus.emit(Event.K8s.ObservabilityPodsWaiting)

            clientProvider.createClient(controlHost).use { client ->
                waitForPodsInNamespace(client, Constants.K8s.NAMESPACE, timeoutSeconds)
            }

            eventBus.emit(Event.K8s.ObservabilityPodsReady)
        }

    override fun waitForPodsReady(
        controlHost: ClusterHost,
        timeoutSeconds: Int,
        namespace: String,
    ): Result<Unit> =
        runCatching {
            log.debug { "Waiting for pods to be ready in $namespace namespace" }

            eventBus.emit(Event.K8s.PodsWaiting(namespace))

            clientProvider.createClient(controlHost).use { client ->
                waitForPodsInNamespace(client, namespace, timeoutSeconds)
            }

            eventBus.emit(Event.K8s.PodsReady(namespace))
        }

    override fun getNamespaceStatus(
        controlHost: ClusterHost,
        namespace: String,
    ): Result<String> =
        runCatching {
            log.debug { "Getting status for namespace $namespace via SOCKS proxy" }

            clientProvider.createClient(controlHost).use { client ->
                formatPodStatus(client, namespace)
            }
        }

    override fun deleteNamespace(
        controlHost: ClusterHost,
        namespace: String,
    ): Result<Unit> =
        runCatching {
            val attributes =
                mapOf(
                    TelemetryNames.Attributes.HOST_ALIAS to controlHost.alias,
                    TelemetryNames.Attributes.K8S_NAMESPACE to namespace,
                )
            telemetryProvider.withSpan(TelemetryNames.Spans.K8S_DELETE_NAMESPACE, attributes) {
                log.debug { "Deleting namespace $namespace via SOCKS proxy" }

                eventBus.emit(Event.K8s.NamespaceDeleting(namespace))

                clientProvider.createClient(controlHost).use { client ->
                    val ns = client.namespaces().withName(namespace).get()
                    if (ns != null) {
                        client.namespaces().withName(namespace).delete()
                        log.info { "Deleted namespace: $namespace" }
                    } else {
                        log.info { "Namespace $namespace does not exist, nothing to delete" }
                    }
                }

                eventBus.emit(Event.K8s.NamespaceDeleted(namespace))
            }
        }

    override fun deleteResourcesByLabel(
        controlHost: ClusterHost,
        namespace: String,
        labelKey: String,
        labelValues: List<String>,
    ): Result<Unit> =
        runCatching {
            log.info { "Deleting resources with $labelKey in $labelValues from namespace $namespace" }

            eventBus.emit(Event.K8s.ResourcesDeleting(labelKey))

            clientProvider.createClient(controlHost).use { client ->
                val labelSelector = "$labelKey in (${labelValues.joinToString(",")})"
                log.debug { "Using label selector: $labelSelector" }

                deleteBySelector(client, namespace, labelSelector)

                log.info { "All resources with label $labelKey deleted" }
            }

            eventBus.emit(Event.K8s.ResourcesDeleted)
        }

    override fun rolloutRestartDeployment(
        controlHost: ClusterHost,
        name: String,
        namespace: String,
    ): Result<Unit> =
        runCatching {
            log.info { "Rolling restart Deployment/$name in namespace $namespace" }
            clientProvider.createClient(controlHost).use { client ->
                client
                    .apps()
                    .deployments()
                    .inNamespace(namespace)
                    .withName(name)
                    .rolling()
                    .restart()
            }
            log.info { "Rolling restart initiated for Deployment/$name" }
        }

    override fun rolloutRestartDaemonSet(
        controlHost: ClusterHost,
        name: String,
        namespace: String,
    ): Result<Unit> =
        runCatching {
            log.info { "Rolling restart DaemonSet/$name in namespace $namespace" }
            clientProvider.createClient(controlHost).use { client ->
                client
                    .apps()
                    .daemonSets()
                    .inNamespace(namespace)
                    .withName(name)
                    .edit { ds ->
                        val annotations =
                            ds.spec
                                ?.template
                                ?.metadata
                                ?.annotations
                                ?.toMutableMap()
                                ?: mutableMapOf()
                        annotations["kubectl.kubernetes.io/restartedAt"] = Instant.now().toString()
                        ds.spec
                            ?.template
                            ?.metadata
                            ?.annotations = annotations
                        ds
                    }
            }
            log.info { "Rolling restart initiated for DaemonSet/$name" }
        }

    private fun formatPodStatus(
        client: KubernetesClient,
        namespace: String,
    ): String {
        val pods =
            client
                .pods()
                .inNamespace(namespace)
                .list()

        val header = "NAME                          READY   STATUS    RESTARTS   AGE"
        val lines =
            pods.items.map { pod ->
                val name = pod.metadata?.name ?: "unknown"
                val containerStatuses = pod.status?.containerStatuses ?: emptyList()
                val readyContainers = containerStatuses.count { it.ready == true }
                val totalContainers = containerStatuses.size.coerceAtLeast(1)
                val ready = "$readyContainers/$totalContainers"
                val status = pod.status?.phase ?: "Unknown"
                val restarts = containerStatuses.sumOf { it.restartCount ?: 0 }
                val age = "N/A"

                "%-30s %-7s %-9s %-10d %s".format(name, ready, status, restarts, age)
            }

        return (listOf(header) + lines).joinToString("\n")
    }

    private fun waitForPodsInNamespace(
        client: KubernetesClient,
        namespace: String,
        timeoutSeconds: Int,
    ) {
        val pods =
            client
                .pods()
                .inNamespace(namespace)
                .list()

        if (pods.items.isEmpty()) {
            log.warn { "No pods found in $namespace namespace" }
            return
        }

        val podNames = pods.items.mapNotNull { it.metadata?.name }
        log.info { "Waiting for ${podNames.size} pods: ${podNames.joinToString(", ")}" }

        for (podName in podNames) {
            val deadline = System.currentTimeMillis() + timeoutSeconds * Constants.Time.MILLIS_PER_SECOND
            var lastLoggedState = ""

            while (System.currentTimeMillis() < deadline) {
                val pod =
                    client
                        .pods()
                        .inNamespace(namespace)
                        .withName(podName)
                        .get() ?: break

                K8sPodUtils.checkForPodFailure(pod)

                val isReady =
                    pod.status?.conditions?.any {
                        it.type == "Ready" && it.status == "True"
                    } == true

                if (isReady) {
                    log.info { "Pod $podName is ready" }
                    break
                }

                val currentState = describePodState(pod)
                if (currentState != lastLoggedState) {
                    log.info { "Pod $podName: $currentState" }
                    lastLoggedState = currentState
                }

                val remaining = (deadline - System.currentTimeMillis()) / Constants.Time.MILLIS_PER_SECOND
                if (remaining <= 0) {
                    logPvcStatus(client, namespace)
                    error(
                        "Timed out waiting for [$timeoutSeconds] seconds for pod $podName. " +
                            "Last state: $currentState",
                    )
                }

                Thread.sleep(POD_POLL_INTERVAL_MS)
            }
        }
    }

    private fun describePodState(pod: io.fabric8.kubernetes.api.model.Pod): String {
        val phase = pod.status?.phase ?: "Unknown"
        val parts = mutableListOf("phase=$phase")

        // Show init container progress
        val initStatuses = pod.status?.initContainerStatuses.orEmpty()
        if (initStatuses.isNotEmpty()) {
            val initSummary =
                initStatuses.joinToString(", ") { cs ->
                    val state =
                        when {
                            cs.state?.running != null -> "running"
                            cs.state?.terminated != null -> {
                                val t = cs.state.terminated
                                if (t.exitCode == 0) "done" else "failed(exit=${t.exitCode})"
                            }
                            cs.state?.waiting != null -> "waiting(${cs.state.waiting.reason ?: "unknown"})"
                            else -> "unknown"
                        }
                    "${cs.name}=$state"
                }
            parts.add("init=[$initSummary]")
        }

        // Show container states
        val containerStatuses = pod.status?.containerStatuses.orEmpty()
        if (containerStatuses.isNotEmpty()) {
            val containerSummary =
                containerStatuses.joinToString(", ") { cs ->
                    val state =
                        when {
                            cs.state?.running != null -> "running"
                            cs.state?.terminated != null -> "terminated(${cs.state.terminated.reason})"
                            cs.state?.waiting != null -> "waiting(${cs.state.waiting.reason ?: "unknown"})"
                            else -> "unknown"
                        }
                    "${cs.name}=$state(restarts=${cs.restartCount ?: 0})"
                }
            parts.add("containers=[$containerSummary]")
        }

        // Show scheduling issues
        val conditions = pod.status?.conditions.orEmpty()
        val unschedulable = conditions.find { it.type == "PodScheduled" && it.status == "False" }
        if (unschedulable != null) {
            parts.add("unschedulable: ${unschedulable.message}")
        }

        return parts.joinToString(" ")
    }

    private fun logPvcStatus(
        client: KubernetesClient,
        namespace: String,
    ) {
        val pvcs =
            client
                .persistentVolumeClaims()
                .inNamespace(namespace)
                .list()
                .items
        if (pvcs.isEmpty()) return

        log.info { "PVC status in namespace $namespace:" }
        for (pvc in pvcs) {
            val name = pvc.metadata?.name ?: "unknown"
            val pvcPhase = pvc.status?.phase ?: "Unknown"
            val volumeName = pvc.spec?.volumeName ?: "<unbound>"
            log.info { "  PVC $name: phase=$pvcPhase volume=$volumeName" }
        }

        val pvNames = pvcs.mapNotNull { it.spec?.volumeName }.filter { it.isNotBlank() }.toSet()
        if (pvNames.isNotEmpty()) {
            log.info { "PV status:" }
            val allPvs = client.persistentVolumes().list().items
            for (pv in allPvs.filter { it.metadata?.name in pvNames }) {
                val pvPhase = pv.status?.phase ?: "Unknown"
                val claimRef = pv.spec?.claimRef
                val claimInfo = claimRef?.let { "${it.namespace}/${it.name} uid=${it.uid ?: "<none>"}" } ?: "<none>"
                log.info { "  PV ${pv.metadata.name}: phase=$pvPhase claimRef=$claimInfo" }
            }
        }
    }

    private fun deleteBySelector(
        client: KubernetesClient,
        namespace: String,
        labelSelector: String,
    ) {
        val ns = namespace
        val sel = labelSelector

        deleteMatchingResources(
            client
                .apps()
                .statefulSets()
                .inNamespace(ns)
                .withLabelSelector(sel)
                .list()
                .items,
            "StatefulSet",
        ) { name ->
            client
                .apps()
                .statefulSets()
                .inNamespace(ns)
                .withName(name)
                .delete()
        }

        deleteMatchingResources(
            client
                .apps()
                .deployments()
                .inNamespace(ns)
                .withLabelSelector(sel)
                .list()
                .items,
            "Deployment",
        ) { name ->
            client
                .apps()
                .deployments()
                .inNamespace(ns)
                .withName(name)
                .delete()
        }

        deleteMatchingResources(
            client
                .services()
                .inNamespace(ns)
                .withLabelSelector(sel)
                .list()
                .items,
            "Service",
        ) { name ->
            client
                .services()
                .inNamespace(ns)
                .withName(name)
                .delete()
        }

        deleteMatchingResources(
            client
                .configMaps()
                .inNamespace(ns)
                .withLabelSelector(sel)
                .list()
                .items,
            "ConfigMap",
        ) { name ->
            client
                .configMaps()
                .inNamespace(ns)
                .withName(name)
                .delete()
        }

        deleteMatchingResources(
            client
                .secrets()
                .inNamespace(ns)
                .withLabelSelector(sel)
                .list()
                .items,
            "Secret",
        ) { name ->
            client
                .secrets()
                .inNamespace(ns)
                .withName(name)
                .delete()
        }

        deleteMatchingResources(
            client
                .persistentVolumeClaims()
                .inNamespace(ns)
                .withLabelSelector(sel)
                .list()
                .items,
            "PVC",
        ) { name ->
            client
                .persistentVolumeClaims()
                .inNamespace(ns)
                .withName(name)
                .delete()
        }
    }

    private fun <T : HasMetadata> deleteMatchingResources(
        resources: List<T>,
        typeName: String,
        deleter: (String) -> Unit,
    ) {
        resources.forEach { resource ->
            val name = resource.metadata?.name ?: return@forEach
            log.info { "Deleting $typeName: $name" }
            deleter(name)
        }
    }
}

private const val POD_POLL_INTERVAL_MS = 5000L
