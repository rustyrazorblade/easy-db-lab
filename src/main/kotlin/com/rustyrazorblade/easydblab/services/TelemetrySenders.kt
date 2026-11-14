package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.resilience4j.retry.Retry
import java.time.Duration
import java.time.Instant

/**
 * The processes that send telemetry to the cluster's backends, as the pre-teardown save sees them.
 *
 * The OTel collectors are the only clients of Tempo's receiver: Beyla, the Java agents, the stress
 * sidecar, Fluent Bit and EMR all send to one of them. Stopping them quiets Tempo so its drain can
 * finish, and their graceful shutdown sends the last batches to Loki and Mimir while they still
 * accept writes. It is kept apart from [BackendWorkloads], whose contract covers only the backends.
 */
interface TelemetrySenders {
    companion object {
        /**
         * The collector DaemonSets, in stop order: the host add-on forwards to the core, so it goes
         * first and its last batches still reach a running core.
         */
        val COLLECTORS: List<String> =
            listOf(Constants.OtelCollector.HOST_COLLECTOR_NAME, Constants.K8s.OTEL_COLLECTOR_APP_LABEL)
    }

    /**
     * Deletes each collector's DaemonSet in [COLLECTORS] order, waiting until its pods are gone
     * before the next, all within one [timeout] that starts with the call. A collector with neither
     * a DaemonSet nor a pod (never deployed, as the add-on is where pods may not be privileged, or
     * gone after an earlier `down`) is not deleted: [onAbsent] is called with its name instead. A
     * collector whose pods outlive the deadline fails the stop, naming it, and no later collector is
     * deleted. [onDeleting] is called with each collector's name just before its delete, so a
     * caller knows which collectors a failed stop left untouched.
     */
    fun stop(
        timeout: Duration,
        onAbsent: (String) -> Unit = {},
        onDeleting: (String) -> Unit = {},
    )
}

/**
 * [TelemetrySenders] over the Kubernetes API.
 *
 * @property namespace where the collectors' DaemonSets run.
 * @property pollInterval how often a wait lists a collector's pods.
 */
class K8sTelemetrySenders(
    private val clientProvider: K8sClientProvider,
    private val namespace: String = Constants.K8s.NAMESPACE,
    private val pollInterval: Duration = Duration.ofSeconds(Constants.TeardownFlush.POLL_INTERVAL_SECONDS),
) : TelemetrySenders {
    private companion object {
        const val NAME_LABEL = "app.kubernetes.io/name"
    }

    override fun stop(
        timeout: Duration,
        onAbsent: (String) -> Unit,
        onDeleting: (String) -> Unit,
    ) = clientProvider.createClient().use { client ->
        val deadline = Instant.now().plus(timeout)
        TelemetrySenders.COLLECTORS.forEach { name ->
            if (daemonSet(client, name).get() == null && pods(client, name).isEmpty()) {
                onAbsent(name)
            } else {
                onDeleting(name)
                stopOne(client, name, deadline, timeout)
            }
        }
    }

    /** Deletes [name]'s DaemonSet and waits, until [deadline], for its pods to go. */
    private fun stopOne(
        client: KubernetesClient,
        name: String,
        deadline: Instant,
        timeout: Duration,
    ) {
        daemonSet(client, name).delete()
        // The shared deadline, not an attempt count, bounds the poll: the add-on's wait uses part of it.
        val gone: (List<Pod>) -> Boolean = { it.isEmpty() }
        val config = RetryUtil.createPollUntilRetryConfig(Int.MAX_VALUE, pollInterval, gone, deadline)
        val pods = Retry.decorateSupplier(Retry.of("await-$name-gone", config)) { pods(client, name) }.get()
        check(gone(pods)) { "$name still has ${pods.size} pod(s) ${timeout.seconds}s after the collectors' stop began" }
    }

    private fun daemonSet(
        client: KubernetesClient,
        name: String,
    ) = client
        .apps()
        .daemonSets()
        .inNamespace(namespace)
        .withName(name)

    private fun pods(
        client: KubernetesClient,
        name: String,
    ): List<Pod> =
        client
            .pods()
            .inNamespace(namespace)
            .withLabel(NAME_LABEL, name)
            .list()
            .items
}
