package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The Log Investigation dashboard's volume histogram must count exactly the lines its Logs panel
 * shows. A filter variable the histogram ignores makes a spike in the graph disagree with the list
 * below it, so every filter the dashboard offers has to appear in the histogram's LogQL.
 */
class LogInvestigationDashboardTest {
    private val dashboard: JsonObject =
        Json.parseToJsonElement(File("dashboards/observability/log-investigation.json").readText()).jsonObject

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

    /** The user-facing filters: every variable except the datasource picker and ad hoc filters. */
    private val filterVariables: List<String> =
        dashboard
            .getValue("templating")
            .jsonObject
            .getValue("list")
            .jsonArray
            .map { it.jsonObject }
            .filterNot { it.string("type") in setOf("datasource", "adhoc") }
            .map { it.getValue("name").jsonPrimitive.content }

    @Test
    fun `a Loki time series counts log volume under every filter variable`() {
        assertThat(filterVariables).contains("cluster", "source", "service", "severity", "search")

        val volume =
            dashboard
                .getValue("panels")
                .jsonArray
                .map { it.jsonObject }
                .filter { it.string("type") == "timeseries" }
                .filter { it.getValue("datasource").jsonObject.string("uid") == "loki" }
                .flatMap { panel -> panel.getValue("targets").jsonArray.mapNotNull { it.jsonObject.string("expr") } }
                .filter { it.contains("count_over_time(") }

        assertThat(volume).isNotEmpty()
        for (expr in volume) {
            for (name in filterVariables) {
                assertThat(expr).describedAs("volume query must use \$$name").containsPattern("\\$\\{?$name\\b")
            }
        }
    }
}
