package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kubernetes.ManifestApplier
import io.fabric8.kubernetes.api.model.HasMetadata
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path

private val log = KotlinLogging.logger {}

/**
 * Implementation of manifest-related K8s operations: applying manifests from files,
 * resources, YAML strings, typed resources, and labeling nodes.
 */
class DefaultK8sManifestOperations(
    private val clientProvider: K8sClientProvider,
    private val eventBus: EventBus,
) : K8sManifestOperations {
    override fun applyManifests(
        controlHost: ClusterHost,
        manifestPath: Path,
    ): Result<Unit> =
        runCatching {
            log.info { "Applying K8s manifests from $manifestPath via SOCKS proxy" }

            clientProvider.createClient(controlHost).use { client ->
                eventBus.emit(Event.K8s.ManifestsApplying)

                val pathFile = manifestPath.toFile()
                val manifestFiles =
                    if (pathFile.isFile) {
                        listOf(pathFile)
                    } else {
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
                eventBus.emit(Event.K8s.ManifestsApplied)
            }
        }

    override fun applyManifestFromResources(
        controlHost: ClusterHost,
        resourcePath: String,
    ): Result<Unit> =
        runCatching {
            log.info { "Applying K8s manifest from resources: $resourcePath" }

            clientProvider.createClient(controlHost).use { client ->
                val resourceStream =
                    this::class.java.getResourceAsStream("/com/rustyrazorblade/easydblab/commands/$resourcePath")
                        ?: error("Resource not found: $resourcePath")

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

            eventBus.emit(Event.K8s.ManifestApplied(resourcePath))
        }

    override fun applyYaml(
        controlHost: ClusterHost,
        yamlContent: String,
    ): Result<Unit> =
        runCatching {
            log.info { "Applying YAML content via SOCKS proxy" }

            clientProvider.createClient(controlHost).use { client ->
                ManifestApplier.applyYaml(client, yamlContent)
                log.info { "YAML content applied successfully" }
            }
        }

    override fun applyResource(
        controlHost: ClusterHost,
        resource: HasMetadata,
    ): Result<Unit> =
        runCatching {
            val kind = resource.kind ?: "Unknown"
            val name = resource.metadata?.name ?: "unknown"
            log.info { "Applying $kind/$name via server-side apply" }

            clientProvider.createClient(controlHost).use { client ->
                try {
                    client
                        .resource(resource)
                        .forceConflicts()
                        .serverSideApply()
                } catch (e: Exception) {
                    if (requiresDeleteAndRecreate(e)) {
                        log.info { "$kind/$name has immutable field conflict, deleting and re-creating" }
                        client.resource(resource).delete()
                        client
                            .resource(resource)
                            .forceConflicts()
                            .serverSideApply()
                    } else {
                        throw e
                    }
                }
                log.info { "Applied $kind/$name successfully" }
            }
        }

    /**
     * Checks if a server-side apply failure requires delete-and-recreate.
     * This happens when immutable fields are changed (e.g., StatefulSet volumeClaimTemplates,
     * Deployment strategy type).
     */
    private fun requiresDeleteAndRecreate(e: Exception): Boolean {
        val msg = e.message ?: return false
        // Strategy type conflict (e.g., RollingUpdate → Recreate)
        if (msg.contains("rollingUpdate") && msg.contains("Recreate")) return true
        // StatefulSet immutable spec fields (e.g., adding/changing volumeClaimTemplates)
        if (msg.contains("updates to statefulset spec") && msg.contains("Forbidden")) return true
        return false
    }

    override fun labelNode(
        controlHost: ClusterHost,
        nodeName: String,
        labels: Map<String, String>,
    ): Result<Unit> =
        runCatching {
            log.info { "Labeling node $nodeName with labels: $labels" }

            clientProvider.createClient(controlHost).use { client ->
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
}
