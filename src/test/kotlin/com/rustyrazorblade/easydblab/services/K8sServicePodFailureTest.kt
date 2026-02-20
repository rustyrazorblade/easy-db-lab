package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.ContainerStateBuilder
import io.fabric8.kubernetes.api.model.ContainerStateWaitingBuilder
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodStatusBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Tests for DefaultK8sService.checkForPodFailure which detects terminal pod failures
 * like CrashLoopBackOff, Error, ImagePullBackOff, and ErrImagePull.
 */
class K8sServicePodFailureTest {
    @Test
    fun `checkForPodFailure should throw for each terminal failure reason`() {
        for (reason in DefaultK8sService.TERMINAL_FAILURE_REASONS) {
            val pod = buildPodWithWaitingContainer("test-pod", "main", reason)

            assertThatThrownBy { DefaultK8sService.checkForPodFailure(pod) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("test-pod")
                .hasMessageContaining(reason)
        }
    }

    @Test
    fun `checkForPodFailure should not throw for Running pod`() {
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

        assertThatCode { DefaultK8sService.checkForPodFailure(pod) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `checkForPodFailure should not throw for null pod`() {
        assertThatCode { DefaultK8sService.checkForPodFailure(null) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `checkForPodFailure should not throw for ContainerCreating`() {
        val pod = buildPodWithWaitingContainer("creating-pod", "main", "ContainerCreating")

        assertThatCode { DefaultK8sService.checkForPodFailure(pod) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `checkForPodFailure should detect failure in init containers`() {
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

        assertThatThrownBy { DefaultK8sService.checkForPodFailure(pod) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("init-fail-pod")
            .hasMessageContaining("init container")
            .hasMessageContaining("CrashLoopBackOff")
    }

    @Test
    fun `checkForPodFailure should include waiting message when present`() {
        val pod =
            buildPodWithWaitingContainer(
                "crash-pod",
                "main",
                "CrashLoopBackOff",
                "back-off 5m0s restarting failed container",
            )

        assertThatThrownBy { DefaultK8sService.checkForPodFailure(pod) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("back-off 5m0s restarting failed container")
    }

    @Test
    fun `TERMINAL_FAILURE_REASONS should contain all expected reasons`() {
        assertThat(DefaultK8sService.TERMINAL_FAILURE_REASONS)
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
