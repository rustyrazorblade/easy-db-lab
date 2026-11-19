package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.WorkspaceK8s
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kubernetes.KubeApi
import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import io.fabric8.kubernetes.api.model.ContainerStateWaitingBuilder
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A pod stuck in `ImagePullBackOff` must reach the caller as an [com.rustyrazorblade.easydblab.kubernetes.ImagePullFailure]
 * on the [com.rustyrazorblade.easydblab.kubernetes.KubernetesPod] that the pod listings return, so
 * `stress start` can fail at once and name the image. The listings run against a fabric8 mock API
 * server holding a real Pod with a waiting container state.
 */
@EnableKubernetesMockClient(crud = true)
class K8sImagePullFailureMappingTest {
    lateinit var server: KubernetesMockServer

    private lateinit var clientProvider: K8sClientProvider

    @BeforeEach
    fun setup() {
        val factory =
            object : KubernetesClientFactory {
                override fun createClient(api: KubeApi): KubernetesClient = server.createClient()
            }
        clientProvider = WorkspaceK8s.provider("placeholder", factory)
    }

    private fun applyBackOffPod() {
        val pod =
            PodBuilder()
                .withNewMetadata()
                .withName("stress-abc")
                .withNamespace("default")
                .addToLabels("job-name", "stress-job")
                .endMetadata()
                .withNewStatus()
                .withPhase("Pending")
                .withContainerStatuses(
                    ContainerStatusBuilder()
                        .withName("stress")
                        .withImage("registry.example/stress:missing")
                        .withNewState()
                        .withWaiting(
                            ContainerStateWaitingBuilder()
                                .withReason("ImagePullBackOff")
                                .withMessage("Back-off pulling image")
                                .build(),
                        ).endState()
                        .build(),
                ).endStatus()
                .build()
        server.createClient().use {
            it
                .pods()
                .inNamespace("default")
                .resource(pod)
                .create()
        }
    }

    @Test
    fun `getPodsForJob reports the image pull failure of a pod`() {
        applyBackOffPod()

        val pods = DefaultK8sJobOperations(clientProvider, EventBus()).getPodsForJob("default", "stress-job").getOrThrow()

        assertThat(pods.single().imagePullFailure)
            .extracting({ it?.container }, { it?.image }, { it?.reason })
            .containsExactly("stress", "registry.example/stress:missing", "ImagePullBackOff")
    }

    @Test
    fun `listPodsByLabel reports the image pull failure of a pod`() {
        applyBackOffPod()

        val pods = DefaultK8sReadOperations(clientProvider).listPodsByLabel("job-name=stress-job", "default").getOrThrow()

        assertThat(pods.single().imagePullFailure)
            .extracting({ it?.container }, { it?.image }, { it?.reason })
            .containsExactly("stress", "registry.example/stress:missing", "ImagePullBackOff")
    }
}
