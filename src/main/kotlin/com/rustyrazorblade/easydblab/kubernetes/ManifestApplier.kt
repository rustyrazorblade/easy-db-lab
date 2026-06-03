package com.rustyrazorblade.easydblab.kubernetes

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.File

@Serializable
private data class K8sManifestMeta(
    val name: String = "unknown",
)

@Serializable
private data class K8sManifestHeader(
    val kind: String? = null,
    val metadata: K8sManifestMeta = K8sManifestMeta(),
)

private val lenientYaml = Yaml(configuration = YamlConfiguration(strictMode = false))

/**
 * Utility for applying Kubernetes manifests using typed loaders.
 *
 * Fabric8's serverSideApply() only works with typed resources. The generic
 * client.load() returns GenericKubernetesResource which cannot be cast to typed
 * classes and has no handler for serverSideApply(). This utility extracts the
 * kind from YAML and routes to the appropriate typed loader.
 *
 * See docs/fabric8-server-side-apply.md for details on this issue.
 */
object ManifestApplier {
    private val log = KotlinLogging.logger {}

    /**
     * Apply a manifest file to the cluster using server-side apply.
     * Supports multi-document YAML files (documents separated by ---).
     */
    @Suppress("TooGenericExceptionCaught")
    fun applyManifest(
        client: KubernetesClient,
        file: File,
    ) {
        val documents = splitDocuments(file.readText())
        log.info { "Processing ${file.name} with ${documents.size} document(s)" }
        for ((index, docYaml) in documents.withIndex()) {
            val header = lenientYaml.decodeFromString(K8sManifestHeader.serializer(), docYaml)
            val kind = header.kind ?: error("Document ${index + 1} in ${file.name} missing 'kind' field")
            val resourceName = header.metadata.name
            log.info { "Applying document ${index + 1}/${documents.size}: $kind '$resourceName' from ${file.name}" }
            log.debug { "YAML content:\n$docYaml" }
            try {
                applySingleDocument(client, docYaml, kind)
                log.info { "Successfully applied $kind '$resourceName'" }
            } catch (e: Exception) {
                log.error(e) { "Failed to apply $kind '$resourceName': ${e.message}" }
                throw e
            }
        }
    }

    /**
     * Apply YAML content to the cluster using server-side apply.
     * Supports multi-document YAML (documents separated by ---).
     */
    fun applyYaml(
        client: KubernetesClient,
        yamlContent: String,
    ) {
        val documents = splitDocuments(yamlContent)
        for ((index, docYaml) in documents.withIndex()) {
            val header = lenientYaml.decodeFromString(K8sManifestHeader.serializer(), docYaml)
            val kind = header.kind ?: error("Document ${index + 1} missing 'kind' field")
            applySingleDocument(client, docYaml, kind)
        }
    }

    private fun splitDocuments(yamlContent: String): List<String> =
        yamlContent
            .split(Regex("(?m)^---\\s*$"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun applySingleDocument(
        client: KubernetesClient,
        yamlContent: String,
        kind: String,
    ) {
        log.debug { "Loading $kind via typed loader" }
        ByteArrayInputStream(yamlContent.toByteArray()).use { stream ->
            when (kind) {
                "Namespace" -> applyNamespace(client, stream)
                "ConfigMap" -> applyConfigMap(client, stream)
                "Service" -> applyService(client, stream)
                "DaemonSet" -> applyDaemonSet(client, stream)
                "Deployment" -> applyDeployment(client, stream)
                "StatefulSet" -> applyStatefulSet(client, stream)
                "Secret" -> applySecret(client, stream)
                "Job" -> applyJob(client, stream)
                // Generic fallback for CRDs and any other resource kind Fabric8 doesn't have a
                // typed client for. client.resource() handles any HasMetadata via the generic API.
                else -> client.resource(yamlContent).serverSideApply()
            }
        }
    }

    private fun applyNamespace(
        client: KubernetesClient,
        stream: ByteArrayInputStream,
    ) = client
        .namespaces()
        .load(stream)
        .forceConflicts()
        .serverSideApply()

    private fun applyConfigMap(
        client: KubernetesClient,
        stream: ByteArrayInputStream,
    ) = client
        .configMaps()
        .load(stream)
        .forceConflicts()
        .serverSideApply()

    private fun applyService(
        client: KubernetesClient,
        stream: ByteArrayInputStream,
    ) = client
        .services()
        .load(stream)
        .forceConflicts()
        .serverSideApply()

    private fun applyDaemonSet(
        client: KubernetesClient,
        stream: ByteArrayInputStream,
    ) = client
        .apps()
        .daemonSets()
        .load(stream)
        .forceConflicts()
        .serverSideApply()

    private fun applyDeployment(
        client: KubernetesClient,
        stream: ByteArrayInputStream,
    ) = client
        .apps()
        .deployments()
        .load(stream)
        .forceConflicts()
        .serverSideApply()

    private fun applyStatefulSet(
        client: KubernetesClient,
        stream: ByteArrayInputStream,
    ) = client
        .apps()
        .statefulSets()
        .load(stream)
        .forceConflicts()
        .serverSideApply()

    private fun applySecret(
        client: KubernetesClient,
        stream: ByteArrayInputStream,
    ) = client
        .secrets()
        .load(stream)
        .forceConflicts()
        .serverSideApply()

    private fun applyJob(
        client: KubernetesClient,
        stream: ByteArrayInputStream,
    ) = client
        .batch()
        .v1()
        .jobs()
        .load(stream)
        .forceConflicts()
        .serverSideApply()
}
