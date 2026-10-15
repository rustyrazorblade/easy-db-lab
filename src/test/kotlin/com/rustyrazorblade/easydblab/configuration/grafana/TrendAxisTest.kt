package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A Trend panel draws its `xField` with the panel's default unit unless an override gives that field
 * its own. Experiment: System's elapsed-time Trends had none, so their hours-since-start axis read
 * "0% ... 2.5%" and "0 B/s ... 2.5 B/s". Every Trend panel on a core or kit dashboard gives its
 * `xField` a unit through a `byName` override.
 */
class TrendAxisTest {
    private fun panels(element: JsonElement): List<JsonObject> =
        when (element) {
            is JsonObject ->
                listOfNotNull(element.takeIf { it["type"] is JsonPrimitive && it["gridPos"] != null }) +
                    element.values.flatMap { panels(it) }
            is JsonArray -> element.flatMap { panels(it) }
            else -> emptyList()
        }

    /** The title of each Trend panel in [dashboard] whose `xField` has no unit override. */
    private fun trendsWithoutAxisUnit(dashboard: JsonElement): List<String> =
        panels(dashboard)
            .filter { it["type"]?.jsonPrimitive?.content == "trend" }
            .filter { panel ->
                val xField =
                    panel["options"]
                        ?.jsonObject
                        ?.get("xField")
                        ?.jsonPrimitive
                        ?.content
                val overrides =
                    panel["fieldConfig"]
                        ?.jsonObject
                        ?.get("overrides")
                        ?.jsonArray
                        .orEmpty()
                xField == null ||
                    overrides.none { override ->
                        val matcher = override.jsonObject["matcher"]?.jsonObject
                        matcher?.get("id")?.jsonPrimitive?.content == "byName" &&
                            matcher["options"]?.jsonPrimitive?.content == xField &&
                            override.jsonObject["properties"]?.jsonArray.orEmpty().any {
                                it.jsonObject["id"]?.jsonPrimitive?.content == "unit"
                            }
                    }
            }.map { it["title"]?.jsonPrimitive?.content.orEmpty() }

    @Test
    fun `every trend panel gives its x field a unit`() {
        val missing =
            DashboardFiles.all().flatMap { file ->
                trendsWithoutAxisUnit(Json.parseToJsonElement(file.readText())).map { "${file.path}: $it" }
            }

        assertThat(missing).isEmpty()
    }

    @Test
    fun `a trend panel whose x field takes the panel unit is caught`() {
        val dashboard =
            Json.parseToJsonElement(
                """{"panels": [
                  {"type": "trend", "title": "no override", "gridPos": {}, "options": {"xField": "Value #A"},
                   "fieldConfig": {"defaults": {"unit": "percent"}, "overrides": []}},
                  {"type": "trend", "title": "other field", "gridPos": {}, "options": {"xField": "Value #A"},
                   "fieldConfig": {"overrides": [{"matcher": {"id": "byName", "options": "Value #B"},
                     "properties": [{"id": "unit", "value": "h"}]}]}},
                  {"type": "trend", "title": "axis unit", "gridPos": {}, "options": {"xField": "Value #A"},
                   "fieldConfig": {"overrides": [{"matcher": {"id": "byName", "options": "Value #A"},
                     "properties": [{"id": "unit", "value": "h"}]}]}},
                  {"type": "timeseries", "title": "not a trend", "gridPos": {}}
                ]}""",
            )

        assertThat(trendsWithoutAxisUnit(dashboard)).containsExactly("no override", "other field")
    }
}
