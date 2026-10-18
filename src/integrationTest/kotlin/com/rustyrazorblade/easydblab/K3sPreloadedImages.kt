package com.rustyrazorblade.easydblab

import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.MountableFile
import java.nio.file.Path

/**
 * Seeds a K3s TestContainer with container images fetched on the host, so the pods a test starts
 * never pull from a registry.
 *
 * Every K3s container starts with an empty containerd store, so without this each test run pulls
 * its pod images (and the pause image every pod sandbox needs) from Docker Hub from inside the
 * container. That pull is outside the test's control: a stalled or rate-limited pull left a
 * rollout-wait test's pod unavailable for its whole three-minute budget. Each image is fetched
 * from its registry by [RegistryImageSource] into a docker-load archive, which is copied into
 * K3s's airgap directory ([AIRGAP_DIR]). K3s imports it before its kubelet starts, so the image is
 * present before the first pod is scheduled. The host's Docker daemon is not involved: its
 * `docker save` exports some images without their layers.
 *
 * The archives come from one [ImageArchiveCache] for the whole run, in the directory the Gradle task
 * names in [ARCHIVE_DIR_PROPERTY], so the parallel test forks share one fetched copy of each image
 * instead of each fetching it for every K3s container.
 */
object K3sPreloadedImages {
    /** The sandbox image K3s starts every pod with ([SharedK3s.K3S_IMAGE] configures containerd with it). */
    const val PAUSE_IMAGE = "rancher/mirrored-pause:3.6"

    /**
     * The system property naming the directory the archives are shared in. The `integrationTest`
     * task sets it to a directory under `build/`; a run without it uses [DEFAULT_ARCHIVE_DIR].
     */
    const val ARCHIVE_DIR_PROPERTY = "easydblab.test.imageArchiveDir"

    /** The directory in the K3s container whose archives K3s imports into containerd at startup. */
    const val AIRGAP_DIR = "/var/lib/rancher/k3s/agent/images"

    /** The archive directory, relative to the working directory, when [ARCHIVE_DIR_PROPERTY] is not set. */
    private const val DEFAULT_ARCHIVE_DIR = "build/k3s-image-archives"

    /** The subdirectory of the archive directory where Jib keeps the layers it downloads. */
    private const val LAYER_CACHE_DIR = "layers"

    private val archives by lazy {
        val directory = Path.of(System.getProperty(ARCHIVE_DIR_PROPERTY) ?: DEFAULT_ARCHIVE_DIR)
        ImageArchiveCache(directory, RegistryImageSource(directory.resolve(LAYER_CACHE_DIR)))
    }

    /** Arranges for [images], plus [PAUSE_IMAGE], to be in this container's containerd at startup. */
    fun K3sContainer.withPreloadedImages(vararg images: String): K3sContainer {
        (listOf(PAUSE_IMAGE) + images).forEachIndexed { index, image ->
            withCopyToContainer(MountableFile.forHostPath(archives.archive(image)), "$AIRGAP_DIR/preload-$index.tar")
        }
        return this
    }
}
