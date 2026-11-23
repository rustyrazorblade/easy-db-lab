package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kubernetes.ManifestApplier
import com.rustyrazorblade.easydblab.messageOrString
import com.rustyrazorblade.easydblab.runCatchingException
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.base.PatchContext
import io.fabric8.kubernetes.client.dsl.base.PatchType
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
    override fun applyManifests(manifestPath: Path): Result<Unit> =
        runCatching {
            log.info { "Applying K8s manifests from $manifestPath via SOCKS proxy" }

            clientProvider.createClient().use { client ->
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

    override fun applyManifestFromResources(resourcePath: String): Result<Unit> =
        runCatching {
            log.info { "Applying K8s manifest from resources: $resourcePath" }

            clientProvider.createClient().use { client ->
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

    override fun applyYaml(yamlContent: String): Result<Unit> =
        runCatching {
            log.info { "Applying YAML content via SOCKS proxy" }

            clientProvider.createClient().use { client ->
                ManifestApplier.applyYaml(client, yamlContent)
                log.info { "YAML content applied successfully" }
            }
        }

    override fun applyResource(resource: HasMetadata): Result<Unit> =
        runCatching {
            val kind = resource.kind ?: "Unknown"
            val name = resource.metadata?.name ?: "unknown"
            log.info { "Applying $kind/$name via server-side apply" }

            clientProvider.createClient().use { client ->
                try {
                    client
                        .resource(resource)
                        .forceConflicts()
                        .serverSideApply()
                } catch (e: KubernetesClientException) {
                    // The API server's refusal of an immutable field change; fabric8 reports every API answer as this type.
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
    private fun requiresDeleteAndRecreate(e: KubernetesClientException): Boolean {
        val msg = e.message ?: return false
        // Strategy type conflict (e.g., RollingUpdate → Recreate)
        if (msg.contains("rollingUpdate") && msg.contains("Recreate")) return true
        // StatefulSet immutable spec fields (e.g., adding/changing volumeClaimTemplates)
        if (msg.contains("updates to statefulset spec") && msg.contains("Forbidden")) return true
        return false
    }

    override fun labelNode(
        nodeName: String,
        labels: Map<String, String>,
    ): Result<Unit> = runCatching { labelNodes(listOf(nodeName), labels).getValue(nodeName).getOrThrow() }

    override fun labelNodes(
        nodeNames: List<String>,
        labels: Map<String, String>,
    ): Map<String, Result<Unit>> =
        clientProvider.createClient().use { client ->
            val outcomes = linkedMapOf<String, Result<Unit>>()
            var stop: Pair<String, Throwable>? = null
            for (nodeName in nodeNames) {
                val outcome = runCatchingException { patchLabels(client, nodeName, labels) }
                outcomes[nodeName] = outcome
                stop = outcome.exceptionOrNull()?.takeIf(::endsTheBatch)?.let { nodeName to it }
                if (stop != null) break
            }
            stop?.let { (failedNode, cause) ->
                nodeNames.filterNot(outcomes::containsKey).forEach { outcomes[it] = Result.failure(notTried(it, failedNode, cause)) }
            }
            outcomes
        }

    /**
     * Whether [failure] says the API cannot be reached at all, so every later node would wait out the
     * same request timeout in turn. fabric8 gives a failure with no HTTP answer the code -1. An
     * answer about one node (404, 403, a missing node) leaves the rest worth
     * trying. An interrupt ends the batch too: fabric8 sets the flag again and every later call
     * would fail on it at once.
     */
    private fun endsTheBatch(failure: Throwable): Boolean =
        Thread.currentThread().isInterrupted || (failure is KubernetesClientException && failure.code <= 0)

    private fun notTried(
        nodeName: String,
        failedNode: String,
        cause: Throwable,
    ) = IllegalStateException("Node $nodeName was not tried, because labelling $failedNode failed: ${cause.messageOrString()}", cause)

    private fun patchLabels(
        client: KubernetesClient,
        nodeName: String,
        labels: Map<String, String>,
    ) {
        log.info { "Labeling node $nodeName with labels: $labels" }
        checkNotNull(client.nodes().withName(nodeName).get()) { "Node $nodeName not found" }

        // A merge patch carries no resourceVersion. `edit()` patches the version it read, and
        // the kubelet's status updates in between make the API server refuse it with 409 Conflict.
        val patch =
            NodeBuilder()
                .withNewMetadata()
                .addToLabels(labels)
                .endMetadata()
                .build()
        client.nodes().withName(nodeName).patch(PatchContext.of(PatchType.STRATEGIC_MERGE), patch)

        log.info { "Labeled node $nodeName" }
    }
}
