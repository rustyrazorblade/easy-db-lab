package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.ContainerStateBuilder
import io.fabric8.kubernetes.api.model.ContainerStateWaitingBuilder
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodStatusBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [K8sPodUtils.failureOf] names a pod's terminal failure (CrashLoopBackOff, Error,
 * ImagePullBackOff, ErrImagePull), so a readiness wait can stop at once instead of timing out.
 */
class K8sPodFailureOfTest {
    @Test
    fun `failureOf names each terminal failure reason`() {
        for (reason in K8sPodUtils.TERMINAL_FAILURE_REASONS) {
            val pod = buildPodWithWaitingContainer("test-pod", "main", reason)

            assertThat(K8sPodUtils.failureOf(pod))
                .contains("test-pod")
                .contains(reason)
        }
    }

    @Test
    fun `failureOf is null for a Running pod`() {
        val pod =
            PodBuilder()
                .withMetadata(ObjectMetaBuilder().withName("healthy-pod").build())
                .withStatus(
                    PodStatusBuilder()
                        .withContainerStatuses(
                            ContainerStatusBuilder()
                                .withName("main")
                                .withReady(true)
                                .withNewState()
                                .withNewRunning()
                                .endRunning()
                                .endState()
                                .build(),
                        ).build(),
                ).build()

        assertThat(K8sPodUtils.failureOf(pod)).isNull()
    }

    @Test
    fun `failureOf is null for no pod`() {
        assertThat(K8sPodUtils.failureOf(null)).isNull()
    }

    @Test
    fun `failureOf is null for ContainerCreating`() {
        val pod = buildPodWithWaitingContainer("creating-pod", "main", "ContainerCreating")

        assertThat(K8sPodUtils.failureOf(pod)).isNull()
    }

    @Test
    fun `failureOf names a failed init container`() {
        val pod =
            PodBuilder()
                .withMetadata(ObjectMetaBuilder().withName("init-fail-pod").build())
                .withStatus(
                    PodStatusBuilder()
                        .withInitContainerStatuses(
                            ContainerStatusBuilder()
                                .withName("init-container")
                                .withState(
                                    ContainerStateBuilder()
                                        .withWaiting(
                                            ContainerStateWaitingBuilder()
                                                .withReason("CrashLoopBackOff")
                                                .withMessage("back-off 40s restarting failed container")
                                                .build(),
                                        ).build(),
                                ).build(),
                        ).build(),
                ).build()

        assertThat(K8sPodUtils.failureOf(pod))
            .contains("init-fail-pod")
            .contains("init container")
            .contains("CrashLoopBackOff")
    }

    @Test
    fun `failureOf includes the waiting message when present`() {
        val pod =
            buildPodWithWaitingContainer(
                "crash-pod",
                "main",
                "CrashLoopBackOff",
                "back-off 5m0s restarting failed container",
            )

        assertThat(K8sPodUtils.failureOf(pod))
            .contains("back-off 5m0s restarting failed container")
    }

    @Test
    fun `TERMINAL_FAILURE_REASONS should contain all expected reasons`() {
        assertThat(K8sPodUtils.TERMINAL_FAILURE_REASONS)
            .containsExactlyInAnyOrder(
                "CrashLoopBackOff",
                "Error",
                "ImagePullBackOff",
                "ErrImagePull",
            )
    }

    private fun buildPodWithWaitingContainer(
        podName: String,
        containerName: String,
        reason: String,
        message: String? = null,
    ) = PodBuilder()
        .withMetadata(ObjectMetaBuilder().withName(podName).build())
        .withStatus(
            PodStatusBuilder()
                .withContainerStatuses(
                    ContainerStatusBuilder()
                        .withName(containerName)
                        .withState(
                            ContainerStateBuilder()
                                .withWaiting(
                                    ContainerStateWaitingBuilder()
                                        .withReason(reason)
                                        .apply { if (message != null) withMessage(message) }
                                        .build(),
                                ).build(),
                        ).build(),
                ).build(),
        ).build()
}
