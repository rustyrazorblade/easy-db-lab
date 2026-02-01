package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.kubernetes.KubernetesJob
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import com.rustyrazorblade.easydblab.kubernetes.ManifestApplier
import com.rustyrazorblade.easydblab.kubernetes.ProxiedKubernetesClientFactory
import com.rustyrazorblade.easydblab.observability.TelemetryNames
import com.rustyrazorblade.easydblab.observability.TelemetryProvider
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Service for managing Kubernetes resources on the K3s cluster.
 *
 * This service handles the application and management of Kubernetes manifests
 * for observability components (OTel collectors, Prometheus, Grafana) on the
 * K3s cluster. It connects via SOCKS proxy and uses the fabric8 Kubernetes client.
 *
 * All operations return Result types for explicit error handling.
 */
interface K8sService {
    /**
     * Applies Kubernetes manifests to the cluster.
     *
     * Reads manifest files from the specified path and applies them to the cluster
     * using the Kubernetes API via SOCKS proxy.
     *
     * @param controlHost The control node running the K3s server (used as gateway)
     * @param manifestPath Local path to a manifest file or directory containing K8s manifests
     * @return Result indicating success or failure
     */
    fun applyManifests(
        controlHost: ClusterHost,
        manifestPath: Path,
    ): Result<Unit>

    /**
     * Gets the status of observability pods in the cluster.
     *
     * @param controlHost The control node running the K3s server
     * @return Result containing pod status output or failure
     */
    fun getObservabilityStatus(controlHost: ClusterHost): Result<String>

    /**
     * Deletes the observability namespace and all its resources.
     *
     * @param controlHost The control node running the K3s server
     * @return Result indicating success or failure
     */
    fun deleteObservability(controlHost: ClusterHost): Result<Unit>

    /**
     * Waits for all pods in the observability namespace to be ready.
     *
     * @param controlHost The control node running the K3s server
     * @param timeoutSeconds Maximum time to wait for pods to be ready
     * @return Result indicating success or failure
     */
    fun waitForPodsReady(
        controlHost: ClusterHost,
        timeoutSeconds: Int = 120,
    ): Result<Unit>

    /**
     * Waits for all pods in a specific namespace to be ready.
     *
     * @param controlHost The control node running the K3s server
     * @param timeoutSeconds Maximum time to wait for pods to be ready
     * @param namespace The namespace to check pods in
     * @return Result indicating success or failure
     */
    fun waitForPodsReady(
        controlHost: ClusterHost,
        timeoutSeconds: Int = 120,
        namespace: String,
    ): Result<Unit>

    /**
     * Gets the status of pods in a specific namespace.
     *
     * @param controlHost The control node running the K3s server
     * @param namespace The namespace to get pod status for
     * @return Result containing pod status output or failure
     */
    fun getNamespaceStatus(
        controlHost: ClusterHost,
        namespace: String,
    ): Result<String>

    /**
     * Deletes a namespace and all its resources.
     *
     * @param controlHost The control node running the K3s server
     * @param namespace The namespace to delete
     * @return Result indicating success or failure
     */
    fun deleteNamespace(
        controlHost: ClusterHost,
        namespace: String,
    ): Result<Unit>

    /**
     * Deletes Kubernetes resources by label selector.
     *
     * Deletes all resources matching the specified label in the given namespace.
     * Deletion order: StatefulSets, Deployments, Services, ConfigMaps, Secrets, PVCs.
     *
     * @param controlHost The control node running the K3s server
     * @param namespace The namespace to delete resources from
     * @param labelKey The label key to match (e.g., "app.kubernetes.io/name")
     * @param labelValues List of label values to match
     * @return Result indicating success or failure
     */
    fun deleteResourcesByLabel(
        controlHost: ClusterHost,
        namespace: String,
        labelKey: String,
        labelValues: List<String>,
    ): Result<Unit>

    /**
     * Creates a Kubernetes secret for ClickHouse S3 storage configuration.
     *
     * This creates a secret containing only the S3 endpoint URL. AWS credentials
     * are obtained from EC2 instance IAM roles via the instance metadata service.
     *
     * @param controlHost The control node running the K3s server
     * @param namespace The namespace to create the secret in
     * @param region AWS region for the S3 bucket
     * @param bucket S3 bucket name
     * @return Result indicating success or failure
     */
    fun createClickHouseS3Secret(
        controlHost: ClusterHost,
        namespace: String,
        region: String,
        bucket: String,
    ): Result<Unit>

    /**
     * Applies a single Kubernetes manifest from resources.
     *
     * @param controlHost The control node running the K3s server
     * @param resourcePath Path to the manifest resource (relative to k8s/ directory)
     * @return Result indicating success or failure
     */
    fun applyManifestFromResources(
        controlHost: ClusterHost,
        resourcePath: String,
    ): Result<Unit>

    /**
     * Scales a StatefulSet to the specified number of replicas.
     *
     * @param controlHost The control node running the K3s server
     * @param namespace The namespace containing the StatefulSet
     * @param statefulSetName The name of the StatefulSet to scale
     * @param replicas The desired number of replicas
     * @return Result indicating success or failure
     */
    fun scaleStatefulSet(
        controlHost: ClusterHost,
        namespace: String,
        statefulSetName: String,
        replicas: Int,
    ): Result<Unit>

    /**
     * Creates a Kubernetes Job from YAML content.
     *
     * @param controlHost The control node running the K3s server
     * @param namespace The namespace to create the job in
     * @param jobYaml YAML content defining the job
     * @return Result containing the job name or failure
     */
    fun createJob(
        controlHost: ClusterHost,
        namespace: String,
        jobYaml: String,
    ): Result<String>

    /**
     * Deletes a Kubernetes Job.
     *
     * @param controlHost The control node running the K3s server
     * @param namespace The namespace containing the job
     * @param jobName The name of the job to delete
     * @return Result indicating success or failure
     */
    fun deleteJob(
        controlHost: ClusterHost,
        namespace: String,
        jobName: String,
    ): Result<Unit>

    /**
     * Gets all jobs matching a label selector.
     *
     * @param controlHost The control node running the K3s server
     * @param namespace The namespace to search in
     * @param labelKey The label key to match
     * @param labelValue The label value to match
     * @return Result containing list of jobs or failure
     */
    fun getJobsByLabel(
        controlHost: ClusterHost,
        namespace: String,
        labelKey: String,
        labelValue: String,
    ): Result<List<KubernetesJob>>

    /**
     * Gets pods associated with a job.
     *
     * @param controlHost The control node running the K3s server
     * @param namespace The namespace containing the job
     * @param jobName The name of the job
     * @return Result containing list of pods or failure
     */
    fun getPodsForJob(
        controlHost: ClusterHost,
        namespace: String,
        jobName: String,
    ): Result<List<KubernetesPod>>

    /**
     * Gets logs from a pod.
     *
     * @param controlHost The control node running the K3s server
     * @param namespace The namespace containing the pod
     * @param podName The name of the pod
     * @param tailLines Optional number of lines to return from the end
     * @return Result containing log content or failure
     */
    fun getPodLogs(
        controlHost: ClusterHost,
        namespace: String,
        podName: String,
        tailLines: Int? = null,
    ): Result<String>

    /**
     * Creates a ConfigMap from data.
     *
     * @param controlHost The control node running the K3s server
     * @param namespace The namespace to create the ConfigMap in
     * @param name The name of the ConfigMap
     * @param data Map of keys to values
     * @param labels Optional labels to apply
     * @return Result indicating success or failure
     */
    fun createConfigMap(
        controlHost: ClusterHost,
        namespace: String,
        name: String,
        data: Map<String, String>,
        labels: Map<String, String> = emptyMap(),
    ): Result<Unit>

    /**
     * Deletes a ConfigMap.
     *
     * @param controlHost The control node running the K3s server
     * @param namespace The namespace containing the ConfigMap
     * @param name The name of the ConfigMap to delete
     * @return Result indicating success or failure
     */
    fun deleteConfigMap(
        controlHost: ClusterHost,
        namespace: String,
        name: String,
    ): Result<Unit>

    /**
     * Applies a Kubernetes manifest from YAML content.
     *
     * @param controlHost The control node running the K3s server
     * @param yamlContent YAML content to apply
     * @return Result indicating success or failure
     */
    fun applyYaml(
        controlHost: ClusterHost,
        yamlContent: String,
    ): Result<Unit>

    /**
     * Labels a Kubernetes node.
     *
     * @param controlHost The control node running the K3s server
     * @param nodeName The name of the node to label
     * @param labels Map of label keys to values
     * @return Result indicating success or failure
     */
    fun labelNode(
        controlHost: ClusterHost,
        nodeName: String,
        labels: Map<String, String>,
    ): Result<Unit>

    /**
     * Creates Local PersistentVolumes for a database with node affinity.
     *
     * Creates one PV per node, with each PV having node affinity to ensure
     * pods are pinned to specific nodes. This is used to guarantee pod-to-node
     * mapping for StatefulSets.
     *
     * PVs are pre-bound to specific PVCs using claimRef, ensuring deterministic
     * binding (e.g., data-clickhouse-0 PVC always binds to the PV on node ordinal 0).
     *
     * @param controlHost The control node running the K3s server
     * @param dbName Name of the database/StatefulSet (e.g., "clickhouse")
     * @param localPath Path on the node where data is stored
     * @param count Number of PVs to create (one per node)
     * @param storageSize Size of each PV (e.g., "100Gi"). Kubernetes requires a capacity.
     * @param storageClass Name of the StorageClass to use
     * @param namespace Namespace where the StatefulSet runs (for claimRef binding)
     * @param volumeClaimTemplateName Name of the volumeClaimTemplate in the StatefulSet
     * @return Result indicating success or failure
     */
    fun createLocalPersistentVolumes(
        controlHost: ClusterHost,
        dbName: String,
        localPath: String,
        count: Int,
        storageSize: String,
        storageClass: String = "local-storage",
        namespace: String = "default",
        volumeClaimTemplateName: String = "data",
    ): Result<Unit>
}

/**
 * Default implementation of K8sService using fabric8 Kubernetes client via SOCKS proxy.
 *
 * This implementation connects to the K3s cluster through a SOCKS5 proxy tunnel,
 * allowing direct API access to private clusters.
 *
 * @property socksProxyService Service for managing the SOCKS5 proxy connection
 * @property outputHandler Handler for user-facing output messages
 */
class DefaultK8sService(
    private val socksProxyService: SocksProxyService,
    private val outputHandler: OutputHandler,
    private val telemetryProvider: TelemetryProvider,
) : K8sService {
    private val log = KotlinLogging.logger {}

    /**
     * Creates a Kubernetes client connected via SOCKS proxy.
     */
    fun createClient(controlHost: ClusterHost): KubernetesClient {
        log.info { "Creating K8s client for ${controlHost.alias} (${controlHost.privateIp})" }

        // Ensure proxy is running
        socksProxyService.ensureRunning(controlHost)
        val proxyPort = socksProxyService.getLocalPort()
        log.info { "SOCKS proxy running on localhost:$proxyPort" }

        // Create factory with current proxy port
        val clientFactory =
            ProxiedKubernetesClientFactory(
                proxyHost = "localhost",
                proxyPort = proxyPort,
            )

        // Create client using local kubeconfig
        val kubeconfigPath = Path.of(Constants.K3s.LOCAL_KUBECONFIG)
        log.info { "Using kubeconfig from $kubeconfigPath" }

        val client = clientFactory.createClient(kubeconfigPath)
        log.info { "K8s client created, master URL: ${client.configuration.masterUrl}" }

        return client
    }

    override fun applyManifests(
        controlHost: ClusterHost,
        manifestPath: Path,
    ): Result<Unit> =
        runCatching {
            val attributes =
                mapOf(
                    TelemetryNames.Attributes.HOST_ALIAS to controlHost.alias,
                    TelemetryNames.Attributes.FILE_PATH_LOCAL to manifestPath.toString(),
                )
            telemetryProvider.withSpan(TelemetryNames.Spans.K8S_APPLY_MANIFESTS, attributes) {
                log.info { "Applying K8s manifests from $manifestPath via SOCKS proxy" }

                createClient(controlHost).use { client ->
                    outputHandler.handleMessage("Applying K8s manifests...")

                    val pathFile = manifestPath.toFile()
                    val manifestFiles =
                        if (pathFile.isFile) {
                            // Single file - apply just this file
                            listOf(pathFile)
                        } else {
                            // Directory - get all YAML files sorted lexicographically
                            // Files use number prefixes (00-, 10-, etc.) to ensure correct ordering
                            pathFile
                                .listFiles { file ->
                                    file.extension == "yaml" || file.extension == "yml"
                                }?.sorted() ?: emptyList()
                        }

                    check(manifestFiles.isNotEmpty()) { "No manifest files found at $manifestPath" }

                    log.info { "Found ${manifestFiles.size} manifest files to apply" }
                    manifestFiles.forEachIndexed { index, file ->
                        log.info { "  [$index] ${file.name}" }
                    }

                    for ((index, file) in manifestFiles.withIndex()) {
                        log.info { "Processing manifest ${index + 1}/${manifestFiles.size}: ${file.name}" }
                        ManifestApplier.applyManifest(client, file)
                    }

                    log.info { "All ${manifestFiles.size} manifests applied successfully" }
                    outputHandler.handleMessage("K8s manifests applied successfully")
                }
            }
        }

    override fun getObservabilityStatus(controlHost: ClusterHost): Result<String> =
        runCatching {
            log.debug { "Getting observability status via SOCKS proxy" }

            createClient(controlHost).use { client ->
                val pods =
                    client
                        .pods()
                        .inNamespace(Constants.K8s.NAMESPACE)
                        .list()

                // Format output similar to kubectl get pods -o wide
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
                        val age = "N/A" // Simplified - could calculate from creationTimestamp

                        "%-30s %-7s %-9s %-10d %s".format(name, ready, status, restarts, age)
                    }

                (listOf(header) + lines).joinToString("\n")
            }
        }

    override fun deleteObservability(controlHost: ClusterHost): Result<Unit> =
        runCatching {
            log.debug { "Deleting observability namespace via SOCKS proxy" }

            outputHandler.handleMessage("Deleting observability namespace...")

            createClient(controlHost).use { client ->
                val namespace = client.namespaces().withName(Constants.K8s.NAMESPACE).get()
                if (namespace != null) {
                    client.namespaces().withName(Constants.K8s.NAMESPACE).delete()
                    log.info { "Deleted namespace: ${Constants.K8s.NAMESPACE}" }
                } else {
                    log.info { "Namespace ${Constants.K8s.NAMESPACE} does not exist, nothing to delete" }
                }
            }

            outputHandler.handleMessage("Observability namespace deleted")
        }

    override fun waitForPodsReady(
        controlHost: ClusterHost,
        timeoutSeconds: Int,
    ): Result<Unit> =
        runCatching {
            log.debug { "Waiting for pods to be ready in ${Constants.K8s.NAMESPACE} namespace" }

            outputHandler.handleMessage("Waiting for observability pods to be ready...")

            createClient(controlHost).use { client ->
                val pods =
                    client
                        .pods()
                        .inNamespace(Constants.K8s.NAMESPACE)
                        .list()

                if (pods.items.isEmpty()) {
                    log.warn { "No pods found in ${Constants.K8s.NAMESPACE} namespace" }
                    return@runCatching
                }

                // Wait for each pod to be ready
                for (pod in pods.items) {
                    val podName = pod.metadata?.name ?: continue
                    log.debug { "Waiting for pod $podName to be ready" }

                    client
                        .pods()
                        .inNamespace(Constants.K8s.NAMESPACE)
                        .withName(podName)
                        .waitUntilCondition(
                            { p ->
                                p?.status?.conditions?.any {
                                    it.type == "Ready" && it.status == "True"
                                } == true
                            },
                            timeoutSeconds.toLong(),
                            TimeUnit.SECONDS,
                        )
                }
            }

            outputHandler.handleMessage("All observability pods are ready")
        }

    override fun waitForPodsReady(
        controlHost: ClusterHost,
        timeoutSeconds: Int,
        namespace: String,
    ): Result<Unit> =
        runCatching {
            log.debug { "Waiting for pods to be ready in $namespace namespace" }

            outputHandler.handleMessage("Waiting for pods in $namespace to be ready...")

            createClient(controlHost).use { client ->
                val pods =
                    client
                        .pods()
                        .inNamespace(namespace)
                        .list()

                if (pods.items.isEmpty()) {
                    log.warn { "No pods found in $namespace namespace" }
                    return@runCatching
                }

                // Wait for each pod to be ready
                for (pod in pods.items) {
                    val podName = pod.metadata?.name ?: continue
                    log.debug { "Waiting for pod $podName to be ready" }

                    client
                        .pods()
                        .inNamespace(namespace)
                        .withName(podName)
                        .waitUntilCondition(
                            { p ->
                                p?.status?.conditions?.any {
                                    it.type == "Ready" && it.status == "True"
                                } == true
                            },
                            timeoutSeconds.toLong(),
                            TimeUnit.SECONDS,
                        )
                }
            }

            outputHandler.handleMessage("All pods in $namespace are ready")
        }

    override fun getNamespaceStatus(
        controlHost: ClusterHost,
        namespace: String,
    ): Result<String> =
        runCatching {
            log.debug { "Getting status for namespace $namespace via SOCKS proxy" }

            createClient(controlHost).use { client ->
                val pods =
                    client
                        .pods()
                        .inNamespace(namespace)
                        .list()

                // Format output similar to kubectl get pods -o wide
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
                        val age = "N/A" // Simplified - could calculate from creationTimestamp

                        "%-30s %-7s %-9s %-10d %s".format(name, ready, status, restarts, age)
                    }

                (listOf(header) + lines).joinToString("\n")
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

                outputHandler.handleMessage("Deleting $namespace namespace...")

                createClient(controlHost).use { client ->
                    val ns = client.namespaces().withName(namespace).get()
                    if (ns != null) {
                        client.namespaces().withName(namespace).delete()
                        log.info { "Deleted namespace: $namespace" }
                    } else {
                        log.info { "Namespace $namespace does not exist, nothing to delete" }
                    }
                }

                outputHandler.handleMessage("Namespace $namespace deleted")
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

            outputHandler.handleMessage("Deleting resources with label $labelKey...")

            createClient(controlHost).use { client ->
                // Build label selector for "key in (value1, value2, ...)"
                val labelSelector = "$labelKey in (${labelValues.joinToString(",")})"
                log.debug { "Using label selector: $labelSelector" }

                // Delete StatefulSets first (they manage pods)
                val statefulSets =
                    client
                        .apps()
                        .statefulSets()
                        .inNamespace(namespace)
                        .withLabelSelector(labelSelector)
                        .list()
                statefulSets.items.forEach { sts ->
                    val name = sts.metadata?.name ?: return@forEach
                    log.info { "Deleting StatefulSet: $name" }
                    client
                        .apps()
                        .statefulSets()
                        .inNamespace(namespace)
                        .withName(name)
                        .delete()
                }

                // Delete Deployments
                val deployments =
                    client
                        .apps()
                        .deployments()
                        .inNamespace(namespace)
                        .withLabelSelector(labelSelector)
                        .list()
                deployments.items.forEach { deploy ->
                    val name = deploy.metadata?.name ?: return@forEach
                    log.info { "Deleting Deployment: $name" }
                    client
                        .apps()
                        .deployments()
                        .inNamespace(namespace)
                        .withName(name)
                        .delete()
                }

                // Delete Services
                val services =
                    client
                        .services()
                        .inNamespace(namespace)
                        .withLabelSelector(labelSelector)
                        .list()
                services.items.forEach { svc ->
                    val name = svc.metadata?.name ?: return@forEach
                    log.info { "Deleting Service: $name" }
                    client
                        .services()
                        .inNamespace(namespace)
                        .withName(name)
                        .delete()
                }

                // Delete ConfigMaps
                val configMaps =
                    client
                        .configMaps()
                        .inNamespace(namespace)
                        .withLabelSelector(labelSelector)
                        .list()
                configMaps.items.forEach { cm ->
                    val name = cm.metadata?.name ?: return@forEach
                    log.info { "Deleting ConfigMap: $name" }
                    client
                        .configMaps()
                        .inNamespace(namespace)
                        .withName(name)
                        .delete()
                }

                // Delete Secrets
                val secrets =
                    client
                        .secrets()
                        .inNamespace(namespace)
                        .withLabelSelector(labelSelector)
                        .list()
                secrets.items.forEach { secret ->
                    val name = secret.metadata?.name ?: return@forEach
                    log.info { "Deleting Secret: $name" }
                    client
                        .secrets()
                        .inNamespace(namespace)
                        .withName(name)
                        .delete()
                }

                // Delete PVCs (persistent volume claims)
                val pvcs =
                    client
                        .persistentVolumeClaims()
                        .inNamespace(namespace)
                        .withLabelSelector(labelSelector)
                        .list()
                pvcs.items.forEach { pvc ->
                    val name = pvc.metadata?.name ?: return@forEach
                    log.info { "Deleting PVC: $name" }
                    client
                        .persistentVolumeClaims()
                        .inNamespace(namespace)
                        .withName(name)
                        .delete()
                }

                log.info { "All resources with label $labelKey deleted" }
            }

            outputHandler.handleMessage("Resources deleted successfully")
        }

    override fun createClickHouseS3Secret(
        controlHost: ClusterHost,
        namespace: String,
        region: String,
        bucket: String,
    ): Result<Unit> =
        runCatching {
            log.info { "Creating ClickHouse S3 secret in namespace $namespace" }

            // Build the S3 endpoint URL
            val s3Endpoint = "https://$bucket.s3.$region.amazonaws.com/clickhouse/"
            log.info { "S3 endpoint: $s3Endpoint" }

            createClient(controlHost).use { client ->
                // Check if secret already exists and delete it
                val existingSecret =
                    client
                        .secrets()
                        .inNamespace(namespace)
                        .withName(Constants.ClickHouse.S3_SECRET_NAME)
                        .get()

                if (existingSecret != null) {
                    log.info { "Deleting existing secret ${Constants.ClickHouse.S3_SECRET_NAME}" }
                    client
                        .secrets()
                        .inNamespace(namespace)
                        .withName(Constants.ClickHouse.S3_SECRET_NAME)
                        .delete()
                }

                // Create the secret with S3 endpoint
                val secret =
                    io.fabric8.kubernetes.api.model
                        .SecretBuilder()
                        .withNewMetadata()
                        .withName(Constants.ClickHouse.S3_SECRET_NAME)
                        .withNamespace(namespace)
                        .addToLabels("app.kubernetes.io/name", "clickhouse-server")
                        .endMetadata()
                        .withType("Opaque")
                        .addToStringData("CLICKHOUSE_S3_ENDPOINT", s3Endpoint)
                        .build()

                client
                    .secrets()
                    .inNamespace(namespace)
                    .resource(secret)
                    .create()
                log.info { "Created secret ${Constants.ClickHouse.S3_SECRET_NAME}" }
            }

            outputHandler.handleMessage("Created ClickHouse S3 secret")
        }

    override fun applyManifestFromResources(
        controlHost: ClusterHost,
        resourcePath: String,
    ): Result<Unit> =
        runCatching {
            log.info { "Applying K8s manifest from resources: $resourcePath" }

            createClient(controlHost).use { client ->
                // Load manifest from resources
                val resourceStream =
                    this::class.java.getResourceAsStream("/com/rustyrazorblade/easydblab/commands/$resourcePath")
                        ?: error("Resource not found: $resourcePath")

                // Create temp file from resource
                val tempFile =
                    kotlin.io.path
                        .createTempFile("manifest", ".yaml")
                        .toFile()
                try {
                    resourceStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    log.info { "Applying manifest: ${tempFile.name}" }
                    ManifestApplier.applyManifest(client, tempFile)
                    log.info { "Manifest applied successfully" }
                } finally {
                    tempFile.delete()
                }
            }

            outputHandler.handleMessage("Manifest applied: $resourcePath")
        }

    override fun scaleStatefulSet(
        controlHost: ClusterHost,
        namespace: String,
        statefulSetName: String,
        replicas: Int,
    ): Result<Unit> =
        runCatching {
            val attributes =
                mapOf(
                    TelemetryNames.Attributes.HOST_ALIAS to controlHost.alias,
                    TelemetryNames.Attributes.K8S_NAMESPACE to namespace,
                    TelemetryNames.Attributes.K8S_RESOURCE_NAME to statefulSetName,
                )
            telemetryProvider.withSpan(TelemetryNames.Spans.K8S_SCALE_STATEFULSET, attributes) {
                log.info { "Scaling StatefulSet $statefulSetName in namespace $namespace to $replicas replicas" }

                createClient(controlHost).use { client ->
                    client
                        .apps()
                        .statefulSets()
                        .inNamespace(namespace)
                        .withName(statefulSetName)
                        .scale(replicas)

                    log.info { "StatefulSet $statefulSetName scaled to $replicas replicas" }
                }

                outputHandler.handleMessage("Scaled $statefulSetName to $replicas replicas")
            }
        }

    override fun createJob(
        controlHost: ClusterHost,
        namespace: String,
        jobYaml: String,
    ): Result<String> =
        runCatching {
            log.info { "Creating K8s job in namespace $namespace" }

            createClient(controlHost).use { client ->
                ManifestApplier.applyYaml(client, jobYaml)

                // Extract job name from YAML for return value
                val yamlLines = jobYaml.lines()
                val nameLine = yamlLines.find { it.trim().startsWith("name:") }
                val jobName =
                    nameLine?.substringAfter("name:")?.trim()?.trim('"', '\'')
                        ?: error("Could not extract job name from YAML")

                log.info { "Created job: $jobName" }
                jobName
            }
        }

    override fun deleteJob(
        controlHost: ClusterHost,
        namespace: String,
        jobName: String,
    ): Result<Unit> =
        runCatching {
            log.info { "Deleting job $jobName from namespace $namespace" }

            createClient(controlHost).use { client ->
                // Delete associated pods first (propagation policy)
                client
                    .batch()
                    .v1()
                    .jobs()
                    .inNamespace(namespace)
                    .withName(jobName)
                    .withPropagationPolicy(io.fabric8.kubernetes.api.model.DeletionPropagation.FOREGROUND)
                    .delete()

                log.info { "Deleted job: $jobName" }
            }

            outputHandler.handleMessage("Deleted job: $jobName")
        }

    override fun getJobsByLabel(
        controlHost: ClusterHost,
        namespace: String,
        labelKey: String,
        labelValue: String,
    ): Result<List<KubernetesJob>> =
        runCatching {
            log.debug { "Getting jobs with label $labelKey=$labelValue in namespace $namespace" }

            createClient(controlHost).use { client ->
                val jobs =
                    client
                        .batch()
                        .v1()
                        .jobs()
                        .inNamespace(namespace)
                        .withLabel(labelKey, labelValue)
                        .list()

                jobs.items.map { job ->
                    val name = job.metadata?.name ?: "unknown"
                    val succeeded = job.status?.succeeded ?: 0
                    val failed = job.status?.failed ?: 0
                    val active = job.status?.active ?: 0
                    val completions = job.spec?.completions ?: 1

                    val status =
                        when {
                            succeeded >= completions -> "Completed"
                            failed > 0 -> "Failed"
                            active > 0 -> "Running"
                            else -> "Pending"
                        }

                    val creationTime =
                        job.metadata?.creationTimestamp?.let {
                            Instant.parse(it)
                        } ?: Instant.now()
                    val age = Duration.between(creationTime, Instant.now())

                    KubernetesJob(
                        namespace = namespace,
                        name = name,
                        status = status,
                        completions = "$succeeded/$completions",
                        age = age,
                    )
                }
            }
        }

    override fun getPodsForJob(
        controlHost: ClusterHost,
        namespace: String,
        jobName: String,
    ): Result<List<KubernetesPod>> =
        runCatching {
            log.debug { "Getting pods for job $jobName in namespace $namespace" }

            createClient(controlHost).use { client ->
                val pods =
                    client
                        .pods()
                        .inNamespace(namespace)
                        .withLabel("job-name", jobName)
                        .list()

                pods.items.map { pod ->
                    val name = pod.metadata?.name ?: "unknown"
                    val containerStatuses = pod.status?.containerStatuses ?: emptyList()
                    val readyContainers = containerStatuses.count { it.ready == true }
                    val totalContainers = containerStatuses.size.coerceAtLeast(1)
                    val status = pod.status?.phase ?: "Unknown"
                    val restarts = containerStatuses.sumOf { it.restartCount ?: 0 }

                    val creationTime =
                        pod.metadata?.creationTimestamp?.let {
                            Instant.parse(it)
                        } ?: Instant.now()
                    val age = Duration.between(creationTime, Instant.now())

                    KubernetesPod(
                        namespace = namespace,
                        name = name,
                        status = status,
                        ready = "$readyContainers/$totalContainers",
                        restarts = restarts,
                        age = age,
                    )
                }
            }
        }

    override fun getPodLogs(
        controlHost: ClusterHost,
        namespace: String,
        podName: String,
        tailLines: Int?,
    ): Result<String> =
        runCatching {
            log.debug { "Getting logs for pod $podName in namespace $namespace" }

            createClient(controlHost).use { client ->
                val logRequest =
                    client
                        .pods()
                        .inNamespace(namespace)
                        .withName(podName)

                val logs =
                    if (tailLines != null) {
                        logRequest.tailingLines(tailLines).log
                    } else {
                        logRequest.log
                    }

                logs ?: ""
            }
        }

    override fun createConfigMap(
        controlHost: ClusterHost,
        namespace: String,
        name: String,
        data: Map<String, String>,
        labels: Map<String, String>,
    ): Result<Unit> =
        runCatching {
            log.info { "Creating ConfigMap $name in namespace $namespace" }

            createClient(controlHost).use { client ->
                // Delete existing ConfigMap if it exists
                val existing =
                    client
                        .configMaps()
                        .inNamespace(namespace)
                        .withName(name)
                        .get()

                if (existing != null) {
                    log.info { "Deleting existing ConfigMap $name" }
                    client
                        .configMaps()
                        .inNamespace(namespace)
                        .withName(name)
                        .delete()
                }

                // Create new ConfigMap
                val configMapBuilder =
                    io.fabric8.kubernetes.api.model
                        .ConfigMapBuilder()
                        .withNewMetadata()
                        .withName(name)
                        .withNamespace(namespace)

                // Add labels
                labels.forEach { (key, value) ->
                    configMapBuilder.addToLabels(key, value)
                }

                val metadataFinished = configMapBuilder.endMetadata()

                // Add data entries
                data.forEach { (key, value) ->
                    metadataFinished.addToData(key, value)
                }

                val configMap = metadataFinished.build()

                client
                    .configMaps()
                    .inNamespace(namespace)
                    .resource(configMap)
                    .create()

                log.info { "Created ConfigMap: $name" }
            }

            outputHandler.handleMessage("Created ConfigMap: $name")
        }

    override fun deleteConfigMap(
        controlHost: ClusterHost,
        namespace: String,
        name: String,
    ): Result<Unit> =
        runCatching {
            log.info { "Deleting ConfigMap $name from namespace $namespace" }

            createClient(controlHost).use { client ->
                client
                    .configMaps()
                    .inNamespace(namespace)
                    .withName(name)
                    .delete()

                log.info { "Deleted ConfigMap: $name" }
            }

            outputHandler.handleMessage("Deleted ConfigMap: $name")
        }

    override fun applyYaml(
        controlHost: ClusterHost,
        yamlContent: String,
    ): Result<Unit> =
        runCatching {
            val attributes =
                mapOf(
                    TelemetryNames.Attributes.HOST_ALIAS to controlHost.alias,
                )
            telemetryProvider.withSpan(TelemetryNames.Spans.K8S_APPLY_YAML, attributes) {
                log.info { "Applying YAML content via SOCKS proxy" }

                createClient(controlHost).use { client ->
                    ManifestApplier.applyYaml(client, yamlContent)
                    log.info { "YAML content applied successfully" }
                }
            }
        }

    override fun labelNode(
        controlHost: ClusterHost,
        nodeName: String,
        labels: Map<String, String>,
    ): Result<Unit> =
        runCatching {
            log.info { "Labeling node $nodeName with labels: $labels" }

            createClient(controlHost).use { client ->
                val node =
                    client.nodes().withName(nodeName).get()
                        ?: error("Node $nodeName not found")

                val existingLabels = node.metadata.labels ?: mutableMapOf()
                val updatedLabels = existingLabels.toMutableMap()
                updatedLabels.putAll(labels)

                client.nodes().withName(nodeName).edit { n ->
                    n.metadata.labels = updatedLabels
                    n
                }

                log.info { "Labeled node $nodeName" }
            }
        }

    override fun createLocalPersistentVolumes(
        controlHost: ClusterHost,
        dbName: String,
        localPath: String,
        count: Int,
        storageSize: String,
        storageClass: String,
        namespace: String,
        volumeClaimTemplateName: String,
    ): Result<Unit> =
        runCatching {
            log.info { "Creating $count Local PVs for $dbName" }

            createClient(controlHost).use { client ->
                for (ordinal in 0 until count) {
                    // PVC naming convention: {volumeClaimTemplateName}-{statefulSetName}-{ordinal}
                    // e.g., data-clickhouse-0, data-clickhouse-1, etc.
                    val pvcName = "$volumeClaimTemplateName-$dbName-$ordinal"
                    // Use same name for PV to make relationship clear
                    val pvName = pvcName

                    // Check if PV already exists
                    val existing = client.persistentVolumes().withName(pvName).get()
                    if (existing != null) {
                        log.info { "PV $pvName already exists, skipping" }
                        continue
                    }

                    val pv =
                        io.fabric8.kubernetes.api.model
                            .PersistentVolumeBuilder()
                            .withNewMetadata()
                            .withName(pvName)
                            .addToLabels("app.kubernetes.io/name", dbName)
                            .addToLabels("app.kubernetes.io/component", "data")
                            .endMetadata()
                            .withNewSpec()
                            .addToCapacity(
                                "storage",
                                io.fabric8.kubernetes.api.model
                                    .Quantity(storageSize),
                            ).withAccessModes("ReadWriteOnce")
                            .withPersistentVolumeReclaimPolicy("Retain")
                            .withStorageClassName(storageClass)
                            .withNewLocal()
                            .withPath(localPath)
                            .endLocal()
                            // Pre-bind to specific PVC to guarantee deterministic binding
                            .withNewClaimRef()
                            .withName(pvcName)
                            .withNamespace(namespace)
                            .endClaimRef()
                            .withNewNodeAffinity()
                            .withNewRequired()
                            .addNewNodeSelectorTerm()
                            .addNewMatchExpression()
                            .withKey(Constants.NODE_ORDINAL_LABEL)
                            .withOperator("In")
                            .withValues(ordinal.toString())
                            .endMatchExpression()
                            .endNodeSelectorTerm()
                            .endRequired()
                            .endNodeAffinity()
                            .endSpec()
                            .build()

                    client.persistentVolumes().resource(pv).create()
                    log.info { "Created PV $pvName pre-bound to PVC $pvcName on node ordinal $ordinal" }
                }

                outputHandler.handleMessage("Created $count Local PVs for $dbName")
            }
        }
}
