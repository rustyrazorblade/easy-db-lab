package com.rustyrazorblade.easydblab

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Checks [DockerArchive.missingEntries], which catches a docker-load archive that lacks a manifest
 * or names layers it does not hold. K3s cannot import such an archive.
 */
class DockerArchiveTest {
    companion object {
        private const val LAYER_A = "blobs/sha256/aaaa"
        private const val LAYER_B = "blobs/sha256/bbbb"
    }

    private fun archive(
        dir: Path,
        layersInManifest: List<String>,
        entries: List<String>,
    ): Path {
        val path = dir.resolve("image.tar")
        val manifest =
            """[{"Config":"blobs/sha256/cccc","RepoTags":["example/image:1.0"],"Layers":[${layersInManifest.joinToString {
                "\"$it\""
            }}]}]"""
        TarArchiveOutputStream(Files.newOutputStream(path)).use { tar ->
            (entries.map { it to "layer" } + ("manifest.json" to manifest)).forEach { (name, content) ->
                val bytes = content.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return path
    }

    @Test
    fun `an archive holding every layer its manifest names is complete`(
        @TempDir dir: Path,
    ) {
        val complete = archive(dir, listOf(LAYER_A, LAYER_B), listOf(LAYER_A, LAYER_B))

        assertThat(DockerArchive.missingEntries(complete)).isEmpty()
    }

    @Test
    fun `an archive without a layer its manifest names reports that layer`(
        @TempDir dir: Path,
    ) {
        val partial = archive(dir, listOf(LAYER_A, LAYER_B), listOf(LAYER_A))

        assertThat(DockerArchive.missingEntries(partial)).containsExactly(LAYER_B)
    }

    @Test
    fun `an archive with no manifest is reported as missing it`(
        @TempDir dir: Path,
    ) {
        val path = dir.resolve("empty.tar")
        TarArchiveOutputStream(Files.newOutputStream(path)).use { it.finish() }

        assertThat(DockerArchive.missingEntries(path)).containsExactly(DockerArchive.MANIFEST)
    }
}
