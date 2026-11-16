package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.services.K8sClientProvider
import io.fabric8.kubernetes.api.model.ListOptionsBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Takes one [ControlPlaneHealth.Sample] of the API server (design D6): how long a `get nodes` with
 * `limit=1` takes, which goes through the datastore like a full list without moving 1,000 Nodes,
 * and how much `apiserver_flowcontrol_rejected_requests_total`, summed over every priority level,
 * grew since the last sample. Both go through one client from [K8sClientProvider], on the
 * per-client SOCKS tunnel.
 *
 * Used from the health monitor's thread only: it keeps the last counter total.
 */
class ControlPlaneSampler private constructor(
    private val client: KubernetesClient,
) : AutoCloseable {
    private var lastRejected: Double? = null

    /** One sample; a call that fails or that the API server refuses is [ControlPlaneHealth.Sample.Unreachable]. */
    fun sample(): ControlPlaneHealth.Sample =
        try {
            val began = System.nanoTime()
            client.nodes().list(ListOptionsBuilder().withLimit(1L).build())
            val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
            val total = rejectedRequests(client.raw(METRICS_PATH).orEmpty())
            val growth = lastRejected?.let { (total - it).coerceAtLeast(0.0) } ?: 0.0
            lastRejected = total
            ControlPlaneHealth.Sample.Reading(latencyMs, growth.toLong())
        } catch (e: KubernetesClientException) {
            ControlPlaneHealth.Sample.Unreachable(e.message ?: e::class.java.simpleName)
        } catch (e: IOException) {
            ControlPlaneHealth.Sample.Unreachable(e.message ?: e::class.java.simpleName)
        }

    override fun close() = client.close()

    /** Opening a sampler, and reading the API server's metrics text. */
    companion object {
        private const val METRICS_PATH = "/metrics"
        private const val REJECTED = "apiserver_flowcontrol_rejected_requests_total"

        /** Opens a sampler on a client of its own from [clientProvider]; close it to stop. */
        fun open(clientProvider: K8sClientProvider): ControlPlaneSampler = ControlPlaneSampler(clientProvider.createClient())

        /**
         * The sum of every [REJECTED] series in the Prometheus text [metrics]; zero when the API
         * server has rejected nothing yet, since a counter series appears only once it counts.
         */
        fun rejectedRequests(metrics: String): Double =
            metrics
                .lineSequence()
                .filter { it.startsWith("$REJECTED{") || it.startsWith("$REJECTED ") }
                .mapNotNull(::sampleValue)
                .sum()

        /** The value of one sample line, `name{labels} value [timestamp]`; a quoted label value may hold anything. */
        private fun sampleValue(line: String): Double? {
            val rest = line.removePrefix(REJECTED)
            val afterLabels = if (rest.startsWith('{')) rest.substring(labelsEnd(rest) + 1) else rest
            return afterLabels
                .trim()
                .split(' ', '\t')
                .firstOrNull()
                ?.toDoubleOrNull()
        }

        /** The index of the `}` that closes the label set opening [labels], skipping quoted values. */
        private fun labelsEnd(labels: String): Int {
            var quoted = false
            var escaped = false
            labels.forEachIndexed { i, c ->
                when {
                    escaped -> escaped = false
                    c == '\\' && quoted -> escaped = true
                    c == '"' -> quoted = !quoted
                    c == '}' && !quoted -> return i
                }
            }
            return labels.length - 1
        }
    }
}
