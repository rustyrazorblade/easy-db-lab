package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.kubernetes.KubernetesService
import com.rustyrazorblade.easydblab.providers.aws.pollUntil
import java.time.Duration

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
 * is already running, or still terminating, fails instead of re-applying over the live objects.
 * `stop` asks it, for every kit that declares a runtime, to wait for that workload's pods to leave:
 * deleting a StatefulSet, Deployment or operator resource, or scaling a Deployment to zero, returns
 * before its pods have terminated, and a `start` right after would be refused.
 */
class KitWorkloadProbe(
    private val kubeService: KubernetesService,
    private val helmService: HelmService,
    private val pollInterval: Duration = Constants.Kit.STOP_WAIT_POLL_INTERVAL,
    private val maxPolls: Int = Constants.Kit.STOP_WAIT_MAX_POLLS,
) {
    /**
     * Finds [kitName]'s workload as its [runtime] declares it: the pods it selects, terminating
     * ones included — a pod still cleaning up after a manual `kubectl delete` would otherwise have
     * manifests reapplied over it — or, for a helm runtime, its release and then that release's
     * pods. A kit whose own `stop` waited with [awaitGone] leaves nothing behind to find. A failed
     * cluster query fails the result.
     */
    fun find(
        kitName: String,
        runtime: KitRuntime?,
        controlHost: ClusterHost,
    ): Result<WorkloadPresence> = runCatching { lookUp(kitName, runtime, controlHost) }

    /**
     * Waits for [kitName]'s workload to leave the cluster: every pod its [runtime] selects,
     * terminating ones included; for a helm runtime, every pod labelled
     * `app.kubernetes.io/instance=<release>` in the runtime's namespace, terminating ones included.
     * The release itself does not count: a kit may stop by scaling its release to zero (Presto,
     * Trino) and keep it, and `helm uninstall` has removed the release record by the time it
     * returns.
     * Looks up to `maxPolls` times, `pollInterval` apart, and returns [WorkloadPresence.Absent] once
     * nothing is left, or what was still there at the last look. A failed cluster query — a dropped
     * API call or SOCKS hiccup during a wait that lasts minutes — is retried like a present
     * workload; it fails the result only on the last look.
     */
    fun awaitGone(
        kitName: String,
        runtime: KitRuntime?,
        controlHost: ClusterHost,
    ): Result<WorkloadPresence> =
        runCatching {
            pollUntil(
                operationName = "kit-stop-$kitName",
                maxAttempts = maxPolls,
                interval = pollInterval,
                done = { it == WorkloadPresence.Absent },
            ) { lookUpPods(kitName, runtime) }
        }

    private fun lookUp(
        kitName: String,
        runtime: KitRuntime?,
        controlHost: ClusterHost,
    ): WorkloadPresence =
        when (runtime?.type) {
            KitRuntime.RuntimeType.HELM -> findHelmRelease(kitName, runtime, controlHost)
            else -> findPods(podSelector(kitName, runtime), runtime?.namespace ?: DEFAULT_NAMESPACE)
        }

    /** The pods that show [kitName]'s workload is running, whatever keeps them there. */
    private fun lookUpPods(
        kitName: String,
        runtime: KitRuntime?,
    ): WorkloadPresence =
        when (runtime?.type) {
            KitRuntime.RuntimeType.HELM -> findPods(helmReleasePods(kitName, runtime), runtime.namespace)
            else -> findPods(podSelector(kitName, runtime), runtime?.namespace ?: DEFAULT_NAMESPACE)
        }

    /**
     * A helm runtime is present while its release exists in the runtime's namespace, and, once the
     * release is gone, while any pod labelled `app.kubernetes.io/instance=<release>` (the label helm
     * charts put on a release's pods) is left there. `helm uninstall` removes the release record in
     * seconds while those pods run out their termination grace period.
     */
    private fun findHelmRelease(
        kitName: String,
        runtime: KitRuntime,
        controlHost: ClusterHost,
    ): WorkloadPresence {
        val release = runtime.release.ifBlank { kitName }
        val exists = helmService.releaseExists(host = controlHost.toHost(), release = release, namespace = runtime.namespace)
        return if (exists) {
            WorkloadPresence.Present(runtime.namespace, listOf("helm-release/$release"))
        } else {
            findPods(helmReleasePods(kitName, runtime), runtime.namespace)
        }
    }

    /** The selector for a helm release's pods: the label helm charts put on them, valued with the release. */
    private fun helmReleasePods(
        kitName: String,
        runtime: KitRuntime,
    ): String = "$HELM_INSTANCE_LABEL=${runtime.release.ifBlank { kitName }}"

    /**
     * The pods [selector] matches in [namespace], terminating ones included. A pod that has
     * finished (Succeeded or Failed, as a completed sysbench run leaves behind) is not running and
     * never counts.
     */
    private fun findPods(
        selector: String,
        namespace: String,
    ): WorkloadPresence {
        val pods =
            kubeService
                .listPodsByLabel(selector, namespace)
                .getOrThrow()
                .filter { it.status !in FINISHED_POD_PHASES }
        return if (pods.isEmpty()) {
            WorkloadPresence.Absent
        } else {
            WorkloadPresence.Present(namespace, pods.map { "pod/${it.name}" })
        }
    }

    private companion object {
        const val DEFAULT_NAMESPACE = "default"

        /** The standard label helm charts put on every object of a release, valued with the release name. */
        const val HELM_INSTANCE_LABEL = "app.kubernetes.io/instance"

        /** Pod phases a pod never leaves: its containers have all exited for good. */
        val FINISHED_POD_PHASES = setOf("Succeeded", "Failed")
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
