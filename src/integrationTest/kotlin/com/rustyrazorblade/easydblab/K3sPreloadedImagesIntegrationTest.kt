package com.rustyrazorblade.easydblab

import io.fabric8.kubernetes.api.model.PodBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.TimeUnit

/**
 * Checks that K3s imports the archives [K3sPreloadedImages] seeds it with, on the [SharedK3s]
 * cluster, which is seeded with the pause image, busybox and the documents web server.
 *
 * K3s logs an archive it cannot import and starts anyway, after which a pod pulls its image from
 * a registry, so a started cluster alone proves nothing. The test reads K3s's own report of each
 * import, and runs the web server image (whose registry index carries attestation manifests) with
 * `imagePullPolicy: Never`, which only starts from an image already in containerd.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class K3sPreloadedImagesIntegrationTest {
    companion object {
        private const val NAMESPACE = "preloaded-images"
        private const val PRELOADED_ARCHIVES = 3
        private const val READY_SECONDS = 120L
    }

    @BeforeAll
    fun createNamespace() {
        SharedK3s.createNamespace(NAMESPACE)
    }

    @Test
    fun `K3s imports every preloaded archive`() {
        val logs = SharedK3s.logs()

        (0 until PRELOADED_ARCHIVES).forEach { index ->
            assertThat(logs).contains("Imported images from ${K3sPreloadedImages.AIRGAP_DIR}/preload-$index.tar")
        }
    }

    @Test
    fun `a pod that may not pull its image runs the preloaded web server`() {
        SharedK3s.client().use { client ->
            client
                .resource(
                    PodBuilder()
                        .withNewMetadata()
                        .withName("web-server")
                        .withNamespace(NAMESPACE)
                        .endMetadata()
                        .withNewSpec()
                        .addNewContainer()
                        .withName("web")
                        .withImage(SharedK3s.WEB_SERVER_IMAGE)
                        .withImagePullPolicy("Never")
                        .endContainer()
                        .endSpec()
                        .build(),
                ).create()

            val pod =
                client
                    .pods()
                    .inNamespace(NAMESPACE)
                    .withName("web-server")
                    .waitUntilCondition(
                        { pod ->
                            pod
                                ?.status
                                ?.containerStatuses
                                .orEmpty()
                                .any { it.ready == true }
                        },
                        READY_SECONDS,
                        TimeUnit.SECONDS,
                    )

            assertThat(pod.status.phase).isEqualTo("Running")
        }
    }
}
