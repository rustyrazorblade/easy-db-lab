package com.rustyrazorblade.easydblab

import org.testcontainers.DockerClientFactory
import org.testcontainers.images.RemoteDockerImage
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Seeds a K3s TestContainer with container images from the host's Docker image cache, so the pods a
 * test starts never pull from a registry.
 *
 * Every K3s container starts with an empty containerd store, so without this each test run pulls
 * its pod images (and the pause image every pod sandbox needs) from Docker Hub from inside the
 * container. That pull is outside the test's control: a stalled or rate-limited pull left a
 * rollout-wait test's pod unavailable for its whole three-minute budget. Images saved from the host
 * are copied into K3s's airgap directory, which K3s imports before its kubelet starts, so they are
 * present before the first pod is scheduled. The host pulls each image at most once and caches it.
 *
 * Archives are streamed to temp files and copied into the container from disk, never held as a
 * byte array: a database image saves to well over 100 MB, and `readAllBytes` plus the array that
 * stays referenced for the container's life exhausted the integration test worker's heap.
 */
object K3sPreloadedImages {
    /** The sandbox image K3s starts every pod with ([SharedK3s.K3S_IMAGE] configures containerd with it). */
    const val PAUSE_IMAGE = "rancher/mirrored-pause:3.6"

    /** One archive file per image for the life of the JVM, so the classes of a fork save each image once. */
    private val archives = ConcurrentHashMap<String, Path>()

    private const val AIRGAP_DIR = "/var/lib/rancher/k3s/agent/images"

    /** Arranges for [images], plus [PAUSE_IMAGE], to be in this container's containerd at startup. */
    fun K3sContainer.withPreloadedImages(vararg images: String): K3sContainer {
        (listOf(PAUSE_IMAGE) + images).forEachIndexed { index, image ->
            withCopyFileToContainer(
                MountableFile.forHostPath(archives.computeIfAbsent(image, ::saveFromHost)),
                "$AIRGAP_DIR/preload-$index.tar",
            )
        }
        return this
    }

    /** A temp file holding the archive for [image], pulling it into the host's cache first if it is not there. */
    private fun saveFromHost(image: String): Path {
        RemoteDockerImage(DockerImageName.parse(image)).get()
        val archive = Files.createTempFile("k3s-preload-", ".tar")
        archive.toFile().deleteOnExit()
        DockerClientFactory
            .instance()
            .client()
            .saveImageCmd(image)
            .exec()
            .use { Files.copy(it, archive, StandardCopyOption.REPLACE_EXISTING) }
        return archive
    }
}
