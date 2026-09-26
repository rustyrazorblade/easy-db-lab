package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Clock
import java.time.Duration

/** Lists every annotation a Grafana holds: the one part of the Grafana API the mirror needs. */
interface GrafanaAnnotationSource {
    /** The raw JSON array Grafana returns from `GET /api/annotations` on [controlHost]. */
    fun fetchAnnotations(controlHost: ClusterHost): String
}

/**
 * One Grafana annotation as it is mirrored to Loki.
 *
 * @property id Grafana's id; it names the annotation's Loki stream.
 * @property time Start, epoch milliseconds; the Loki entry's timestamp.
 * @property timeEnd End of a region annotation, epoch milliseconds; null for a point annotation.
 * @property dashboardUid The dashboard a scoped annotation belongs to; null for a global one.
 * @property panelId The panel a scoped annotation belongs to; null when not panel-scoped.
 */
data class MirroredAnnotation(
    val id: Long,
    val text: String,
    val tags: List<String>,
    val time: Long,
    val timeEnd: Long? = null,
    val dashboardUid: String? = null,
    val panelId: Int? = null,
)

/** This annotation as mirrored to Loki, under the [id] Grafana gave it when it was created. */
fun GrafanaAnnotationRequest.toMirrored(id: Long): MirroredAnnotation =
    MirroredAnnotation(
        id = id,
        text = text,
        tags = tags,
        time = time,
        timeEnd = timeEnd,
        dashboardUid = dashboardUID,
        panelId = panelId,
    )

/**
 * Copies Grafana annotations into Loki, under the cluster's tenant, so they outlive the cluster's
 * Grafana with the rest of its observability data.
 *
 * Grafana stays where annotations are written. Each annotation is its own Loki stream
 * (`cluster`, `source="annotation"`, `annotation_id`), with the text as the line and the tags,
 * dashboard, panel and end time as structured metadata. Pushing the same annotation again writes
 * the same entry to the same stream, which Loki stores once; and since no two annotations share a
 * stream, a backdated one is never out of order.
 */
interface AnnotationMirror {
    /** Mirrors one annotation the tool just created. */
    fun push(annotation: MirroredAnnotation): Result<Unit>

    /**
     * Mirrors every annotation Grafana on [controlHost] holds, including ones made in the UI. One
     * Loki would refuse, because it falls outside Loki's accepted window, is skipped with a warning
     * rather than failing the rest.
     *
     * @return how many annotations were mirrored.
     */
    fun syncAll(controlHost: ClusterHost): Result<Int>
}

/**
 * [AnnotationMirror] over Loki's push API.
 *
 * @property pushClient Writes the streams to Loki.
 * @property grafana Lists Grafana's annotations for [syncAll].
 * @property clusterStateManager The cluster label.
 * @property eventBus Warns about annotations Loki would refuse.
 * @property clock Where Loki's accepted window is measured from.
 */
class DefaultAnnotationMirror(
    private val pushClient: LokiPushClient,
    private val grafana: GrafanaAnnotationSource,
    private val clusterStateManager: ClusterStateManager,
    private val eventBus: EventBus,
    private val clock: Clock = Clock.systemUTC(),
) : AnnotationMirror {
    private val json = Json { ignoreUnknownKeys = true }

    override fun push(annotation: MirroredAnnotation): Result<Unit> = runCatching { pushAll(listOf(annotation)) }

    override fun syncAll(controlHost: ClusterHost): Result<Int> =
        runCatching {
            val annotations =
                json
                    .decodeFromString<List<GrafanaListedAnnotation>>(grafana.fetchAnnotations(controlHost))
                    .map { it.toMirrored() }
            val (accepted, refused) = annotations.partition { it.time in lokiWindow() }
            if (refused.isNotEmpty()) {
                eventBus.emit(
                    Event.Grafana.AnnotationsOutsideLokiWindow(
                        ids = refused.map { it.id },
                        maxAgeHours = Constants.Loki.MAX_ENTRY_AGE_HOURS,
                        maxAheadHours = Constants.Loki.MAX_ENTRY_AHEAD_HOURS,
                    ),
                )
            }
            if (accepted.isNotEmpty()) pushAll(accepted)
            accepted.size
        }

    /** The entry timestamps, epoch milliseconds, Loki accepts now, less a margin at each edge. */
    private fun lokiWindow(): LongRange {
        val now = clock.instant()
        val margin = Duration.ofMinutes(Constants.Loki.ENTRY_WINDOW_MARGIN_MINUTES)
        val oldest = now.minus(Duration.ofHours(Constants.Loki.MAX_ENTRY_AGE_HOURS)).plus(margin)
        val newest = now.plus(Duration.ofHours(Constants.Loki.MAX_ENTRY_AHEAD_HOURS)).minus(margin)
        return oldest.toEpochMilli()..newest.toEpochMilli()
    }

    private fun pushAll(annotations: List<MirroredAnnotation>) {
        val cluster = clusterStateManager.load().clusterLabelName()
        pushClient.push(annotations.map { it.toStream(cluster) })
    }

    private fun MirroredAnnotation.toStream(cluster: String): LokiPushStream =
        LokiPushStream(
            labels = mapOf("cluster" to cluster, "source" to Constants.Loki.ANNOTATION_SOURCE, "annotation_id" to id.toString()),
            timestampNanos = time * NANOS_PER_MILLI,
            line = text,
            metadata =
                listOfNotNull(
                    tags.takeIf { it.isNotEmpty() }?.let { "tags" to it.joinToString(",") },
                    dashboardUid?.let { "dashboard_uid" to it },
                    panelId?.let { "panel_id" to it.toString() },
                    timeEnd?.let { "time_end" to it.toString() },
                ).toMap(),
        )

    /** An annotation as `GET /api/annotations` lists it; unscoped fields come back empty or zero. */
    @Serializable
    private data class GrafanaListedAnnotation(
        val id: Long,
        val text: String = "",
        val tags: List<String> = emptyList(),
        val time: Long,
        val timeEnd: Long = 0,
        val dashboardUID: String? = null,
        val panelId: Int = 0,
    ) {
        fun toMirrored(): MirroredAnnotation =
            MirroredAnnotation(
                id = id,
                text = text,
                tags = tags,
                time = time,
                timeEnd = timeEnd.takeIf { it > time },
                dashboardUid = dashboardUID?.takeIf { it.isNotBlank() },
                panelId = panelId.takeIf { it != 0 },
            )
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * One Loki stream with a single entry.
 *
 * @property metadata Structured metadata of the entry; empty values are not sent.
 */
data class LokiPushStream(
    val labels: Map<String, String>,
    val timestampNanos: Long,
    val line: String,
    val metadata: Map<String, String>,
)

/**
 * Writes streams to the cluster's Loki with its push API (`/loki/api/v1/push`), in the cluster's
 * tenant, through [ObservabilityHttp].
 */
class LokiPushClient(
    private val http: ObservabilityHttp,
) {
    /**
     * Pushes [streams] in one request.
     *
     * @throws IllegalStateException when Loki does not accept them.
     */
    fun push(streams: List<LokiPushStream>) {
        val body =
            buildJsonObject {
                putJsonArray("streams") {
                    streams.forEach { stream ->
                        add(
                            buildJsonObject {
                                put("stream", buildJsonObject { stream.labels.forEach { (k, v) -> put(k, v) } })
                                put("values", JsonArray(listOf(entry(stream))))
                            },
                        )
                    }
                }
            }
        val response = http.post(Constants.K8s.LOKI_HTTP_PORT, PUSH_PATH, body.toString())
        check(response.code == Constants.HttpStatus.NO_CONTENT || response.code == Constants.HttpStatus.OK) {
            "Loki refused the push with status ${response.code}: ${response.body}"
        }
    }

    private fun entry(stream: LokiPushStream): JsonArray =
        buildJsonArray {
            add(JsonPrimitive(stream.timestampNanos.toString()))
            add(JsonPrimitive(stream.line))
            add(buildJsonObject { stream.metadata.forEach { (k, v) -> put(k, v) } })
        }

    private companion object {
        const val PUSH_PATH = "/loki/api/v1/push"
    }
}
