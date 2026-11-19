package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.HasMetadata
import java.nio.file.Path

/**
 * A [K8sManifestOperations] that records each typed resource it is given to apply, or fails every
 * apply with [failure]. The other operations are not used by the callers that take it and fail if
 * called, so a test notices when one starts using them.
 */
class RecordingManifests : K8sManifestOperations {
    val applied = mutableListOf<HasMetadata>()
    var failure: Exception? = null

    override fun applyResource(resource: HasMetadata): Result<Unit> =
        failure?.let { Result.failure(it) } ?: Result.success(Unit).also { applied += resource }

    override fun applyManifests(manifestPath: Path): Result<Unit> = error("not used")

    override fun applyManifestFromResources(resourcePath: String): Result<Unit> = error("not used")

    override fun applyYaml(yamlContent: String): Result<Unit> = error("not used")

    override fun labelNode(
        nodeName: String,
        labels: Map<String, String>,
    ): Result<Unit> = error("not used")
}
