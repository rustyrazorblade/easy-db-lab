package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The comparison dashboards, in two kinds.
 *
 * The pair dashboards (the cluster pair, `ab-comparison`, `system-ab-comparison`) select cluster A
 * and cluster B of the selected experiment and compare them in three rows at the top: overlay,
 * side by side, and a summary with both reports. Those rows filter by the selected side only: the
 * build and host pickers (`build_a`/`build_b`/`host`, `hosts_a`/`hosts_b`) apply to the older panels
 * below. A lists oldest first and has no installed default; B lists newest first.
 *
 * The Experiment dashboards (Experiment: Cassandra, Experiment: System) compare any number of the
 * experiment's clusters, each over its own window: a leaderboard and distributions pinned at the
 * experiment's end, and every other panel on absolute time covering the experiment's span through
 * the panel time override.
 */
class ComparisonDashboardsTest {
    private val pairDashboards =
        listOf(
            "cassandra/cluster-pair.json",
            "cassandra/ab-comparison.json",
            "infrastructure/system-ab-comparison.json",
        )

    private val experimentDashboards =
        listOf(
            "cassandra/experiment-cassandra.json",
            "infrastructure/experiment-system.json",
        )

    /**
     * The build and host pickers each pair dashboard keeps for its older panels. The run views must
     * not read them, so each is first checked to be declared, or the check would pass on nothing.
     */
    private val olderPanelFilters =
        mapOf(
            "cassandra/cluster-pair.json" to emptyList(),
            "cassandra/ab-comparison.json" to listOf("build_a", "build_b", "host"),
            "infrastructure/system-ab-comparison.json" to listOf("hosts_a", "hosts_b"),
        )

    private val runFilter = Regex("""cluster="\$\{?(cluster_a|cluster_b)""")

    private fun dashboard(path: String): JsonObject {
        assertThat(File("dashboards/$path")).describedAs(path).exists()
        return Json.parseToJsonElement(File("dashboards/$path").readText()).jsonObject
    }

    /** [check] on every one of [paths] that exists, plus a problem for each that does not. */
    private fun problems(
        paths: List<String>,
        check: (String, JsonObject) -> List<String>,
    ): List<String> =
        paths.flatMap { path ->
            val file = File("dashboards/$path")
            if (!file.exists()) {
                listOf("$path: missing")
            } else {
                check(path, Json.parseToJsonElement(file.readText()).jsonObject).map { "$path: $it" }
            }
        }

    private fun variables(dashboard: JsonObject): Map<String, JsonObject> =
        ((dashboard["templating"] as? JsonObject)?.get("list") as? JsonArray)
            .orEmpty()
            .map { it.jsonObject }
            .associateBy { it.getValue("name").jsonPrimitive.content }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.y(): Int =
        getValue("gridPos")
            .jsonObject
            .getValue("y")
            .jsonPrimitive.int

    /** The query a variable runs: its `definition`, its editor object's `query`, or its legacy string. */
    private fun queryOf(variable: JsonObject): String =
        variable.string("definition")
            ?: (variable["query"] as? JsonObject)?.string("query")
            ?: variable.string("query").orEmpty()

    /**
     * The panels of the three top rows: every top-level panel above the first older panel (ids
     * below 1000). A dashboard with no older panel, like the cluster pair, is all run views from id
     * 1000 on.
     */
    private fun runViewPanels(dashboard: JsonObject): List<JsonObject> {
        val panels = dashboard["panels"]?.jsonArray.orEmpty().map { it.jsonObject }
        val firstOld = panels.filter { it.getValue("id").jsonPrimitive.int < RUN_VIEW_FIRST_ID }.minOfOrNull { it.y() }
        return if (firstOld == null) panels else panels.filter { it.y() < firstOld }
    }

    /** Every panel of [dashboard], rows' nested panels included. */
    private fun allPanels(dashboard: JsonObject): List<JsonObject> {
        fun walk(list: JsonElement?): List<JsonObject> =
            (list as? JsonArray).orEmpty().filterIsInstance<JsonObject>().flatMap { listOf(it) + walk(it["panels"]) }
        return walk(dashboard["panels"])
    }

    private fun exprs(panel: JsonObject): List<String> =
        panel["targets"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.string("expr") }

    @Test
    fun `Experiment Cassandra replaces Cluster Comparison and keeps its uid`() {
        assertThat(File("dashboards/cassandra/cluster-comparison.json")).doesNotExist()

        val experiment = dashboard("cassandra/experiment-cassandra.json")
        assertThat(experiment.string("uid")).isEqualTo(CLUSTER_COMPARISON_UID)
        assertThat(experiment.string("title")).isEqualTo("Experiment: Cassandra")
    }

    @Test
    fun `Experiment Cassandra picks an experiment and any of its clusters, never a pair`() {
        val found =
            problems(listOf("cassandra/experiment-cassandra.json")) { _, dashboard ->
                val names = variables(dashboard).keys
                val missing = listOf("experiment", "exp_tenant", "exp_name", "home_tenant", "cluster", "filters") - names
                missing.map { "declares no $it" } + (PAIR_VARIABLES intersect names).map { "declares the pair variable $it" }
            }

        assertThat(found).isEmpty()
    }

    @Test
    fun `every pair dashboard selects A and B over lookback and shows each side's report`() {
        assertThat(problems(pairDashboards) { _, dashboard -> pairProblems(dashboard) }).isEmpty()
    }

    private fun pairProblems(dashboard: JsonObject): List<String> {
        val variables = variables(dashboard)
        val missing = REQUIRED_PAIR_VARIABLES - variables.keys
        val retired = RETIRED_VARIABLES intersect variables.keys
        val sides =
            listOf(
                "cluster_a" to "query_result(sort(min by (cluster) (min_over_time(",
                "cluster_b" to "query_result(sort_desc(max by (cluster) (max_over_time(",
            ).flatMap { (side, order) ->
                val variable = variables[side] ?: return@flatMap emptyList()
                listOfNotNull(
                    "$side is multi-select".takeIf { variable.string("multi") == "true" },
                    "$side does not list over lookback".takeUnless { "[\$lookback:" in queryOf(variable) },
                    "$side does not list ${if (side == "cluster_a") "oldest" else "newest"} first with $order"
                        .takeUnless { queryOf(variable).startsWith(order) },
                    "$side sort is not 0".takeUnless { variable.string("sort") == "0" },
                )
            }
        val reports =
            listOf("a_report" to "cluster_a", "b_report" to "cluster_b").mapNotNull { (report, side) ->
                val variable = variables[report] ?: return@mapNotNull null
                "$report does not read \$$side".takeUnless { "\$$side" in queryOf(variable) }
            }
        val lookback =
            variables["lookback"]
                ?.get("current")
                ?.jsonObject
                ?.get("value")
                ?.jsonPrimitive
                ?.content
        return missing.map { "declares no $it" } + retired.map { "still declares $it" } + sides + reports +
            listOfNotNull("lookback does not default to 180d".takeUnless { lookback == "180d" })
    }

    @Test
    fun `the build and host comparisons keep their pickers under A and B names`() {
        val found =
            problems(pairDashboards) { path, dashboard ->
                val names = variables(dashboard).keys
                val own = olderPanelFilters.getValue(path)
                val others = (olderPanelFilters.values.flatten().toSet() + RETIRED_BUILD_PICKERS) - own.toSet()
                (own - names).map { "declares no $it" } + (others intersect names).map { "declares $it" }
            }

        assertThat(found).isEmpty()
    }

    @Test
    fun `the run views filter by the selected side only`() {
        val found =
            problems(pairDashboards) { path, dashboard ->
                val filters = olderPanelFilters.getValue(path)
                val older = filters.takeIf { it.isNotEmpty() }?.let { Regex("""\$\{?(${it.joinToString("|")})(?![A-Za-z0-9_])""") }
                val targets =
                    runViewPanels(dashboard).flatMap { panel -> exprs(panel).map { panel.string("title") to it } }
                val undeclared =
                    (
                        filters -
                            variables(
                                dashboard,
                            ).keys
                    ).map { "declares no $it, so the run views are not checked against it" }
                undeclared + listOfNotNull("has no run view targets".takeIf { targets.isEmpty() }) +
                    targets.mapNotNull { (title, expr) ->
                        when {
                            older?.containsMatchIn(expr) == true -> "'$title' reads a build or host picker"
                            !runFilter.containsMatchIn(expr) -> "'$title' does not filter by cluster_a or cluster_b"
                            else -> null
                        }
                    }
            }

        assertThat(found).isEmpty()
    }

    /**
     * Grafana names a table query's value field `Value` only when the panel has one query; with
     * several it is `Value #A`, `Value #B`, ... and `groupingToMatrix`, which needs one frame and a
     * field named `Value`, returns the rows unpivoted. So each summary is one query that holds every
     * figure, pivoted by run and given its column order and names.
     */
    @Test
    fun `each summary is one query pivoted to a row per figure with A, B and B vs A columns`() {
        assertThat(problems(pairDashboards) { _, dashboard -> summaryProblems(dashboard) }).isEmpty()
    }

    private fun summaryProblems(dashboard: JsonObject): List<String> {
        val summary =
            runViewPanels(dashboard).singleOrNull { it.string("type") == "table" } ?: return listOf("has no single summary table")
        val targets = summary["targets"]?.jsonArray.orEmpty()
        val transformations = summary["transformations"]?.jsonArray.orEmpty().map { it.jsonObject }
        val organize = transformations.lastOrNull()?.get("options")?.jsonObject
        val expr = exprs(summary).firstOrNull().orEmpty()
        val columns = SUMMARY_COLUMNS.keys.withIndex().associate { it.value to it.index }
        return listOfNotNull(
            "summary has ${targets.size} queries, not one".takeUnless { targets.size == 1 },
            "summary does not label every run (${SUMMARY_COLUMNS.keys.drop(1)})"
                .takeUnless { SUMMARY_COLUMNS.keys.drop(1).all { "\"run\", \"$it\"" in expr } },
            "summary transformations are not groupingToMatrix then organize"
                .takeUnless { transformations.map { it.string("id") } == listOf("groupingToMatrix", "organize") },
            "summary grouping is not run by figure".takeUnless { transformations.firstOrNull() == GROUPING },
            "summary column order is not ${columns.keys}"
                .takeUnless { organize?.get("indexByName")?.jsonObject?.mapValues { it.value.jsonPrimitive.int } == columns },
            "summary headers are not ${SUMMARY_COLUMNS.values}"
                .takeUnless { organize?.get("renameByName")?.jsonObject?.mapValues { it.value.jsonPrimitive.content } == SUMMARY_COLUMNS },
        )
    }

    /**
     * A difference over a zero A is NaN (0 vs 0) or infinite, so each difference term divides by A
     * filtered to non-zero values: over a zero A the term returns nothing and the "B vs A %" cell
     * stays empty.
     */
    @Test
    fun `each difference is empty when A is 0`() {
        val guardedDifference = Regex("""^label_replace\(label_replace\(100 \* \(\((.+)\) - \((.+)\)\) / \(\(\2\) != 0\), "figure", """)
        val found =
            problems(pairDashboards) { _, dashboard ->
                val summary =
                    runViewPanels(dashboard).singleOrNull { it.string("type") == "table" } ?: return@problems listOf("has no summary table")
                val terms =
                    exprs(summary)
                        .firstOrNull()
                        .orEmpty()
                        .split("\nor ")
                        .filter { "\"run\", \"$DIFFERENCE_RUN\"" in it }
                listOfNotNull(
                    "summary has no \"$DIFFERENCE_RUN\" term".takeIf { terms.isEmpty() },
                    "summary description does not say the difference is empty when A is 0"
                        .takeUnless { "empty when A is 0" in summary.string("description").orEmpty() },
                ) + terms.filterNot { guardedDifference.containsMatchIn(it) }.map { "unguarded difference: ${it.take(TERM_PREVIEW)}" }
            }

        assertThat(found).isEmpty()
    }

    @Test
    fun `every experiment dashboard declares the span helpers, seconds read from days`() {
        val found =
            problems(experimentDashboards) { _, dashboard ->
                val variables = variables(dashboard)
                val missing = SPAN_HELPERS.filterNot { it in variables }.map { "declares no $it" }
                val split =
                    SPLIT_SPANS.flatMap { span ->
                        listOfNotNull(
                            "${span}_d is not the whole days of $span".takeUnless {
                                variables["${span}_d"]?.let(::queryOf) == "query_result(floor(vector(\${$span}) / 86400))"
                            },
                            "${span}_s is not the seconds left after ${span}_d".takeUnless {
                                variables["${span}_s"]?.let(::queryOf) == "query_result(vector(\${$span}) - 86400 * \${${span}_d})"
                            },
                        )
                    }
                missing + split
            }

        assertThat(found).isEmpty()
    }

    /**
     * The dashboard range stays relative, so a panel on absolute time sees an experiment that ran
     * days ago only through the span override. The leaderboard and the distributions are instant
     * queries pinned at the experiment's end and need none.
     */
    @Test
    fun `every experiment panel covers the experiment's span or is pinned at its end`() {
        val found =
            problems(experimentDashboards) { _, dashboard ->
                allPanels(dashboard)
                    .filter { it.string("type") !in setOf("row", "text") && exprs(it).isNotEmpty() }
                    .filterNot { panel -> exprs(panel).all { PINNED_AT_END in it } }
                    .filterNot { it.string("timeFrom") == SPAN_FROM && it.string("timeShift") == SPAN_SHIFT }
                    .map { "panel '${it.string("title")}' has neither the span override nor @ \${exp_end}" }
            }

        assertThat(found).isEmpty()
    }

    @Test
    fun `every experiment dashboard has a per-cluster leaderboard and distributions at the experiment's end`() {
        val found =
            problems(experimentDashboards) { _, dashboard ->
                val panels = allPanels(dashboard)
                val perCluster = { panel: JsonObject, extra: String ->
                    exprs(panel).isNotEmpty() && exprs(panel).all { PINNED_AT_END in it && BY_CLUSTER in it && extra in it }
                }
                listOfNotNull(
                    "has no leaderboard table with every figure by (cluster) at @ \${exp_end}"
                        .takeUnless { panels.any { it.string("type") == "table" && perCluster(it, "") } },
                    "has no distribution bar chart of quantile_over_time by (cluster) at @ \${exp_end}"
                        .takeUnless { panels.any { it.string("type") == "barchart" && perCluster(it, "quantile_over_time(") } },
                )
            }

        assertThat(found).isEmpty()
    }

    /**
     * With All the frame shows the documents server's error page, so the instruction must be on
     * the panel itself, in its title or in the content's text, not in the hover description.
     */
    @Test
    fun `every experiment dashboard's report panel tells the operator to select one experiment for All`() {
        val found =
            problems(experimentDashboards) { _, dashboard ->
                val reports =
                    allPanels(dashboard).filter { panel ->
                        panel.string("type") == "text" && EXPERIMENT_REPORT in panel["options"]?.jsonObject?.string("content").orEmpty()
                    }
                listOfNotNull(
                    "has no report panel".takeIf { reports.isEmpty() },
                    "report panel does not show the instruction to select one experiment"
                        .takeUnless { reports.all { SELECT_ONE_EXPERIMENT in visibleText(it).lowercase() } },
                )
            }

        assertThat(found).isEmpty()
    }

    @Test
    fun `an instruction only in the description is not visible`() {
        val hidden =
            Json
                .parseToJsonElement(
                    """{"type": "text", "title": "Report", "description": "Select one experiment to see it.",
                       "options": {"content": "<iframe src=\"x\"></iframe>"}}""",
                ).jsonObject
        val shown =
            Json
                .parseToJsonElement(
                    """{"type": "text", "title": "Report", "options": {"content": "<p>Select one experiment.</p><iframe></iframe>"}}""",
                ).jsonObject

        assertThat(visibleText(hidden).lowercase()).doesNotContain(SELECT_ONE_EXPERIMENT)
        assertThat(visibleText(shown).lowercase()).contains(SELECT_ONE_EXPERIMENT)
    }

    /** What a Text panel shows without hovering: its title and its content with the HTML tags removed. */
    private fun visibleText(panel: JsonObject): String {
        val content = panel["options"]?.jsonObject?.string("content").orEmpty()
        return panel.string("title").orEmpty() + "\n" + content.replace(Regex("<[^>]*>"), " ")
    }

    /**
     * A span length is the last step minus the first plus one step: the first and last samples
     * sit on the Resolution grid, so a cluster seen at one step spans one step, not 0s (which
     * PromQL refuses as a range and Grafana as a `timeFrom`), and a window from the last step
     * back by the length reaches the first step.
     */
    @Test
    fun `every span length includes the resolution step`() {
        val found =
            problems(pairDashboards + experimentDashboards) { path, dashboard ->
                spanLengthProblems(variables(dashboard), if (path in pairDashboards) PAIR_SPANS else EXPERIMENT_SPANS)
            }

        assertThat(found).isEmpty()
    }

    @Test
    fun `a span length without the resolution step is caught`() {
        fun variable(query: String) =
            buildJsonObject {
                put("name", "a_len")
                put("definition", query)
            }

        assertThat(spanLengthProblems(mapOf("a_len" to variable("query_result(vector(\${a_end} - \${a_start}))")), listOf("a")))
            .containsExactly("a_len is not query_result(vector(\${a_end} - \${a_start} + \${resolution}))")
        assertThat(
            spanLengthProblems(mapOf("a_len" to variable("query_result(vector(\${a_end} - \${a_start} + \${resolution}))")), listOf("a")),
        ).isEmpty()
        assertThat(spanLengthProblems(emptyMap(), listOf("a"))).containsExactly("declares no a_len")
    }

    /** Each `<side>_len` of [sides] in [variables] that is missing or not `<side>_end - <side>_start + resolution`. */
    private fun spanLengthProblems(
        variables: Map<String, JsonObject>,
        sides: List<String>,
    ): List<String> =
        sides.mapNotNull { side ->
            val expected = "query_result(vector(\${${side}_end} - \${${side}_start} + \${resolution}))"
            when (val variable = variables["${side}_len"]) {
                null -> "declares no ${side}_len"
                else -> "${side}_len is not $expected".takeUnless { queryOf(variable) == expected }
            }
        }

    /**
     * A delta `100 * (B - A) / A` over a zero A is NaN or infinite, and every pair dashboard says a
     * delta is blank when A is 0. So every delta divides by A filtered to non-zero values.
     */
    @Test
    fun `every delta on a pair dashboard is empty when A is 0`() {
        val found =
            problems(pairDashboards) { _, dashboard ->
                allPanels(dashboard).flatMap { panel ->
                    exprs(panel).mapNotNull { expr ->
                        "'${panel.string("title")}': unguarded delta ${expr.take(TERM_PREVIEW)}".takeIf { unguardedDelta(expr) }
                    }
                }
            }

        assertThat(found).isEmpty()
    }

    @Test
    fun `an unguarded delta is caught`() {
        val a = "avg by (cluster) (x{cluster=~\"\$cluster\", build=~\"\$build_a\"})"
        val b = "avg by (cluster) (x{cluster=~\"\$cluster\", build=~\"\$build_b\"})"
        val tail = ", \"cluster_name\", \"\$1\", \"cluster\", \"(.+)\")"

        assertThat(unguardedDelta("label_replace(100 * (\n    ($b)\n  - ($a)\n) / ($a)$tail")).isTrue()
        assertThat(unguardedDelta("label_replace(100 * ($b - $a) / $a$tail")).isTrue()
        assertThat(unguardedDelta("label_replace(100 * (\n    ($b)\n  - ($a)\n) / (($a) != 0)$tail")).isFalse()
        assertThat(unguardedDelta("label_replace(100 * ($b - $a) / (($a) != 0)$tail")).isFalse()
        assertThat(unguardedDelta("label_replace(100 * (1 - sum by (cluster) (a) / sum by (cluster) (b))$tail")).isFalse()
    }

    /**
     * Whether [expr] is `label_replace(100 * (<group>) / <divisor>, "cluster_name", ...)` whose
     * divisor appears inside the group (the A of `B - A`) and is not filtered with `!= 0`.
     */
    private fun unguardedDelta(expr: String): Boolean {
        val prefix = "label_replace(100 * "
        val body = expr.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.substringBefore(", \"cluster_name\"") ?: return false
        val end = groupEnd(body)
        val divisor =
            body
                .substring(end + 1)
                .trimStart('\n')
                .takeIf { end > 0 && it.startsWith(" / ") }
                ?.removePrefix(" / ") ?: return false
        val core = if (groupEnd(divisor) == divisor.length - 1) divisor.substring(1, divisor.length - 1) else divisor
        return core in body.substring(0, end + 1) && !divisor.endsWith("!= 0)")
    }

    /** The index of the parenthesis that closes the group [text] opens with, or -1. */
    private fun groupEnd(text: String): Int {
        if (!text.startsWith("(")) return -1
        var depth = 0
        text.forEachIndexed { i, c ->
            if (c == '(') depth++
            if (c == ')') depth--
            if (depth == 0) return i
        }
        return -1
    }

    private companion object {
        /** The comparison rows were added with panel ids from 1000; every older panel has a lower id. */
        const val RUN_VIEW_FIRST_ID = 1000

        /** The uid of the former Cluster Comparison, which Experiment: Cassandra keeps. */
        const val CLUSTER_COMPARISON_UID = "c09599ec-8d87-4fe5-9346-9e85c41d33af"

        val PAIR_VARIABLES = setOf("cluster2", "baseline_cluster", "candidate_cluster", "cluster_a", "cluster_b")
        val REQUIRED_PAIR_VARIABLES = listOf("experiment", "home_tenant", "cluster_a", "cluster_b", "lookback", "a_report", "b_report")
        val RETIRED_VARIABLES = setOf("baseline_cluster", "candidate_cluster", "doc_tenant")

        /** The build and host pickers of `ab-comparison` and `system-ab-comparison` before the A/B rename. */
        val RETIRED_BUILD_PICKERS = setOf("baseline", "candidate")

        /** The matrix's columns, in order, and the header each shows. The first is the figure column groupingToMatrix names. */
        val SUMMARY_COLUMNS =
            linkedMapOf(
                "figure\\run" to "Figure",
                "1 A" to "A",
                "2 B" to "B",
                "3 B vs A %" to "B vs A %",
            )
        const val DIFFERENCE_RUN = "3 B vs A %"
        const val TERM_PREVIEW = 120

        val GROUPING =
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

        /** The span helpers whose seconds and whole days the panel override reads, seconds first. */
        val SPLIT_SPANS = listOf("exp_len", "exp_since_end")
        val SPAN_HELPERS = listOf("exp_start", "exp_end") + SPLIT_SPANS.flatMap { listOf(it, "${it}_d", "${it}_s") }
        const val SPAN_FROM = "\${exp_len_s}s-\${exp_len_d}d"
        const val SPAN_SHIFT = "\${exp_since_end_s}s-\${exp_since_end_d}d"
        const val PINNED_AT_END = "@ \${exp_end}"
        const val BY_CLUSTER = "by (cluster)"
        const val EXPERIMENT_REPORT = "reports/\${experiment}/index.html"
        const val SELECT_ONE_EXPERIMENT = "select one experiment"

        /** The span lengths each kind of dashboard derives from its start and end helpers. */
        val PAIR_SPANS = listOf("a", "b")
        val EXPERIMENT_SPANS = listOf("exp")
    }
}
