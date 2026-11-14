package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kubernetes.KubernetesJob
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.batch.v1.Job
import java.nio.file.Path
import java.time.Duration

/**
 * Configuration for creating local persistent volumes.
 *
 * @property dbName Name of the database (used in PV naming)
 * @property localPath Local filesystem path on the node
 * @property count Number of PVs to create
 * @property storageSize Storage capacity (e.g., "100Gi")
 * @property storageClass StorageClass name
 * @property namespace Namespace for PVC binding
 * @property volumeClaimTemplateName Name of the volume claim template
 * @property nodeType The node pool (`db`, `app`) the PVs are on; each PV requires that pool as well
 *   as its ordinal, since db and app nodes both carry ordinals
 */
data class PersistentVolumeConfig(
    val dbName: String,
    val localPath: String,
    val count: Int,
    val storageSize: String,
    val storageClass: String = Constants.K8s.LOCAL_STORAGE_CLASS,
    val namespace: String = "default",
    val volumeClaimTemplateName: String = "data",
    val nodeType: String = ServerType.Cassandra.serverType,
)

/**
 * Operations for applying Kubernetes manifests, YAML, and typed resources.
 */
interface K8sManifestOperations {
    fun applyManifests(manifestPath: Path): Result<Unit>

    fun applyManifestFromResources(resourcePath: String): Result<Unit>

    fun applyYaml(yamlContent: String): Result<Unit>

    fun applyResource(resource: HasMetadata): Result<Unit>

    fun labelNode(
        nodeName: String,
        labels: Map<String, String>,
    ): Result<Unit>
}

/**
 * Operations for managing Kubernetes jobs and their pods.
 */
interface K8sJobOperations {
    fun createJob(
        namespace: String,
        jobYaml: String,
    ): Result<String>

    fun createJob(
        namespace: String,
        job: Job,
    ): Result<String>

    fun deleteJob(
        namespace: String,
        jobName: String,
    ): Result<Unit>

    fun getJobsByLabel(
        namespace: String,
        labelKey: String,
        labelValue: String,
    ): Result<List<KubernetesJob>>

    fun getPodsForJob(
        namespace: String,
        jobName: String,
    ): Result<List<KubernetesPod>>

    fun getPodLogs(
        namespace: String,
        podName: String,
        tailLines: Int? = null,
    ): Result<String>
}

/**
 * Operations for managing Kubernetes namespaces, pods, and resource lifecycle.
 */
interface K8sNamespaceOperations {
    fun getObservabilityStatus(): Result<String>

    fun deleteObservability(): Result<Unit>

    fun waitForPodsReady(timeoutSeconds: Int): Result<Unit>

    fun waitForPodsReady(
        timeoutSeconds: Int,
        namespace: String,
    ): Result<Unit>

    /**
     * Waits until every node named in [nodeNames] is registered with the cluster, listing the
     * nodes once per poll, and fails naming the ones still missing when [timeout] passes. K3s
     * agents label their own node when they register, so once this returns every node carries its
     * `type` and ordinal labels.
     */
    fun waitForNodesRegistered(
        nodeNames: List<String>,
        timeout: Duration,
    ): Result<Unit>

    fun getNamespaceStatus(namespace: String): Result<String>

    fun deleteNamespace(namespace: String): Result<Unit>

    fun deleteResourcesByLabel(
        namespace: String,
        labelKey: String,
        labelValues: List<String>,
    ): Result<Unit>

    fun rolloutRestartDeployment(
        name: String,
        namespace: String = Constants.K8s.NAMESPACE,
    ): Result<Unit>

    fun rolloutRestartDaemonSet(
        name: String,
        namespace: String = Constants.K8s.NAMESPACE,
    ): Result<Unit>

    /**
     * Waits until every one of [workloads] in [namespace] has finished rolling out, with
     * `kubectl rollout status` semantics (see [RolloutStatus]). Call it after applying a workload:
     * pod readiness alone passes while the old pods are still serving.
     *
     * @return failure naming each unfinished workload and what it is waiting on if they have not
     *   all completed within [timeoutSeconds].
     */
    fun waitForRollouts(
        workloads: List<WorkloadRef>,
        namespace: String,
        timeoutSeconds: Int,
    ): Result<Unit>

    /**
     * The [Constants.K8s.CONFIG_HASH_ANNOTATION] on the pod template of each of [workloads] as it runs
     * in [namespace] now; null for a workload that does not exist or carries no hash.
     */
    fun workloadConfigHashes(
        workloads: List<WorkloadRef>,
        namespace: String,
    ): Result<Map<WorkloadRef, String?>>
}

/**
 * Operations for managing Kubernetes storage resources.
 */
interface K8sStorageOperations {
    fun scaleStatefulSet(
        namespace: String,
        statefulSetName: String,
        replicas: Int,
    ): Result<Unit>

    fun createConfigMap(
        namespace: String,
        name: String,
        data: Map<String, String>,
        labels: Map<String, String> = emptyMap(),
    ): Result<Unit>

    fun deleteConfigMap(
        namespace: String,
        name: String,
    ): Result<Unit>

    /** Deletes all ConfigMaps in [namespace] whose labels match all entries in [labels]. */
    fun deleteConfigMapsByLabels(
        namespace: String,
        labels: Map<String, String>,
    ): Result<Unit>

    fun createLocalPersistentVolumes(config: PersistentVolumeConfig): Result<Unit>

    fun deleteLocalPersistentVolumes(dbName: String): Result<Unit>

    fun ensureLocalStorageClass(): Result<Unit>

    fun ensureLocalStorageWfcClass(): Result<Unit>
}

/**
 * Combined K8s service interface, composed from focused operation interfaces.
 */
interface K8sService :
    K8sManifestOperations,
    K8sJobOperations,
    K8sNamespaceOperations,
    K8sStorageOperations,
    K8sPodOperations,
    K8sReadOperations

/**
 * Default implementation of K8sService that delegates to focused operation classes.
 *
 * Each operation group (manifests, jobs, namespaces, storage, pod exec) is implemented by a
 * dedicated class, following the Single Responsibility Principle. This class composes
 * them via Kotlin's delegation pattern.
 *
 * @property clientProvider Shared provider for creating Kubernetes clients
 * @property eventBus Event bus for emitting domain events
 */
class DefaultK8sService(
    clientProvider: K8sClientProvider,
    eventBus: EventBus,
) : K8sService,
    K8sManifestOperations by DefaultK8sManifestOperations(
        clientProvider,
        eventBus,
    ),
    K8sJobOperations by DefaultK8sJobOperations(
        clientProvider,
        eventBus,
    ),
    K8sNamespaceOperations by DefaultK8sNamespaceOperations(
        clientProvider,
        eventBus,
    ),
    K8sStorageOperations by DefaultK8sStorageOperations(
        clientProvider,
        eventBus,
    ),
    K8sPodOperations by DefaultK8sPodOperations(clientProvider),
    K8sReadOperations by DefaultK8sReadOperations(clientProvider)
