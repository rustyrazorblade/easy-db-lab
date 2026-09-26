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

    /** The phase of a pod whose containers all exited successfully and will not restart. */
    const val SUCCEEDED_PHASE = "Succeeded"

    /** The phase of a pod whose containers all exited and at least one of them failed. */
    const val FAILED_PHASE = "Failed"

    private const val JOB_KIND = "Job"

    /**
     * Whether a readiness wait should hold out for [pod]. A pod that ran to completion (a Job's
     * pod, such as a kit's stress run) never becomes Ready again, so waiting on it can only time
     * out. The same holds for a Job's pod that failed: the Job replaces it or gives up, but that
     * pod stays terminal. Every other pod is awaited.
     */
    fun awaitsReadiness(pod: Pod): Boolean = !isTerminal(pod)

    /** Whether [pod] finished for good: it succeeded, or it is a Job's pod that failed. */
    private fun isTerminal(pod: Pod): Boolean =
        when (pod.status?.phase) {
            SUCCEEDED_PHASE -> true
            FAILED_PHASE ->
                pod.metadata
                    ?.ownerReferences
                    .orEmpty()
                    .any { it.kind == JOB_KIND }
            else -> false
        }

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
