package com.rustyrazorblade.easydblab.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for `POST /api/annotations` on the Grafana HTTP API.
 *
 * Field names match Grafana's JSON keys exactly. Null fields are omitted on the wire so a global
 * annotation (no dashboard/panel scope, no explicit end time) sends only the fields it sets. See
 * the `grafana annotate` command and [GrafanaDashboardService.createAnnotation].
 *
 * @property text The human-readable annotation body.
 * @property tags Zero or more tags. The core dashboards render global annotations by a tag filter.
 * @property time Start time in epoch milliseconds.
 * @property timeEnd Optional end time in epoch milliseconds; when set, Grafana renders a region.
 * @property dashboardUID Optional dashboard UID to scope the annotation to one dashboard.
 * @property panelId Optional panel id to scope the annotation to one panel.
 */
@Serializable
data class GrafanaAnnotationRequest(
    val text: String,
    val tags: List<String> = emptyList(),
    val time: Long,
    val timeEnd: Long? = null,
    @SerialName("dashboardUID")
    val dashboardUID: String? = null,
    val panelId: Int? = null,
)

/**
 * Response body Grafana returns from a successful `POST /api/annotations`.
 *
 * Grafana replies with the created annotation's id and a confirmation message. Unknown fields are
 * ignored on decode so a Grafana version that adds fields does not break parsing.
 *
 * @property id The id Grafana assigned to the created annotation.
 * @property message Grafana's confirmation message (e.g. "Annotation added").
 */
@Serializable
data class GrafanaAnnotationResponse(
    val id: Long,
    val message: String = "",
)
