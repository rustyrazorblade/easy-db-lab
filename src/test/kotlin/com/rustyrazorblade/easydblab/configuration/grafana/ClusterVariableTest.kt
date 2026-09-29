package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every `cluster` variable lists the clusters of the tenant the Metrics picker selects the same way:
 * `label_values(up, cluster)` on `${metrics_datasource}`, multi-select with "All". The Tests dashboard
 * selects one test, so its `cluster` is single-select; it lists over its default time range, which
 * therefore equals the default `lookback` of its test listing.
 */
class ClusterVariableTest {
    private val clusterQuery = "label_values(up, cluster)"

    private fun parse(file: File): JsonObject = Json.parseToJsonElement(file.readText()).jsonObject

    private fun variables(dashboard: JsonObject): List<JsonObject> =
        (
            dashboard["templating"]
                ?.jsonObject
                ?.get("list")
                ?.jsonArray
                .orEmpty()
        ).map { it.jsonObject }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** The query a Prometheus query variable runs: the editor object's `query`, or the legacy string. */
    private fun queryOf(variable: JsonObject): String? =
        when (val query = variable["query"]) {
            is JsonObject -> query.string("query")
            is JsonPrimitive -> query.contentOrNull
            else -> null
        }

    private fun clusterVariables(): List<Pair<String, Pair<JsonObject, JsonObject>>> =
        DashboardFiles.all().flatMap { file ->
            val dashboard = parse(file)
            variables(dashboard).filter { it.string("name") == "cluster" }.map { file.path to (dashboard to it) }
        }

    @Test
    fun `every cluster variable reads the metrics picker with label_values of up`() {
        val clusters = clusterVariables()
        assertThat(clusters).isNotEmpty()

        val wrong =
            clusters.mapNotNull { (path, pair) ->
                val variable = pair.second
                val datasource = variable["datasource"]?.jsonObject?.string("uid")
                val query = queryOf(variable)
                "$path: query=$query datasource=$datasource".takeUnless {
                    query == clusterQuery &&
                        datasource == "\${metrics_datasource}" &&
                        (variable.string("definition") ?: clusterQuery) == clusterQuery
                }
            }
        assertThat(wrong).isEmpty()
    }

    @Test
    fun `every cluster variable but the Tests dashboard's is multi-select with All`() {
        val wrong =
            clusterVariables()
                .filterNot { (_, pair) -> pair.first.string("uid") == TESTS_UID }
                .mapNotNull { (path, pair) ->
                    val variable = pair.second
                    "$path: multi=${variable.string("multi")} includeAll=${variable.string("includeAll")}".takeUnless {
                        variable.string("multi") == "true" && variable.string("includeAll") == "true"
                    }
                }
        assertThat(wrong).isEmpty()
    }

    @Test
    fun `the Tests dashboard selects one test and lists over its default lookback`() {
        val tests = parse(File("dashboards/infrastructure/tests.json"))
        val variables = variables(tests).associateBy { it.string("name") }
        val cluster = variables.getValue("cluster")
        val lookback =
            variables
                .getValue("lookback")
                .getValue("current")
                .jsonObject
                .string("value")

        assertThat(cluster.string("multi")).isEqualTo("false")
        assertThat(cluster.string("includeAll")).isEqualTo("false")
        assertThat(tests.getValue("time").jsonObject.string("from")).isEqualTo("now-$lookback")
        assertThat(tests.getValue("time").jsonObject.string("to")).isEqualTo("now")
    }

    /** Every string in [element], at any depth. */
    private fun strings(element: JsonElement): List<String> =
        when (element) {
            is JsonObject -> element.values.flatMap { strings(it) }
            is JsonArray -> element.flatMap { strings(it) }
            is JsonPrimitive -> listOfNotNull(element.contentOrNull.takeIf { element.isString })
        }

    /**
     * A multi-select `cluster` renders as a regex (`(a|b)`, or the All value), which an equality
     * matcher never matches: `cluster="\$cluster"` goes empty as soon as a second cluster is picked.
     */
    @Test
    fun `no query on a dashboard with a multi-select cluster matches it by equality`() {
        val equality = Regex("""cluster\s*=\s*"\$\{?cluster\b""")
        val wrong =
            DashboardFiles.all().flatMap { file ->
                val dashboard = parse(file)
                val multi = variables(dashboard).any { it.string("name") == "cluster" && it.string("multi") == "true" }
                if (!multi) emptyList() else strings(dashboard).filter { equality.containsMatchIn(it) }.map { "${file.path}: $it" }
            }
        assertThat(wrong).isEmpty()
    }

    private companion object {
        const val TESTS_UID = "tests"
    }
}
