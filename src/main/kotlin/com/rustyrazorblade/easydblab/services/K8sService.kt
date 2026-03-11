package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kubernetes.KubernetesJob
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import com.rustyrazorblade.easydblab.observability.TelemetryProvider
import io.fabric8.kubernetes.api.model.HasMetadata
import java.nio.file.Path

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
 */
data class PersistentVolumeConfig(
    val dbName: String,
    val localPath: String,
    val count: Int,
    val storageSize: String,
    val storageClass: String = Constants.K8s.LOCAL_STORAGE_CLASS,
    val namespace: String = "default",
    val volumeClaimTemplateName: String = "data",
)

/**
 * Operations for applying Kubernetes manifests, YAML, and typed resources.
 */
interface K8sManifestOperations {
    fun applyManifests(
        controlHost: ClusterHost,
        manifestPath: Path,
    ): Result<Unit>

    fun applyManifestFromResources(
        controlHost: ClusterHost,
        resourcePath: String,
    ): Result<Unit>

    fun applyYaml(
        controlHost: ClusterHost,
        yamlContent: String,
    ): Result<Unit>

    fun applyResource(
        controlHost: ClusterHost,
        resource: HasMetadata,
    ): Result<Unit>

    fun labelNode(
        controlHost: ClusterHost,
        nodeName: String,
        labels: Map<String, String>,
    ): Result<Unit>
}

/**
 * Operations for managing Kubernetes jobs and their pods.
 */
interface K8sJobOperations {
    fun createJob(
        controlHost: ClusterHost,
        namespace: String,
        jobYaml: String,
    ): Result<String>

    fun createJob(
        controlHost: ClusterHost,
        namespace: String,
        job: io.fabric8.kubernetes.api.model.batch.v1.Job,
    ): Result<String>

    fun deleteJob(
        controlHost: ClusterHost,
        namespace: String,
        jobName: String,
    ): Result<Unit>

    fun getJobsByLabel(
        controlHost: ClusterHost,
        namespace: String,
        labelKey: String,
        labelValue: String,
    ): Result<List<KubernetesJob>>

    fun getPodsForJob(
        controlHost: ClusterHost,
        namespace: String,
        jobName: String,
    ): Result<List<KubernetesPod>>

    fun getPodLogs(
        controlHost: ClusterHost,
        namespace: String,
        podName: String,
        tailLines: Int? = null,
    ): Result<String>
}

/**
 * Operations for managing Kubernetes namespaces, pods, and resource lifecycle.
 */
interface K8sNamespaceOperations {
    fun getObservabilityStatus(controlHost: ClusterHost): Result<String>

    fun deleteObservability(controlHost: ClusterHost): Result<Unit>

    fun waitForPodsReady(
        controlHost: ClusterHost,
        timeoutSeconds: Int,
    ): Result<Unit>

    fun waitForPodsReady(
        controlHost: ClusterHost,
        timeoutSeconds: Int,
        namespace: String,
    ): Result<Unit>

    fun getNamespaceStatus(
        controlHost: ClusterHost,
        namespace: String,
    ): Result<String>

    fun deleteNamespace(
        controlHost: ClusterHost,
        namespace: String,
    ): Result<Unit>

    fun deleteResourcesByLabel(
        controlHost: ClusterHost,
        namespace: String,
        labelKey: String,
        labelValues: List<String>,
    ): Result<Unit>

    fun rolloutRestartDeployment(
        controlHost: ClusterHost,
        name: String,
        namespace: String = Constants.K8s.NAMESPACE,
    ): Result<Unit>

    fun rolloutRestartDaemonSet(
        controlHost: ClusterHost,
        name: String,
        namespace: String = Constants.K8s.NAMESPACE,
    ): Result<Unit>
}

/**
 * Operations for managing Kubernetes storage resources.
 */
interface K8sStorageOperations {
    fun createClickHouseS3ConfigMap(
        controlHost: ClusterHost,
        namespace: String,
        s3EndpointUrl: String,
    ): Result<Unit>

    fun scaleStatefulSet(
        controlHost: ClusterHost,
        namespace: String,
        statefulSetName: String,
        replicas: Int,
    ): Result<Unit>

    fun createConfigMap(
        controlHost: ClusterHost,
        namespace: String,
        name: String,
        data: Map<String, String>,
        labels: Map<String, String> = emptyMap(),
    ): Result<Unit>

    fun deleteConfigMap(
        controlHost: ClusterHost,
        namespace: String,
        name: String,
    ): Result<Unit>

    fun createLocalPersistentVolumes(
        controlHost: ClusterHost,
        config: PersistentVolumeConfig,
    ): Result<Unit>

    fun ensureLocalStorageClass(controlHost: ClusterHost): Result<Unit>
}

/**
 * Combined K8s service interface, composed from focused operation interfaces.
 */
interface K8sService :
    K8sManifestOperations,
    K8sJobOperations,
    K8sNamespaceOperations,
    K8sStorageOperations

/**
 * Default implementation of K8sService that delegates to focused operation classes.
 *
 * Each operation group (manifests, jobs, namespaces, storage) is implemented by a
 * dedicated class, following the Single Responsibility Principle. This class composes
 * them via Kotlin's delegation pattern.
 *
 * @property clientProvider Shared provider for creating Kubernetes clients
 * @property telemetryProvider Provider for observability telemetry
 * @property eventBus Event bus for emitting domain events
 */
class DefaultK8sService(
    clientProvider: K8sClientProvider,
    telemetryProvider: TelemetryProvider,
    eventBus: EventBus,
) : K8sService,
    K8sManifestOperations by DefaultK8sManifestOperations(
        clientProvider,
        telemetryProvider,
        eventBus,
    ),
    K8sJobOperations by DefaultK8sJobOperations(
        clientProvider,
        eventBus,
    ),
    K8sNamespaceOperations by DefaultK8sNamespaceOperations(
        clientProvider,
        telemetryProvider,
        eventBus,
    ),
    K8sStorageOperations by DefaultK8sStorageOperations(
        clientProvider,
        telemetryProvider,
        eventBus,
    )
