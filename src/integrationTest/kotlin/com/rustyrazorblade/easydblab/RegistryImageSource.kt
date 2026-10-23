package com.rustyrazorblade.easydblab

import com.google.cloud.tools.jib.api.Containerizer
import com.google.cloud.tools.jib.api.ImageReference
import com.google.cloud.tools.jib.api.Jib
import com.google.cloud.tools.jib.api.RegistryImage
import com.google.cloud.tools.jib.api.TarImage
import com.google.cloud.tools.jib.api.buildplan.Platform
import com.google.cloud.tools.jib.event.EventHandlers
import com.google.cloud.tools.jib.http.FailoverHttpClient
import com.google.cloud.tools.jib.registry.RegistryClient
import org.testcontainers.DockerClientFactory
import java.nio.file.Path

/**
 * An [ImageArchiveSource] that fetches each image straight from its registry, for the Docker
 * host's Linux platform, into a docker-load archive that K3s's airgap import accepts.
 *
 * It does not go through the host's Docker daemon. With the containerd image store, `docker save`
 * of an image whose registry index carries attestation manifests (the documents web server image
 * is one) exports the index and manifests but none of the layers, even right after a fresh pull,
 * and Docker reports no error. Jib pulls the platform's manifest and layers itself and writes the
 * archive. [save] still refuses an archive that lacks a layer its manifest names
 * ([DockerArchive.missingEntries]), since K3s logs such an archive and starts without the image.
 *
 * The content ID is the registry digest of the reference (for a multi-platform image, its index),
 * read with a manifest `HEAD` request, so a tag moved to new content is fetched again. Pulls are
 * anonymous. Jib keeps the layers it downloads in [layerCache], so a refetch downloads only the
 * layers that changed. A registry read that fails is tried again ([RegistryFetchRetry]).
 */
class RegistryImageSource(
    private val layerCache: Path,
    private val architecture: String = dockerHostArchitecture(),
    private val retry: RegistryFetchRetry = RegistryFetchRetry(),
) : ImageArchiveSource {
    override fun contentId(image: String): String = retry.run(image) { registryDigest(image) }

    override fun save(
        image: String,
        target: Path,
    ) {
        retry.run(image) { fetch(image, target) }
        val missing = DockerArchive.missingEntries(target)
        check(missing.isEmpty()) {
            "The archive fetched for $image lacks ${missing.size} entries its manifest names, so K3s cannot import it: $missing"
        }
    }

    private fun registryDigest(image: String): String {
        val reference = ImageReference.parse(image)
        val http = FailoverHttpClient(false, false) {}
        return try {
            val registry =
                RegistryClient
                    .factory(EventHandlers.NONE, reference.registry, reference.repository, http)
                    .newRegistryClient()
            registry.doPullBearerAuth()
            registry
                .checkManifest(reference.qualifier)
                .map { it.digest.toString() }
                .orElseThrow { IllegalStateException("The registry has no manifest for $image") }
        } finally {
            http.shutDown()
        }
    }

    private fun fetch(
        image: String,
        target: Path,
    ) {
        Jib
            .from(RegistryImage.named(image))
            .setPlatforms(setOf(Platform(architecture, LINUX)))
            .containerize(
                Containerizer
                    .to(TarImage.at(target).named(image))
                    .setBaseImageLayersCache(layerCache.resolve(BASE_LAYERS))
                    .setApplicationLayersCache(layerCache.resolve(APPLICATION_LAYERS)),
            )
    }

    private companion object {
        const val LINUX = "linux"
        const val BASE_LAYERS = "base"
        const val APPLICATION_LAYERS = "application"

        /** The image platform architecture of the Docker host the K3s containers run on. */
        fun dockerHostArchitecture(): String =
            when (val architecture: String? = DockerClientFactory.instance().info.architecture) {
                "aarch64", "arm64" -> "arm64"
                "x86_64", "amd64" -> "amd64"
                else -> error("No Linux image platform matches the Docker host's architecture $architecture")
            }
    }
}
