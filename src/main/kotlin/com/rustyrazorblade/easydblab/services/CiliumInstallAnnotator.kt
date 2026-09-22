package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import java.time.Clock

/**
 * One annotation [CiliumInstallAnnotator.post] created: what was sent and what Grafana answered.
 */
data class PostedAnnotation(
    val request: GrafanaAnnotationRequest,
    val response: GrafanaAnnotationResponse,
)

/**
 * Records the Cilium install window as Grafana annotations and posts them once Grafana exists.
 *
 * Cilium installs on the K3s server-ready hook, before the observability stack (and so Grafana)
 * is deployed, so the annotations cannot be posted at the moment the install runs. Instead
 * [CiliumService] records the start and the finish (or failure) here with their real timestamps,
 * and `up` calls [post] after the stack is up. The markers then land on the dashboards at the time
 * the install actually happened, tagged [Constants.Cilium.ANNOTATION_TAG] and the global tag so the
 * core dashboards' tag-filtered annotation query renders them.
 *
 * This is a Koin singleton: the service that records and the command that posts are different
 * objects, and the pending list must be shared between them within one process.
 *
 * @property grafanaDashboardService Posts the annotations over the Grafana HTTP API.
 * @property clock Time source; tests inject a fixed clock to assert the recorded timestamps.
 */
class CiliumInstallAnnotator(
    private val grafanaDashboardService: GrafanaDashboardService,
    private val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        const val STARTED_TEXT = "Cilium install started"
        const val FINISHED_TEXT = "Cilium install finished"
        const val FAILED_TEXT = "Cilium install failed"
        val TAGS = listOf(Constants.Cilium.ANNOTATION_TAG, Constants.Grafana.GLOBAL_ANNOTATION_TAG)
    }

    private val _pending = mutableListOf<GrafanaAnnotationRequest>()

    /** The annotations recorded so far and not yet posted, in record order. */
    val pending: List<GrafanaAnnotationRequest> get() = _pending.toList()

    /** Records that the Cilium install began now. */
    fun installStarted() = record(STARTED_TEXT)

    /** Records that the Cilium install completed now. */
    fun installFinished() = record(FINISHED_TEXT)

    /** Records that the Cilium install failed now, with the error in the annotation body. */
    fun installFailed(error: String) = record("$FAILED_TEXT: $error")

    /**
     * Posts every pending annotation to the cluster's Grafana and clears the pending list.
     *
     * Annotations are posted in record order. If a post fails, the annotations already posted are
     * dropped and the rest stay pending, so the failure surfaces and a retry does not duplicate.
     *
     * @param controlHost The control node running Grafana.
     * @return The annotations posted, in order; empty when nothing was pending.
     */
    fun post(controlHost: ClusterHost): Result<List<PostedAnnotation>> =
        runCatching {
            val posted = mutableListOf<PostedAnnotation>()
            while (_pending.isNotEmpty()) {
                val request = _pending.first()
                posted += PostedAnnotation(request, grafanaDashboardService.createAnnotation(controlHost, request))
                _pending.removeAt(0)
            }
            posted.toList()
        }

    private fun record(text: String) {
        _pending += GrafanaAnnotationRequest(text = text, tags = TAGS, time = clock.millis())
    }
}
