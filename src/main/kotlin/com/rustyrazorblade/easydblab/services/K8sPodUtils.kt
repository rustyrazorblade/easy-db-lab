package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.ContainerStatus
import io.fabric8.kubernetes.api.model.Pod

/**
 * Utility functions for inspecting Kubernetes pod status.
 */
object K8sPodUtils {
    /**
     * Container waiting reasons that indicate a terminal failure.
     * When any container enters one of these states, the pod will not recover
     * without intervention, so we fail fast instead of waiting for timeout.
     */
    val TERMINAL_FAILURE_REASONS =
        setOf(
            "CrashLoopBackOff",
            "Error",
            "ImagePullBackOff",
            "ErrImagePull",
        )

    /**
     * Checks if a pod has any containers in a terminal failure state.
     * Throws IllegalStateException if a failure is detected, causing
     * waitUntilCondition to exit immediately.
     */
    fun checkForPodFailure(pod: Pod?) {
        val podName = pod?.metadata?.name ?: "unknown"
        checkContainerStatuses(pod?.status?.containerStatuses, podName, "container")
        checkContainerStatuses(pod?.status?.initContainerStatuses, podName, "init container")
    }

    private fun checkContainerStatuses(
        statuses: List<ContainerStatus>?,
        podName: String,
        containerType: String,
    ) {
        statuses?.forEach { containerStatus ->
            val waitingReason = containerStatus.state?.waiting?.reason ?: return@forEach
            if (waitingReason in TERMINAL_FAILURE_REASONS) {
                val containerName = containerStatus.name ?: "unknown"
                val waitingMessage = containerStatus.state?.waiting?.message
                val suffix = waitingMessage?.let { ": $it" } ?: ""
                error("Pod $podName $containerType $containerName is in $waitingReason state$suffix")
            }
        }
    }
}
