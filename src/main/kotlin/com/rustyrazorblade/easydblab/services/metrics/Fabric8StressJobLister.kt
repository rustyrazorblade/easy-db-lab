package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.events.StressJobOutcome
import com.rustyrazorblade.easydblab.proxy.ObservabilityRoute
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import java.time.Duration
import java.time.Instant

/**
 * [StressJobLister] over the Kubernetes API, with a Fabric8 client built for each list from
 * [baseConfig] and the cycle's route.
 *
 * The route decides the proxy, per client: [ObservabilityRoute.Socks] sets
 * `httpsProxy = socks5://127.0.0.1:<port>`, and [ObservabilityRoute.Direct] sets none.  It does not
 * read the in-JVM published tunnel port, which can name a tunnel another process has replaced, and
 * it never touches the JVM-wide socks properties.
 *
 * @param baseConfig the client configuration for the workspace's kubeconfig, read for each list
 */
class Fabric8StressJobLister(
    private val baseConfig: () -> Config,
) : StressJobLister {
    override fun list(
        state: ClusterState,
        route: ObservabilityRoute,
    ): List<ListedStressJob> {
        val config = baseConfig().also { applyRoute(it, route) }
        return KubernetesClientBuilder().withConfig(config).build().use { client ->
            val nodeByJob =
                client
                    .pods()
                    .inNamespace(Constants.Stress.NAMESPACE)
                    .withLabel(Constants.Stress.LABEL_KEY, Constants.Stress.LABEL_VALUE)
                    .list()
                    .items
                    .mapNotNull { pod ->
                        val job = pod.metadata?.labels?.get(JOB_NAME_LABEL) ?: return@mapNotNull null
                        job to pod.spec?.nodeName.orEmpty()
                    }.filter { (_, node) -> node.isNotEmpty() }
                    .toMap()
            client
                .batch()
                .v1()
                .jobs()
                .inNamespace(Constants.Stress.NAMESPACE)
                .withLabel(Constants.Stress.LABEL_KEY, Constants.Stress.LABEL_VALUE)
                .list()
                .items
                .map { job -> listedStressJob(job, nodeByJob[job.metadata?.name].orEmpty()) }
        }
    }

    private companion object {
        const val JOB_NAME_LABEL = "job-name"
    }
}

/** Points [config] at [route]: an explicit per-client SOCKS proxy, or no proxy at all. */
internal fun applyRoute(
    config: Config,
    route: ObservabilityRoute,
) {
    config.httpsProxy =
        when (route) {
            is ObservabilityRoute.Socks -> "socks5://${Constants.Proxy.TUNNEL_LISTEN_ADDRESS}:${route.port}"
            ObservabilityRoute.Direct -> null
        }
}

/**
 * [job] as a cycle sees it, on [host].  It finished when its `Complete` or `Failed` condition is
 * `True`; its duration runs from `status.startTime` to the completion time (or, for a failure, the
 * condition's transition time).
 */
internal fun listedStressJob(
    job: Job,
    host: String,
): ListedStressJob {
    val name = job.metadata?.name.orEmpty()
    val status = job.status
    val finished =
        status?.conditions.orEmpty().firstOrNull { it.status == "True" && it.type in FINISHED_CONDITIONS }
            ?: return ListedStressJob.Running(name, host)
    val outcome = if (finished.type == COMPLETE) StressJobOutcome.SUCCEEDED else StressJobOutcome.FAILED
    val start = status?.startTime?.let(Instant::parse)
    val end = (status?.completionTime ?: finished.lastTransitionTime)?.let(Instant::parse)
    val seconds = if (start != null && end != null) Duration.between(start, end).seconds.coerceAtLeast(0) else 0
    return ListedStressJob.Finished(name, host, outcome, seconds)
}

private const val COMPLETE = "Complete"
private val FINISHED_CONDITIONS = setOf(COMPLETE, "Failed")
