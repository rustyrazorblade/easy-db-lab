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
 * A panel's `timeFrom` and `timeShift` survive runs of any length. Grafana's date math reads at most
 * five digits per number, so `${x}s` fails with "invalid timeshift" once `x` passes 99999 seconds
 * (27.8 hours). A variable length is written as whole days and the remaining seconds instead,
 * `${x_d}d-${x_s}s`, and both parts come from the same helper.
 */
class PanelTimeOverrideTest {
    private val singleSeconds = Regex("""^\$\{[A-Za-z0-9_]+}s$""")
    private val daysAndSeconds = Regex("""^\$\{([A-Za-z0-9_]+)_d}d-\$\{([A-Za-z0-9_]+)_s}s$""")

    private data class Override(
        val location: String,
        val value: String,
    )

    private fun overrides(
        element: JsonElement,
        location: String,
    ): List<Override> =
        when (element) {
            is JsonObject ->
                element.flatMap { (key, value) ->
                    if (key in OVERRIDE_FIELDS && value is JsonPrimitive && value.isString) {
                        listOf(Override("$location $key", value.content))
                    } else {
                        overrides(value, location)
                    }
                }
            is JsonArray -> element.flatMap { overrides(it, location) }
            else -> emptyList()
        }

    private fun queriesByName(dashboard: JsonObject): Map<String, String> =
        dashboard["templating"]
            ?.jsonObject
            ?.get("list")
            ?.jsonArray
            .orEmpty()
            .map { it.jsonObject }
            .associate { it.getValue("name").jsonPrimitive.content to (it["query"] as? JsonPrimitive)?.content.orEmpty() }

    @Test
    fun `no panel time override is a single variable in seconds`() {
        val offenders =
            DashboardFiles.all().flatMap { file ->
                overrides(Json.parseToJsonElement(file.readText()), file.path).filter { singleSeconds.matches(it.value) }
            }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `every variable override is the days and seconds of one helper`() {
        val checked =
            DashboardFiles.all().flatMap { file ->
                val dashboard = Json.parseToJsonElement(file.readText()).jsonObject
                val queries = queriesByName(dashboard)
                overrides(dashboard, file.path).filter { "$" in it.value }.onEach { override ->
                    val parts =
                        daysAndSeconds
                            .matchEntire(override.value)
                            ?.destructured
                            ?.toList()
                            .orEmpty()
                    assertThat(parts).describedAs("${override.location}: ${override.value}").hasSize(2)
                    val (days, seconds) = parts
                    assertThat(days).describedAs(override.location).isEqualTo(seconds)
                    assertThat(queries["${days}_d"]).describedAs(override.location).isEqualTo("query_result(floor(vector(\${$days}) / 86400))")
                    assertThat(queries["${seconds}_s"]).describedAs(override.location).isEqualTo("query_result(vector(\${$seconds}) % 86400)")
                }
            }

        assertThat(checked).isNotEmpty()
    }

    private companion object {
        val OVERRIDE_FIELDS = setOf("timeFrom", "timeShift")
    }
}
