package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.resilience4j.retry.Retry
import java.time.Duration
import java.time.Instant

/**
 * How the pre-teardown flush stops the single-replica backends (Mimir, Loki). A backend is named by
 * its Deployment, which is also its pods' `app.kubernetes.io/name`.
 *
 * There is deliberately no way to scale a backend up or replace its pod: `down` never starts a
 * backend again (owner decision, 2026-09-26).
 */
interface BackendWorkloads {
    /** Scales [workload] to 0 and waits up to [timeout] until its pod is gone. */
    fun scaleDown(
        controlHost: ClusterHost,
        workload: String,
        timeout: Duration,
    )
}

/**
 * [BackendWorkloads] over the Kubernetes API.
 *
 * @property namespace where the backends' Deployments run.
 * @property pollInterval how often a wait lists the backend's pods.
 */
class K8sBackendWorkloads(
    private val clientProvider: K8sClientProvider,
    private val namespace: String = Constants.K8s.NAMESPACE,
    private val pollInterval: Duration = Duration.ofSeconds(Constants.TeardownFlush.POLL_INTERVAL_SECONDS),
) : BackendWorkloads {
    private companion object {
        const val NAME_LABEL = "app.kubernetes.io/name"
    }

    override fun scaleDown(
        controlHost: ClusterHost,
        workload: String,
        timeout: Duration,
    ) = clientProvider.createClient(controlHost).use { client ->
        deployment(client, workload).scale(0)
        awaitPods(client, workload, timeout, "gone") { it.isEmpty() }
    }

    private fun deployment(
        client: KubernetesClient,
        workload: String,
    ) = client
        .apps()
        .deployments()
        .inNamespace(namespace)
        .withName(workload)

    private fun pods(
        client: KubernetesClient,
        workload: String,
    ): List<Pod> =
        client
            .pods()
            .inNamespace(namespace)
            .withLabel(NAME_LABEL, workload)
            .list()
            .items

    private fun awaitPods(
        client: KubernetesClient,
        workload: String,
        timeout: Duration,
        what: String,
        done: (List<Pod>) -> Boolean,
    ) {
        val attempts = (timeout.toMillis() / pollInterval.toMillis()).toInt().coerceAtLeast(1) + 1
        val config = RetryUtil.createPollUntilRetryConfig<List<Pod>>(attempts, pollInterval, done, Instant.now().plus(timeout))
        val pods = Retry.decorateSupplier(Retry.of("await-$workload-$what", config)) { pods(client, workload) }.get()
        check(done(pods)) { "$workload was not $what within ${timeout.seconds}s" }
    }
}
