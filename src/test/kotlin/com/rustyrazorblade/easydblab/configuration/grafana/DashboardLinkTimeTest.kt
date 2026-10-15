package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every `/d/` link keeps the time range the operator is looking at: `from` and `to` in its URL,
 * `${__url_time_range}`, or `keepTime`. The postgres extension dashboards' header links carried
 * none, so following one reset the time. Links into the Tests, Experiment and pair dashboards are
 * the exception: those open on their own relative ranges, because an absolute range turns panel
 * `timeFrom` off.
 */
class DashboardLinkTimeTest {
    /** Tests, Experiment: Cassandra (the former Cluster Comparison uid), Experiment: System and the three pair dashboards. */
    private val ownRangeDashboards =
        listOf(
            "tests",
            "c09599ec-8d87-4fe5-9346-9e85c41d33af",
            "experiment-system",
            "cluster-pair",
            "ab-comparison",
            "system-ab-comparison",
        )

    @Test
    fun `every own-range dashboard exists under its uid`() {
        val uids =
            DashboardFiles.all().mapNotNull { file ->
                Json
                    .parseToJsonElement(file.readText())
                    .jsonObject["uid"]
                    ?.jsonPrimitive
                    ?.content
            }

        assertThat(uids).containsAll(ownRangeDashboards)
    }

    /** Every object in [element] with a `/d/` url, as (url, keepTime). */
    private fun dashboardLinks(element: JsonElement): List<Pair<String, Boolean>> =
        when (element) {
            is JsonObject -> {
                val url = (element["url"] as? JsonPrimitive)?.content.orEmpty()
                val self =
                    if (url.startsWith("/d/")) {
                        listOf(url to ((element["keepTime"] as? JsonPrimitive)?.content == "true"))
                    } else {
                        emptyList()
                    }
                self + element.values.flatMap { dashboardLinks(it) }
            }
            is JsonArray -> element.flatMap { dashboardLinks(it) }
            else -> emptyList()
        }

    private fun carriesTime(
        url: String,
        keepTime: Boolean,
    ): Boolean =
        keepTime || "\${__url_time_range}" in url || (Regex("[?&]from=").containsMatchIn(url) && Regex("[?&]to=").containsMatchIn(url))

    private fun target(url: String): String = url.removePrefix("/d/").substringBefore('/').substringBefore('?')

    @Test
    fun `every dashboard link keeps the time range`() {
        val dropped =
            DashboardFiles.all().flatMap { file ->
                dashboardLinks(Json.parseToJsonElement(file.readText()))
                    .filter { (url, keepTime) -> target(url) !in ownRangeDashboards && !carriesTime(url, keepTime) }
                    .map { (url, _) -> "${file.path}: $url" }
            }

        assertThat(dropped).isEmpty()
    }
}
