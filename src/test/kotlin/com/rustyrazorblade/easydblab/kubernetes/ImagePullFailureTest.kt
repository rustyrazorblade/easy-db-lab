package com.rustyrazorblade.easydblab.kubernetes

import io.fabric8.kubernetes.api.model.ContainerStatus
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Checks which container states [imagePullFailure] reports as an image that cannot be pulled. */
class ImagePullFailureTest {
    private fun waiting(
        name: String,
        reason: String,
        message: String? = null,
    ): ContainerStatus =
        ContainerStatusBuilder()
            .withName(name)
            .withImage("$name-image:1")
            .withNewState()
            .withNewWaiting()
            .withReason(reason)
            .withMessage(message)
            .endWaiting()
            .endState()
            .build()

    private fun pod(
        init: List<ContainerStatus> = emptyList(),
        containers: List<ContainerStatus> = emptyList(),
    ): Pod =
        PodBuilder()
            .withNewMetadata()
            .withName("p")
            .endMetadata()
            .withNewStatus()
            .withInitContainerStatuses(init)
            .withContainerStatuses(containers)
            .endStatus()
            .build()

    @Test
    fun `each pull failure reason names the container, its image and the kubelet message`() {
        for (reason in listOf("ErrImagePull", "ImagePullBackOff", "InvalidImageName")) {
            assertThat(pod(containers = listOf(waiting("stress", reason, "manifest unknown"))).imagePullFailure())
                .describedAs(reason)
                .isEqualTo(ImagePullFailure(container = "stress", image = "stress-image:1", reason = reason, message = "manifest unknown"))
        }
    }

    @Test
    fun `an init container that cannot pull is reported`() {
        val failure = pod(init = listOf(waiting("init", "ImagePullBackOff"))).imagePullFailure()

        assertThat(failure).isEqualTo(ImagePullFailure(container = "init", image = "init-image:1", reason = "ImagePullBackOff", message = ""))
    }

    @Test
    fun `other waiting reasons and a pod with no status are not pull failures`() {
        assertThat(pod(containers = listOf(waiting("stress", "ContainerCreating"), waiting("s2", "CrashLoopBackOff"))).imagePullFailure())
            .isNull()
        assertThat(PodBuilder().build().imagePullFailure()).isNull()
    }
}
