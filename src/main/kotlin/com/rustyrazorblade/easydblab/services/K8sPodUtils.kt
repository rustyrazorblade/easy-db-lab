package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
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
            Constants.K8s.CRASH_LOOP_BACK_OFF,
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
     * Describes the first container of [pod] in a terminal failure state, or null when none is.
     * It returns rather than throws, so a poll can end on it at once instead of treating it as a
     * failed look to retry.
     */
    fun failureOf(pod: Pod?): String? = failingContainer(pod)?.description

    /**
     * The first container of [pod] in a terminal failure state, among those [considered] accepts:
     * its containers first, then its init containers. Null when none is. A caller that needs more
     * than [failureOf]'s text (the container's restart count, say) reads it from [ContainerFailure.status].
     */
    fun failingContainer(
        pod: Pod?,
        considered: (ContainerStatus) -> Boolean = { true },
    ): ContainerFailure? {
        val podName = pod?.metadata?.name ?: "unknown"
        return containerFailure(pod?.status?.containerStatuses, podName, "container", considered)
            ?: containerFailure(pod?.status?.initContainerStatuses, podName, "init container", considered)
    }

    /**
     * [pod]'s state in one line for a wait's report: its phase, each init container's and container's
     * state (with restart counts), and why it cannot be scheduled, when it cannot.
     */
    fun describeState(pod: Pod): String {
        val initStatuses = pod.status?.initContainerStatuses.orEmpty()
        val containerStatuses = pod.status?.containerStatuses.orEmpty()
        val unschedulable =
            pod.status
                ?.conditions
                .orEmpty()
                .find { it.type == "PodScheduled" && it.status == "False" }
        return listOfNotNull(
            "phase=${pod.status?.phase ?: "Unknown"}",
            initStatuses.takeIf { it.isNotEmpty() }?.joinToString(", ", "init=[", "]") { "${it.name}=${initState(it)}" },
            containerStatuses.takeIf { it.isNotEmpty() }?.joinToString(", ", "containers=[", "]") {
                "${it.name}=${containerState(it)}(restarts=${it.restartCount ?: 0})"
            },
            unschedulable?.let { "unschedulable: ${it.message}" },
        ).joinToString(" ")
    }

    private fun initState(status: ContainerStatus): String {
        val terminated = status.state?.terminated
        return when {
            terminated != null -> if (terminated.exitCode == 0) "done" else "failed(exit=${terminated.exitCode})"
            else -> containerState(status)
        }
    }

    private fun containerState(status: ContainerStatus): String {
        val state = status.state
        return when {
            state?.running != null -> "running"
            state?.terminated != null -> "terminated(${state.terminated.reason})"
            state?.waiting != null -> "waiting(${state.waiting.reason ?: "unknown"})"
            else -> "unknown"
        }
    }

    private fun containerFailure(
        statuses: List<ContainerStatus>?,
        podName: String,
        containerType: String,
        considered: (ContainerStatus) -> Boolean,
    ): ContainerFailure? =
        statuses
            .orEmpty()
            .filter(considered)
            .firstNotNullOfOrNull { containerStatus ->
                val waitingReason = containerStatus.state?.waiting?.reason
                if (waitingReason in TERMINAL_FAILURE_REASONS) {
                    val containerName = containerStatus.name ?: "unknown"
                    val suffix =
                        containerStatus.state
                            ?.waiting
                            ?.message
                            ?.let { ": $it" } ?: ""
                    ContainerFailure(
                        status = containerStatus,
                        container = containerName,
                        containerType = containerType,
                        reason = waitingReason.orEmpty(),
                        description = "Pod $podName $containerType $containerName is in $waitingReason state$suffix",
                    )
                } else {
                    null
                }
            }
}

/**
 * A container of a pod in a terminal failure state: its [status], its name ([container]) and whether
 * it is a `container` or an `init container` ([containerType]), the waiting [reason] (one of
 * [K8sPodUtils.TERMINAL_FAILURE_REASONS]) and a [description] naming the pod, the container and why.
 */
data class ContainerFailure(
    val status: ContainerStatus,
    val container: String,
    val containerType: String,
    val reason: String,
    val description: String,
)
