package com.rustyrazorblade.easydblab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads a docker-load archive to tell whether it holds the image it describes.
 *
 * An archive can carry its manifest and config but not the layers the manifest names: with the
 * containerd image store, `docker save` exports images with attestation manifests that way, and
 * reports no error. K3s cannot import such an archive and starts without the image, so a pod
 * would pull it from a registry after all, which is what seeding exists to prevent.
 */
object DockerArchive {
    /** The Docker-compatible manifest every docker-load archive carries at its root. */
    const val MANIFEST = "manifest.json"

    private val json = Json { ignoreUnknownKeys = true }

    /** One image of [MANIFEST]: the archive paths of its layers. */
    @Serializable
    private data class ManifestEntry(
        @SerialName("Layers") val layers: List<String> = emptyList(),
    )

    /**
     * The entries [archive] lacks: every layer its manifest names that it does not hold, or
     * [MANIFEST] itself when it has none. Empty for a complete archive.
     */
    fun missingEntries(archive: Path): List<String> {
        val names = mutableSetOf<String>()
        var manifest: String? = null
        TarArchiveInputStream(Files.newInputStream(archive)).use { tar ->
            generateSequence { tar.nextEntry }.forEach { entry ->
                names += entry.name
                if (entry.name == MANIFEST) manifest = tar.readAllBytes().decodeToString()
            }
        }
        return manifest
            ?.let { text -> json.decodeFromString<List<ManifestEntry>>(text).flatMap { it.layers }.filterNot { it in names } }
            ?: listOf(MANIFEST)
    }
}
