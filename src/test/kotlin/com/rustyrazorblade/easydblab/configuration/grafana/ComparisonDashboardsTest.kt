package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The three comparison dashboards select two runs and compare them in three rows at the top. The
 * run variables replace `cluster_a`/`cluster_b`, list runs over `lookback`, and the new rows filter
 * by the selected run only: the A/B build and host filters apply to the older panels below.
 */
class ComparisonDashboardsTest {
    private val comparisonDashboards =
        listOf(
            "cassandra/cluster-comparison.json",
            "cassandra/ab-comparison.json",
            "infrastructure/system-ab-comparison.json",
        )

    /** The run views reference neither the A/B build variables nor the host filter. */
    private val oldFilter = Regex("""\$\{?(baseline|candidate|host)(?![A-Za-z0-9_])""")
    private val runFilter = Regex("""cluster="\$(baseline_cluster|candidate_cluster)"""")

    private fun dashboard(path: String): JsonObject = Json.parseToJsonElement(File("dashboards/$path").readText()).jsonObject

    private fun variables(dashboard: JsonObject): Map<String, JsonObject> =
        dashboard
            .getValue("templating")
            .jsonObject
            .getValue("list")
            .jsonArray
            .map { it.jsonObject }
            .associateBy { it.getValue("name").jsonPrimitive.content }

    private fun JsonObject.y(): Int =
        getValue("gridPos")
            .jsonObject
            .getValue("y")
            .jsonPrimitive.int

    /** The panels of the three new top rows: every top-level panel above the first of the older ones (ids below 1000). */
    private fun runViewPanels(dashboard: JsonObject): List<JsonObject> {
        val panels = dashboard.getValue("panels").jsonArray.map { it.jsonObject }
        val firstOld = panels.filter { it.getValue("id").jsonPrimitive.int < RUN_VIEW_FIRST_ID }.minOf { it.y() }
        return panels.filter { it.y() < firstOld }
    }

    @Test
    fun `the run variables replace cluster_a and cluster_b`() {
        val names = variables(dashboard("cassandra/cluster-comparison.json")).keys

        assertThat(names).contains("baseline_cluster", "candidate_cluster").doesNotContain("cluster_a", "cluster_b")
    }

    @Test
    fun `every comparison dashboard selects two runs over lookback and reads documents from doc_tenant`() {
        for (path in comparisonDashboards) {
            val variables = variables(dashboard(path))

            assertThat(variables.keys).describedAs(path).contains("baseline_cluster", "candidate_cluster", "lookback", "doc_tenant")
            for (run in listOf("baseline_cluster", "candidate_cluster")) {
                val variable = variables.getValue(run)
                assertThat(variable["multi"]?.jsonPrimitive?.content).describedAs("$path $run").isEqualTo("false")
                assertThat(variable.getValue("definition").jsonPrimitive.content).describedAs("$path $run").contains("[\$lookback:")
            }
            val lookback = variables.getValue("lookback")
            assertThat(
                lookback
                    .getValue("current")
                    .jsonObject
                    .getValue("value")
                    .jsonPrimitive.content,
            ).describedAs(path).isEqualTo("180d")
        }
    }

    @Test
    fun `the run views filter by the selected run only`() {
        for (path in comparisonDashboards) {
            val panels = runViewPanels(dashboard(path))
            val targets =
                panels.flatMap { panel ->
                    panel["targets"]?.jsonArray.orEmpty().map { panel.getValue("title").jsonPrimitive.content to it.jsonObject }
                }
            assertThat(targets).describedAs(path).isNotEmpty()

            for ((title, target) in targets) {
                val expr = target.getValue("expr").jsonPrimitive.content
                assertThat(expr).describedAs("$path '$title'").doesNotContainPattern(oldFilter.pattern).containsPattern(runFilter.pattern)
            }
        }
    }

    /**
     * Grafana names a table query's value field `Value` only when the panel has one query; with
     * several it is `Value #A`, `Value #B`, ... and `groupingToMatrix`, which needs one frame and a
     * field named `Value`, returns the rows unpivoted. So each summary is one query that holds every
     * figure, pivoted by run and given its column order and names.
     */
    @Test
    fun `each summary is one query pivoted to a row per figure with baseline, candidate and difference columns`() {
        val grouping =
            buildJsonObject {
                put("id", "groupingToMatrix")
                put(
                    "options",
                    buildJsonObject {
                        put("columnField", "run")
                        put("rowField", "figure")
                        put("valueField", "Value")
                    },
                )
            }
        for (path in comparisonDashboards) {
            val summary =
                runViewPanels(dashboard(path)).single { it["type"]?.jsonPrimitive?.content == "table" }
            val targets = summary.getValue("targets").jsonArray
            assertThat(targets).describedAs(path).hasSize(1)

            val transformations = summary.getValue("transformations").jsonArray.map { it.jsonObject }
            val organize = transformations.last().getValue("options").jsonObject
            val expr =
                targets
                    .single()
                    .jsonObject
                    .getValue("expr")
                    .jsonPrimitive.content
            for (run in SUMMARY_COLUMNS.keys.drop(1)) {
                assertThat(expr).describedAs(path).contains("\"run\", \"$run\"")
            }
            assertThat(transformations.map { it.getValue("id").jsonPrimitive.content })
                .describedAs(path)
                .containsExactly("groupingToMatrix", "organize")
            assertThat(transformations.first()).describedAs(path).isEqualTo(grouping)
            assertThat(organize.getValue("indexByName").jsonObject.mapValues { it.value.jsonPrimitive.int })
                .describedAs(path)
                .containsExactlyEntriesOf(SUMMARY_COLUMNS.keys.withIndex().associate { it.value to it.index })
            assertThat(organize.getValue("renameByName").jsonObject.mapValues { (it.value as JsonPrimitive).content })
                .describedAs(path)
                .containsExactlyEntriesOf(SUMMARY_COLUMNS)
        }
    }

    private companion object {
        /** The comparison rows were added with panel ids from 1000; every older panel has a lower id. */
        const val RUN_VIEW_FIRST_ID = 1000

        /** The matrix's columns, in order, and the header each shows. The first is the figure column groupingToMatrix names. */
        val SUMMARY_COLUMNS =
            linkedMapOf(
                "figure\\run" to "Figure",
                "1 baseline" to "Baseline",
                "2 candidate" to "Candidate",
                "3 difference %" to "Difference %",
            )
    }
}
