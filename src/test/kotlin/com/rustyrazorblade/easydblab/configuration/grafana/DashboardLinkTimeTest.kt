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

    /**
     * Whether a link into an own-range dashboard sets any time at all. Either end alone moves the
     * range off the dashboard's own relative default, so `from=` or `to=` counts, as do
     * `${'$'}{__url_time_range}` and `keepTime`.
     */
    private fun carriesAnyTime(
        url: String,
        keepTime: Boolean,
    ): Boolean = keepTime || "\${__url_time_range}" in url || Regex("[?&](from|to)=").containsMatchIn(url)

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

    /**
     * System A/B Comparison's link to the Cassandra A/B Comparison carried `from` and `to` and
     * `keepTime`, so the target opened on an absolute range and its overlay and side-by-side
     * panels lost their `timeFrom`: a run that ended earlier drew no line.
     */
    @Test
    fun `no link into an own-range dashboard carries a time range`() {
        val carried =
            DashboardFiles.all().flatMap { file ->
                dashboardLinks(Json.parseToJsonElement(file.readText()))
                    .filter { (url, keepTime) -> target(url) in ownRangeDashboards && carriesAnyTime(url, keepTime) }
                    .map { (url, _) -> "${file.path}: $url" }
            }

        assertThat(carried).isEmpty()
    }

    @Test
    fun `a time range on a link into an own-range dashboard is caught`() {
        val link =
            Json.parseToJsonElement(
                """{"links": [{"keepTime": false, "url": "/d/ab-comparison/a-b-comparison?from=${'$'}{__from}&to=${'$'}{__to}"},
                   {"keepTime": true, "url": "/d/cluster-pair/cluster-pair?var-cluster_a=x"},
                   {"url": "/d/tests/tests?var-experiment=x&from=${'$'}{__from}"},
                   {"url": "/d/experiment-system/x?to=now-1h&var-experiment=x"},
                   {"url": "/d/system-ab-comparison/x?var-cluster_a=x&${'$'}{__url_time_range}"},
                   {"url": "/d/tests/tests?var-from_cluster=x"},
                   {"keepTime": false, "url": "/d/tests/tests?var-experiment=x"}]}""",
            )

        val carried =
            dashboardLinks(link)
                .filter { (url, keepTime) -> target(url) in ownRangeDashboards && carriesAnyTime(url, keepTime) }
                .map { it.first }

        assertThat(carried).containsExactly(
            "/d/ab-comparison/a-b-comparison?from=\${__from}&to=\${__to}",
            "/d/cluster-pair/cluster-pair?var-cluster_a=x",
            "/d/tests/tests?var-experiment=x&from=\${__from}",
            "/d/experiment-system/x?to=now-1h&var-experiment=x",
            "/d/system-ab-comparison/x?var-cluster_a=x&\${__url_time_range}",
        )
    }
}
