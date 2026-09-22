package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.kubernetes.KubernetesService

/** Whether a kit's workload is already in the cluster, as [KitWorkloadProbe] found it. */
sealed interface WorkloadPresence {
    /** Nothing the kit's runtime declaration selects exists. */
    data object Absent : WorkloadPresence

    /** The objects that show the kit is already running, as `kind/name`, in [namespace]. */
    data class Present(
        val namespace: String,
        val resources: List<String>,
    ) : WorkloadPresence
}

/**
 * Looks in the cluster for a kit's running workload.
 *
 * `start` asks it before running a collision-checked kit's start phase, so that starting a kit that
 * is already running fails instead of re-applying over the live objects.
 */
class KitWorkloadProbe(
    private val kubeService: KubernetesService,
    private val helmService: HelmService,
) {
    /** Finds [kitName]'s workload as its [runtime] declares it; a failed cluster query fails the result. */
    fun find(
        kitName: String,
        runtime: KitRuntime?,
        controlHost: ClusterHost,
    ): Result<WorkloadPresence> =
        runCatching {
            when (runtime?.type) {
                KitRuntime.RuntimeType.HELM -> findHelmRelease(kitName, runtime, controlHost)
                else -> findPods(podSelector(kitName, runtime), runtime?.namespace ?: DEFAULT_NAMESPACE)
            }
        }

    private fun findHelmRelease(
        kitName: String,
        runtime: KitRuntime,
        controlHost: ClusterHost,
    ): WorkloadPresence {
        val release = runtime.release.ifBlank { kitName }
        val exists = helmService.releaseExists(host = controlHost.toHost(), release = release, namespace = runtime.namespace)
        return if (exists) WorkloadPresence.Present(runtime.namespace, listOf("helm-release/$release")) else WorkloadPresence.Absent
    }

    private fun findPods(
        selector: String,
        namespace: String,
    ): WorkloadPresence {
        // A pod already being deleted is on its way out, e.g. after a `stop` just before `start`.
        val pods = kubeService.listPodsByLabel(selector, namespace).getOrThrow().filterNot { it.terminating }
        return if (pods.isEmpty()) {
            WorkloadPresence.Absent
        } else {
            WorkloadPresence.Present(namespace, pods.map { "pod/${it.name}" })
        }
    }

    private companion object {
        const val DEFAULT_NAMESPACE = "default"
    }
}

/**
 * The label selector for a kit's pods: the [runtime]'s own selector with `${KIT_NAME}` filled in,
 * or `app.kubernetes.io/name=<kit>` when the kit declares none. `<kit> status` reads the same pods.
 */
fun podSelector(
    kitName: String,
    runtime: KitRuntime?,
): String =
    runtime
        ?.selector
        .orEmpty()
        .replace("\${KIT_NAME}", kitName)
        .ifBlank { "app.kubernetes.io/name=$kitName" }
