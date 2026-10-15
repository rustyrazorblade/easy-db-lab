package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The experiment picker, as Grafana 13.2.2 reads it (spike S6), and the tenant filter on every
 * cluster list of a dashboard that has one.
 *
 * - The picker's value and text are `<tenant>/<name>`. Grafana builds each `query_result` row as
 *   `{k="v",k2="v2"} <value> <ts>`, joining labels with a comma and no space, so a two-label regex
 *   with a space after the comma matches nothing and the picker is empty.
 * - The Prometheus plugin recognises `query_result(...)` only on one line; a line break makes the
 *   variable fall through to a series lookup that fails.
 * - The picker lists newest first (`sort_desc`, kept by `sort: 0`) and ends with a synthetic "All"
 *   (its value matches every tenant and name; its timestamp is 0), not Grafana's `includeAll`, which would open on the most
 *   expensive query. Tests has no "All".
 * - `exp_tenant` and `exp_name` split the selection; with "All" both are `.*`.
 * - Every cluster list on such a dashboard filters by `edl_tenant=~"$exp_tenant"`, so two
 *   experiments of one name in two tenants never merge.
 */
class ExperimentPickerTest {
    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text.trimIndent()).jsonObject

    private fun variables(dashboard: JsonObject): List<JsonObject> =
        ((dashboard["templating"] as? JsonObject)?.get("list") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Every query text a variable holds: its `definition`, its legacy string query, or its editor object's `query`. */
    private fun queryTexts(variable: JsonObject): List<String> =
        listOfNotNull(
            variable.string("definition"),
            (variable["query"] as? JsonPrimitive)?.contentOrNull,
            (variable["query"] as? JsonObject)?.string("query"),
        ).filter { it.isNotBlank() }

    private fun declaresExperiment(dashboard: JsonObject): Boolean =
        variables(dashboard).any { it.string("name") == DashboardVariables.EXPERIMENT }

    private fun variable(
        dashboard: JsonObject,
        name: String,
    ): JsonObject? = variables(dashboard).firstOrNull { it.string("name") == name }

    /** The cluster lists of [dashboard], when it declares `experiment`, that do not filter by the experiment's tenant. */
    private fun untenantedClusterLists(dashboard: JsonObject): List<String> =
        if (!declaresExperiment(dashboard)) {
            emptyList()
        } else {
            variables(dashboard)
                .filter { it.string("type") == "query" && it.string("name") in CLUSTER_PICKERS }
                .filterNot { variable -> queryTexts(variable).isNotEmpty() && queryTexts(variable).all { TENANT_FILTER in it } }
                .mapNotNull { it.string("name") }
        }

    /** The variables of [dashboard] whose `query_result(...)` holds a line break. */
    private fun multiLineQueryResults(dashboard: JsonObject): List<String> =
        variables(dashboard)
            .filter { variable -> queryTexts(variable).any { "query_result(" in it && ('\n' in it || '\r' in it) } }
            .mapNotNull { it.string("name") }

    /** The variables of [dashboard] whose two-label regex has a space after the comma. */
    private fun spacedTwoLabelRegexes(dashboard: JsonObject): List<String> =
        variables(dashboard)
            .filter { variable -> variable.string("regex")?.let { SPACED_LABELS.containsMatchIn(it) } == true }
            .mapNotNull { it.string("name") }

    @Test
    fun `a cluster list on an experiment dashboard without the tenant filter is reported`() {
        val unfiltered =
            parse(
                """
                {"templating": {"list": [
                  {"name": "experiment", "type": "query", "query": "x"},
                  {"name": "cluster", "type": "query", "definition": "query_result(count by (cluster) (up{experiment=~\"${'$'}exp_name\"}))"},
                  {"name": "cluster_a", "type": "query", "query": {"query": "query_result(count by (cluster) (up{edl_tenant=~\"${'$'}exp_tenant\"}))"}},
                  {"name": "host", "type": "query", "query": "label_values(up, host_name)"}]}}
                """,
            )
        val noExperiment = parse("""{"templating": {"list": [{"name": "cluster", "type": "query", "query": "label_values(up, cluster)"}]}}""")

        assertThat(untenantedClusterLists(unfiltered)).containsExactly("cluster")
        assertThat(untenantedClusterLists(noExperiment)).isEmpty()
    }

    @Test
    fun `a query_result with a line break is reported`() {
        val dashboard =
            parse(
                """
                {"templating": {"list": [
                  {"name": "broken", "type": "query", "query": {"query": "query_result(sort_desc(\n  max by (cluster) (up)))"}},
                  {"name": "fine", "type": "query", "query": {"query": "query_result(sort_desc(max by (cluster) (up)))"}},
                  {"name": "series", "type": "query", "query": "label_values(up,\n cluster)"}]}}
                """,
            )

        assertThat(multiLineQueryResults(dashboard)).containsExactly("broken")
    }

    @Test
    fun `a two-label regex with a space after the comma is reported`() {
        val dashboard =
            parse(
                """
                {"templating": {"list": [
                  {"name": "spaced", "type": "query", "regex": "/exp_key=\"(?<value>[^\"]+)\", exp_text=\"(?<text>[^\"]+)\"/"},
                  {"name": "tight", "type": "query", "regex": "${PICKER_REGEX.replace("\"", "\\\"")}"},
                  {"name": "one", "type": "query", "regex": "/cluster=\"(?<value>[^\"]+)\"/"}]}}
                """,
            )

        assertThat(spacedTwoLabelRegexes(dashboard)).containsExactly("spaced")
    }

    @Test
    fun `the comparison and Tests dashboards declare a single-select experiment picker`() {
        val byUid = DashboardFiles.all().associate { file -> parse(file.readText()).let { it.string("uid") to (file.path to it) } }

        val found =
            EXPERIMENT_DASHBOARDS.flatMap { uid ->
                val (path, dashboard) = byUid[uid] ?: return@flatMap listOf("no dashboard has uid $uid")
                pickerProblems(variable(dashboard, DashboardVariables.EXPERIMENT)).map { "$path: $it" }
            }

        assertThat(found).isEmpty()
    }

    @Test
    fun `a picker that is multi-select, native All, unsorted or spaced is reported`() {
        val good =
            parse(
                """
                {"name": "experiment", "type": "query", "sort": 0, "regex": "${PICKER_REGEX.replace("\"", "\\\"")}",
                 "query": {"query": "query_result(sort_desc(max by (exp_key, exp_text) (x[${'$'}lookback:300s])))"}}
                """,
            )

        assertThat(pickerProblems(good)).isEmpty()
        assertThat(pickerProblems(null)).containsExactly("declares no experiment")
        assertThat(pickerProblems(JsonObject(good + ("multi" to JsonPrimitive(true))))).containsExactly("experiment is multi-select")
        assertThat(pickerProblems(JsonObject(good + ("includeAll" to JsonPrimitive(true)))))
            .containsExactly("experiment uses Grafana's includeAll")
        assertThat(pickerProblems(JsonObject(good + ("sort" to JsonPrimitive(1))))).containsExactly("experiment sort is not 0")
        assertThat(pickerProblems(JsonObject(good + ("regex" to JsonPrimitive("/x/"))))).containsExactly("experiment regex is not $PICKER_REGEX")
        assertThat(pickerProblems(JsonObject(good + ("query" to JsonPrimitive("query_result(up)")))))
            .containsExactly("experiment does not list newest first over lookback")
    }

    private fun pickerProblems(picker: JsonObject?): List<String> {
        if (picker == null) return listOf("declares no experiment")
        val queries = queryTexts(picker)
        return listOfNotNull(
            "experiment is multi-select".takeIf { picker.string("multi") == "true" },
            "experiment uses Grafana's includeAll".takeIf { picker.string("includeAll") == "true" },
            "experiment sort is not 0".takeUnless { picker.string("sort") == "0" },
            "experiment regex is not $PICKER_REGEX".takeUnless { picker.string("regex") == PICKER_REGEX },
            "experiment does not list newest first over lookback".takeUnless {
                queries.isNotEmpty() && queries.all { it.startsWith("query_result(sort_desc(") && "[\$lookback:" in it }
            },
        )
    }

    @Test
    fun `every experiment picker but the Tests one ends with the synthetic All`() {
        val found =
            DashboardFiles.all().mapNotNull { file ->
                val dashboard = parse(file.readText())
                val picker = variable(dashboard, DashboardVariables.EXPERIMENT) ?: return@mapNotNull null
                val tests = dashboard.string("uid") == TESTS_UID
                val hasAll = queryTexts(picker).all { SYNTHETIC_ALL in it }
                val noAll = queryTexts(picker).none { SYNTHETIC_ALL in it }
                file.path.takeUnless { if (tests) noAll else hasAll }
            }

        assertThat(found).isEmpty()
    }

    @Test
    fun `every experiment dashboard splits the selection into exp_tenant and exp_name, and declares home_tenant`() {
        val found =
            DashboardFiles.all().flatMap { file ->
                val dashboard = parse(file.readText())
                if (!declaresExperiment(dashboard)) return@flatMap emptyList()
                splitProblems(dashboard).map { "${file.path}: $it" }
            }

        assertThat(found).isEmpty()
    }

    private fun splitProblems(dashboard: JsonObject): List<String> {
        val expected = mapOf(DashboardVariables.EXP_TENANT to EXP_TENANT_REGEX, DashboardVariables.EXP_NAME to EXP_NAME_REGEX)
        val split =
            expected.flatMap { (name, regex) ->
                val variable = variable(dashboard, name) ?: return@flatMap listOf("declares no $name")
                listOfNotNull(
                    "$name is not hidden".takeUnless { variable.string("hide") == HIDDEN },
                    "$name does not read the experiment with $SPLIT_QUERY".takeUnless {
                        queryTexts(variable).isNotEmpty() && queryTexts(variable).all { it == SPLIT_QUERY }
                    },
                    "$name regex is not $regex".takeUnless { variable.string("regex") == regex },
                )
            }
        val home = variable(dashboard, DashboardVariables.HOME_TENANT)
        val homeProblem =
            "declares no hidden custom home_tenant".takeUnless { home?.string("type") == "custom" && home.string("hide") == HIDDEN }
        return split + listOfNotNull(homeProblem)
    }

    @Test
    fun `every cluster list on an experiment dashboard filters by the experiment's tenant`() {
        val found =
            DashboardFiles.all().flatMap { file ->
                untenantedClusterLists(parse(file.readText())).map { "${file.path} variable '$it'" }
            }

        assertThat(found).isEmpty()
    }

    @Test
    fun `no query_result variable query holds a line break`() {
        val found = DashboardFiles.all().flatMap { file -> multiLineQueryResults(parse(file.readText())).map { "${file.path} variable '$it'" } }

        assertThat(found).isEmpty()
    }

    @Test
    fun `no two-label variable regex has a space after the comma`() {
        val found = DashboardFiles.all().flatMap { file -> spacedTwoLabelRegexes(parse(file.readText())).map { "${file.path} variable '$it'" } }

        assertThat(found).isEmpty()
    }

    @Test
    fun `the picker regex reads the comma-joined row Grafana builds`() {
        val row = """{exp_key="acme/e1",exp_text="acme/e1"} 1790000000 1790000300"""
        val match = Regex(PICKER_REGEX.removePrefix("/").removeSuffix("/")).find(row)

        assertThat(match?.groups?.get("value")?.value).isEqualTo("acme/e1")
        assertThat(match?.groups?.get("text")?.value).isEqualTo("acme/e1")
        assertThat(Regex(""""(?<value>[^"]+)", exp_text=""").containsMatchIn(row)).isFalse()
    }

    private companion object {
        const val TESTS_UID = "tests"

        /** The uids of the dashboards that pick an experiment: Experiment: Cassandra keeps the old Cluster Comparison uid. */
        val EXPERIMENT_DASHBOARDS =
            listOf(
                "c09599ec-8d87-4fe5-9346-9e85c41d33af",
                "experiment-system",
                "cluster-pair",
                "ab-comparison",
                "system-ab-comparison",
                TESTS_UID,
            )

        val CLUSTER_PICKERS = setOf(DashboardVariables.CLUSTER) + DashboardVariables.PAIR

        const val TENANT_FILTER = "edl_tenant=~\"\$exp_tenant\""
        const val PICKER_REGEX = """/exp_key="(?<value>[^"]+)",exp_text="(?<text>[^"]+)"/"""
        const val SPLIT_QUERY = """query_result(label_replace(vector(1), "k", "${'$'}experiment", "", ""))"""
        const val EXP_TENANT_REGEX = """/k="(?<value>[^/"]*)\//"""
        const val EXP_NAME_REGEX = """/k="[^/"]*\/(?<value>[^"]*)"/"""
        const val SYNTHETIC_ALL = """label_replace(label_replace(vector(0), "exp_key", ".*/.*", "", ""), "exp_text", "All", "", "")"""

        /** A label value's closing quote, a comma, whitespace, and the next label: Grafana writes no space there. */
        val SPACED_LABELS = Regex("""",\s+\w+=""")

        /** Grafana's `hide` value for a variable shown neither as a label nor as a picker. */
        const val HIDDEN = "2"
    }
}
