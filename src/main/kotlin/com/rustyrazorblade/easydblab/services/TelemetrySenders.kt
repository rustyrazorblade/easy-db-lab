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
 * The processes that send telemetry to the cluster's backends, as the pre-teardown save sees them.
 *
 * The OTel collector is the only client of Tempo's receiver: Beyla, the Java agents, the stress
 * sidecar, Fluent Bit and EMR all send to it. Stopping it quiets Tempo so its drain can finish, and
 * its graceful shutdown sends its last batches to Loki and Mimir while they still accept writes.
 * It is kept apart from [BackendWorkloads], whose contract covers only the backends.
 */
interface TelemetrySenders {
    /**
     * Deletes the collector's DaemonSet and waits up to [timeout] until its pods are gone. Succeeds
     * when the collector is already gone.
     */
    fun stop(
        controlHost: ClusterHost,
        timeout: Duration,
    )
}

/**
 * [TelemetrySenders] over the Kubernetes API.
 *
 * @property namespace where the collector's DaemonSet runs.
 * @property pollInterval how often the wait lists the collector's pods.
 */
class K8sTelemetrySenders(
    private val clientProvider: K8sClientProvider,
    private val namespace: String = Constants.K8s.NAMESPACE,
    private val pollInterval: Duration = Duration.ofSeconds(Constants.TeardownFlush.POLL_INTERVAL_SECONDS),
) : TelemetrySenders {
    private companion object {
        const val NAME_LABEL = "app.kubernetes.io/name"
        const val COLLECTOR = Constants.K8s.OTEL_COLLECTOR_APP_LABEL
    }

    override fun stop(
        controlHost: ClusterHost,
        timeout: Duration,
    ) = clientProvider.createClient(controlHost).use { client ->
        client
            .apps()
            .daemonSets()
            .inNamespace(namespace)
            .withName(COLLECTOR)
            .delete()
        val attempts = (timeout.toMillis() / pollInterval.toMillis()).toInt().coerceAtLeast(1) + 1
        val gone: (List<Pod>) -> Boolean = { it.isEmpty() }
        val config = RetryUtil.createPollUntilRetryConfig(attempts, pollInterval, gone, Instant.now().plus(timeout))
        val pods = Retry.decorateSupplier(Retry.of("await-$COLLECTOR-gone", config)) { pods(client) }.get()
        check(gone(pods)) { "$COLLECTOR still has ${pods.size} pod(s) ${timeout.seconds}s after its DaemonSet was deleted" }
    }

    private fun pods(client: KubernetesClient): List<Pod> =
        client
            .pods()
            .inNamespace(namespace)
            .withLabel(NAME_LABEL, COLLECTOR)
            .list()
            .items
}
