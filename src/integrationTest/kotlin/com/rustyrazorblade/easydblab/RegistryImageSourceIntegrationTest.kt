package com.rustyrazorblade.easydblab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Checks that [RegistryImageSource] fetches an image from its registry into a complete
 * docker-load archive, including an image whose registry index carries attestation manifests:
 * that is the kind of image the host's `docker save` exported without its layers.
 */
class RegistryImageSourceIntegrationTest {
    private fun repoTags(archive: Path): List<String> =
        TarArchiveInputStream(Files.newInputStream(archive)).use { tar ->
            generateSequence { tar.nextEntry }
                .first { it.name == DockerArchive.MANIFEST }
                .let { Json.parseToJsonElement(tar.readAllBytes().decodeToString()) }
                .jsonArray
                .flatMap { image ->
                    image.jsonObject
                        .getValue("RepoTags")
                        .jsonArray
                        .map { it.jsonPrimitive.content }
                }
        }

    @Test
    fun `an image with attestation manifests is fetched into an archive holding every layer, tagged with its reference`(
        @TempDir dir: Path,
    ) {
        val source = RegistryImageSource(dir.resolve("layers"))
        val target = dir.resolve("web.tar")

        source.save(SharedK3s.WEB_SERVER_IMAGE, target)

        assertThat(DockerArchive.missingEntries(target)).isEmpty()
        assertThat(repoTags(target)).containsExactly(SharedK3s.WEB_SERVER_IMAGE)
    }

    @Test
    fun `the content ID is the registry digest of the reference, the same on every lookup`(
        @TempDir dir: Path,
    ) {
        val source = RegistryImageSource(dir.resolve("layers"))

        val first = source.contentId(K3sPreloadedImages.PAUSE_IMAGE)

        assertThat(first).matches("sha256:[0-9a-f]{64}")
        assertThat(source.contentId(K3sPreloadedImages.PAUSE_IMAGE)).isEqualTo(first)
        assertThat(source.contentId(SharedK3s.BUSYBOX_IMAGE)).isNotEqualTo(first)
    }
}
