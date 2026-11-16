package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A table that shows the build (`cassandra_build`) makes that column a string before it organizes
 * the table. Grafana guesses a numeric type for a value such as `5.0` and printed the build as `5`
 * on A/B Comparison.
 */
class BuildColumnTest {
    @Test
    fun `a table grouped by build that does not convert the build to a string is reported`() {
        val grouped = """{"expr": "max by (cluster, cassandra_build) (up)"}"""
        val conversion = """{"targetField": "cassandra_build", "destinationType": "string"}"""
        val convert = """{"id": "convertFieldType", "options": {"conversions": [$conversion]}}"""
        val organize = """{"id": "organize", "options": {}}"""

        assertThat(problems(parse("""{"type": "table", "targets": [$grouped], "transformations": [$organize]}"""))).hasSize(1)
        assertThat(problems(parse("""{"type": "table", "targets": [$grouped], "transformations": [$convert, $organize]}"""))).isEmpty()
        assertThat(problems(parse("""{"type": "table", "targets": [$grouped], "transformations": [$organize, $convert]}"""))).hasSize(1)
    }

    @Test
    fun `every table that shows the build shows it as text`() {
        val found =
            DashboardFiles.all().flatMap { file ->
                panels(parse(file.readText())).flatMap { panel ->
                    problems(panel).map { "${file.path} panel '${(panel["title"] as? JsonPrimitive)?.contentOrNull}': $it" }
                }
            }

        assertThat(found).isEmpty()
    }

    /** Why a table [panel] that groups by `cassandra_build` does not turn it into a string before `organize`. */
    private fun problems(panel: JsonObject): List<String> {
        if ((panel["type"] as? JsonPrimitive)?.contentOrNull != "table") return emptyList()
        val exprs = (panel["targets"] as? JsonArray).orEmpty().mapNotNull { text(it, "expr") }
        if (exprs.none { BUILD_GROUPING.containsMatchIn(it) }) return emptyList()
        val ids = (panel["transformations"] as? JsonArray).orEmpty().map { text(it, "id") }
        val converts =
            (panel["transformations"] as? JsonArray).orEmpty().indexOfFirst { transformation ->
                val options = (transformation as? JsonObject)?.get("options") as? JsonObject
                ((options?.get("conversions") as? JsonArray).orEmpty()).any { conversion ->
                    text(conversion, "targetField") == BUILD && text(conversion, "destinationType") == "string"
                }
            }
        val organize = ids.indexOf("organize")
        return listOfNotNull(
            "the build column is not converted to a string before organize".takeIf { converts < 0 || (organize in 0 until converts) },
        )
    }

    private fun panels(dashboard: JsonObject): List<JsonObject> {
        fun walk(list: JsonArray?): List<JsonObject> =
            list.orEmpty().filterIsInstance<JsonObject>().flatMap { listOf(it) + walk(it["panels"] as? JsonArray) }
        return walk(dashboard["panels"] as? JsonArray)
    }

    private fun text(
        element: JsonElement,
        key: String,
    ): String? = ((element as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private companion object {
        const val BUILD = "cassandra_build"
        val BUILD_GROUPING = Regex("""by\s*\([^)]*\bcassandra_build\b""")
    }
}
