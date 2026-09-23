package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.kubernetes.KubernetesService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
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
 * is already running fails instead of re-applying over the live objects. `stop` asks it to wait for
 * that workload to leave: deleting a StatefulSet, Deployment or operator resource returns before
 * its pods are even marked for deletion, and a `start` right after would be refused.
 */
class KitWorkloadProbe(
    private val kubeService: KubernetesService,
    private val helmService: HelmService,
    private val pollInterval: Duration = Constants.Kit.STOP_WAIT_POLL_INTERVAL,
    private val maxPolls: Int = Constants.Kit.STOP_WAIT_MAX_POLLS,
) {
    /**
     * Retries while the workload is still there, `pollInterval` apart, up to `maxPolls` looks,
     * then returns the last result rather than throwing. A failed look — a dropped API call or
     * SOCKS hiccup during a wait that lasts minutes — is retried within the same budget; the wait
     * fails only when the last look still throws.
     */
    private val untilGoneRetryConfig: RetryConfig =
        RetryConfig
            .custom<Boolean>()
            .maxAttempts(maxPolls)
            .intervalFunction { _ -> pollInterval.toMillis() }
            .retryOnResult { gone -> !gone }
            .retryOnException { true }
            .build()

    /** Finds [kitName]'s workload as its [runtime] declares it; a failed cluster query fails the result. */
    fun find(
        kitName: String,
        runtime: KitRuntime?,
        controlHost: ClusterHost,
    ): Result<WorkloadPresence> = runCatching { lookUp(kitName, runtime, controlHost, countTerminating = false) }

    /**
     * Waits for [kitName]'s workload to leave the cluster: every pod its [runtime] selects,
     * terminating ones included, or its helm release. Looks up to `maxPolls` times, `pollInterval`
     * apart, and returns [WorkloadPresence.Absent] once nothing is left, or what was still there at
     * the last look. A failed cluster query is retried like a present workload; it fails the result
     * only on the last look.
     */
    fun awaitGone(
        kitName: String,
        runtime: KitRuntime?,
        controlHost: ClusterHost,
    ): Result<WorkloadPresence> =
        runCatching {
            var remaining: WorkloadPresence = WorkloadPresence.Absent
            val retry = Retry.of("kit-stop-$kitName", untilGoneRetryConfig)
            retry.eventPublisher.onRetry { event ->
                event.lastThrowable?.let { e -> log.warn(e) { "Looking for $kitName's workload failed; retrying" } }
            }
            Retry
                .decorateSupplier(retry) {
                    remaining = lookUp(kitName, runtime, controlHost, countTerminating = true)
                    remaining == WorkloadPresence.Absent
                }.get()
            remaining
        }

    private fun lookUp(
        kitName: String,
        runtime: KitRuntime?,
        controlHost: ClusterHost,
        countTerminating: Boolean,
    ): WorkloadPresence =
        when (runtime?.type) {
            KitRuntime.RuntimeType.HELM -> findHelmRelease(kitName, runtime, controlHost)
            else -> findPods(podSelector(kitName, runtime), runtime?.namespace ?: DEFAULT_NAMESPACE, countTerminating)
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

    /**
     * The pods [selector] matches in [namespace]. A pod already being deleted is on its way out, so
     * it counts only when [countTerminating] is set — when waiting for the workload to be gone. A
     * pod that has finished (Succeeded or Failed, as a completed sysbench run leaves behind) is not
     * running and never counts.
     */
    private fun findPods(
        selector: String,
        namespace: String,
        countTerminating: Boolean,
    ): WorkloadPresence {
        val pods =
            kubeService
                .listPodsByLabel(selector, namespace)
                .getOrThrow()
                .filter { it.status !in FINISHED_POD_PHASES }
                .filter { countTerminating || !it.terminating }
        return if (pods.isEmpty()) {
            WorkloadPresence.Absent
        } else {
            WorkloadPresence.Present(namespace, pods.map { "pod/${it.name}" })
        }
    }

    private companion object {
        val log = KotlinLogging.logger {}

        const val DEFAULT_NAMESPACE = "default"

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
