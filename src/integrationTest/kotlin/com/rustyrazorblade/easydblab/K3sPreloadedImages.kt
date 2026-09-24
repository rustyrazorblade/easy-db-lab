package com.rustyrazorblade.easydblab

import org.testcontainers.DockerClientFactory
import org.testcontainers.images.RemoteDockerImage
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

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
 */
object K3sPreloadedImages {
    /** The sandbox image K3s v1.30 starts every pod with. */
    const val PAUSE_IMAGE = "rancher/mirrored-pause:3.6"

    private const val AIRGAP_DIR = "/var/lib/rancher/k3s/agent/images"

    /** Arranges for [images], plus [PAUSE_IMAGE], to be in this container's containerd at startup. */
    fun K3sContainer.withPreloadedImages(vararg images: String): K3sContainer {
        (listOf(PAUSE_IMAGE) + images).forEachIndexed { index, image ->
            withCopyToContainer(Transferable.of(saveFromHost(image)), "$AIRGAP_DIR/preload-$index.tar")
        }
        return this
    }

    /** The image archive for [image], pulling it into the host's cache first if it is not there. */
    private fun saveFromHost(image: String): ByteArray {
        RemoteDockerImage(DockerImageName.parse(image)).get()
        return DockerClientFactory
            .instance()
            .client()
            .saveImageCmd(image)
            .exec()
            .use { it.readAllBytes() }
    }
}
