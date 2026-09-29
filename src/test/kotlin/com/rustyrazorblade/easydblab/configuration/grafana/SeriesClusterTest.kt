package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.configuration.grafana.ClusterFilterGuards.Language
import com.rustyrazorblade.easydblab.configuration.grafana.ClusterFilterGuards.Query
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every dashboard series keeps its cluster, in its labels and in its legend (owner decision, 2026-09-29).
 *
 * With two clusters selected, the dashboards rolled series up by host: `db0` of one cluster merged
 * with `db0` of the other, and no line said which cluster it belonged to.
 */
class SeriesClusterTest {
    private val short = """label_replace(x, "cluster_name", "${'$'}1", "cluster", "(.+)-[0-9a-f]{8}")"""

    @Test
    fun `a grouping without the cluster is reported, and one with it is not`() {
        assertThat(SeriesClusterGuards.droppedCluster("sum by (host_name) (rate(a[5m]))")).containsExactly("by (host_name)")
        assertThat(SeriesClusterGuards.droppedCluster("sum(rate(a[5m])) by (le)")).containsExactly("by (le)")
        assertThat(SeriesClusterGuards.droppedCluster("sum by (cluster, host_name) (a) / sum by (cluster) (b)")).isEmpty()
        assertThat(SeriesClusterGuards.droppedCluster("avg(a) by (cluster)")).isEmpty()
    }

    @Test
    fun `an aggregation with no grouping is reported`() {
        assertThat(SeriesClusterGuards.droppedCluster("sum(a) / count (b)")).containsExactly("sum", "count")
        assertThat(SeriesClusterGuards.droppedCluster("topk(5, a)")).containsExactly("topk")
        assertThat(SeriesClusterGuards.droppedCluster("max by (cluster) (sum(a))")).containsExactly("sum")
    }

    @Test
    fun `without keeps the cluster unless it names it`() {
        assertThat(SeriesClusterGuards.droppedCluster("sum without (instance) (a)")).isEmpty()
        assertThat(SeriesClusterGuards.droppedCluster("sum without (cluster, instance) (a)")).containsExactly("without (cluster, instance)")
    }

    @Test
    fun `range functions, strings, selectors and match clauses are not aggregations`() {
        val query =
            """max_over_time(a{job="sum", name=~"max|count"}[5m]) / on (cluster, host_name) group_left (count) """ +
                """label_replace(b, "x", "sum", "y", "(.*)")"""

        assertThat(SeriesClusterGuards.droppedCluster(query)).isEmpty()
    }

    @Test
    fun `a LogQL range aggregation grouped without the cluster is reported`() {
        val grouped = """avg_over_time({cluster=~"${'$'}cluster"} | json | unwrap gc_event_ms [5m]) by (host_name)"""

        assertThat(SeriesClusterGuards.droppedCluster(grouped)).containsExactly("by (host_name)")
        assertThat(
            SeriesClusterGuards.droppedCluster("""sum by (cluster) (count_over_time({cluster=~"${'$'}cluster"} |= "sum" [5m]))"""),
        ).isEmpty()
    }

    @Test
    fun `a legend must show the cluster, and a short name must be written by the query`() {
        assertThat(SeriesClusterGuards.legendProblems(Query("p", Language.PROMQL, "x", "{{host_name}}"))).hasSize(1)
        assertThat(SeriesClusterGuards.legendProblems(Query("p", Language.PROMQL, "x", ""))).hasSize(1)
        assertThat(SeriesClusterGuards.legendProblems(Query("p", Language.PROMQL, "x", "{{cluster}} {{host_name}}"))).isEmpty()
        assertThat(SeriesClusterGuards.legendProblems(Query("p", Language.PROMQL, "x", "{{cluster_name}} {{host_name}}")))
            .containsExactly("legend '{{cluster_name}} {{host_name}}' reads cluster_name, which the query does not write")
        assertThat(SeriesClusterGuards.legendProblems(Query("p", Language.PROMQL, short, "{{cluster_name}}"))).isEmpty()
        assertThat(SeriesClusterGuards.legendProblems(Query("p", Language.PROMQL, "x"))).isEmpty()
    }

    @Test
    fun `a log query draws no series, so its legend is not read`() {
        assertThat(SeriesClusterGuards.legendProblems(Query("p", Language.LOGQL, """{cluster=~"${'$'}cluster"}""", ""))).isEmpty()
        assertThat(SeriesClusterGuards.legendProblems(Query("p", Language.LOGQL, "sum(count_over_time({a=\"b\"}[5m]))", ""))).hasSize(1)
    }

    @Test
    fun `the queries that read one cluster, or every cluster, on purpose are exempt`() {
        val side = """sum(rate(a{cluster="${'$'}baseline_cluster"}[5m]))"""
        val both = """sum(a{cluster="${'$'}baseline_cluster"}) + sum(a{cluster=~"${'$'}cluster"})"""

        assertThat(SeriesClusterGuards.exempt(Query("variable 'cluster'", Language.PROMQL, "count by (cluster) (up)"), false)).isTrue()
        assertThat(SeriesClusterGuards.exempt(Query("panel 'p'", Language.PROMQL, side), false)).isTrue()
        assertThat(SeriesClusterGuards.exempt(Query("panel 'p'", Language.PROMQL, both), false)).isFalse()
        assertThat(SeriesClusterGuards.exempt(Query("panel 'p'", Language.PROMQL, "max by (name) (aws_s3_x)"), false)).isTrue()
        assertThat(SeriesClusterGuards.exempt(Query("panel 'p'", Language.PROMQL, "sum(up)"), true)).isTrue()
        assertThat(SeriesClusterGuards.exempt(Query("panel 'p'", Language.PROMQL, "sum(up)"), false)).isFalse()
    }

    @Test
    fun `every dashboard aggregation keeps the cluster`() {
        val dropped =
            seriesQueries().flatMap { (path, query) ->
                SeriesClusterGuards.droppedCluster(query.text).map { "$path ${query.where}: $it in ${query.text}" }
            }

        assertThat(dropped).isEmpty()
    }

    @Test
    fun `every dashboard legend shows the cluster`() {
        val hidden =
            seriesQueries().flatMap { (path, query) ->
                SeriesClusterGuards.legendProblems(query).map { "$path ${query.where}: $it" }
            }

        assertThat(hidden).isEmpty()
    }

    @Test
    fun `a grouping that names a label twice, directly or through a variable, is reported`() {
        val groupby = mapOf("groupby" to listOf("host_name", "cluster"))

        assertThat(
            SeriesClusterGuards.repeatedGrouping("sum by (cluster, cluster) (a)", emptyMap()),
        ).containsExactly("by (cluster, cluster)")
        assertThat(
            SeriesClusterGuards.repeatedGrouping("sum by (cluster, \$groupby) (a)", groupby),
        ).containsExactly("by (cluster, \$groupby)")
        assertThat(SeriesClusterGuards.repeatedGrouping("sum by (cluster, \${groupby}) (a)", groupby)).hasSize(1)
        assertThat(SeriesClusterGuards.repeatedGrouping("sum by (cluster, \$groupby) (a)", mapOf("groupby" to listOf("host_name"))))
            .isEmpty()
        assertThat(SeriesClusterGuards.repeatedGrouping("a / on (cluster, cluster) b", emptyMap())).isEmpty()
    }

    @Test
    fun `no dashboard aggregation groups by one label twice`() {
        val repeated =
            DashboardFiles.all().flatMap { file ->
                val dashboard = Json.parseToJsonElement(file.readText()).jsonObject
                val variables = customValues(dashboard)
                ClusterFilterGuards
                    .queries(dashboard)
                    .filter { it.language != Language.PROFILES }
                    .flatMap { query ->
                        SeriesClusterGuards.repeatedGrouping(query.text, variables).map { "${file.path} ${query.where}: $it" }
                    }
            }

        assertThat(repeated).isEmpty()
    }

    @Test
    fun `a table legend names a column, so a table is not held to the legend rule`() {
        assertThat(SeriesClusterGuards.legendProblems(Query("p", Language.PROMQL, short, "Write ops/s", "table"))).isEmpty()
        assertThat(SeriesClusterGuards.legendProblems(Query("p", Language.PROMQL, short, "Write ops/s", "timeseries"))).hasSize(1)
    }

    @Test
    fun `a table that writes cluster_name hides the long cluster and shows the short one as Cluster`() {
        val expr = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(short))

        fun table(transformations: String) =
            parse("""{"type": "table", "targets": [{"expr": $expr}], "transformations": $transformations}""")

        fun organize(
            hidden: String,
            renamed: String,
        ) = """[{"id": "organize", "options": {"excludeByName": {"$hidden": true}, "renameByName": {"$renamed": "Cluster"}}}]"""

        val shown = organize("cluster", "cluster_name")
        val joined = organize("cluster 1", "cluster_name 1")

        assertThat(SeriesClusterGuards.tableProblems(table(shown))).isEmpty()
        assertThat(SeriesClusterGuards.tableProblems(table(joined))).isEmpty()
        assertThat(SeriesClusterGuards.tableProblems(table("""[{"id": "merge", "options": {}}]"""))).containsExactly(
            "the long cluster column is not hidden",
            "cluster_name is not shown as Cluster",
        )
        assertThat(SeriesClusterGuards.tableProblems(parse("""{"type": "table", "targets": [{"expr": "up"}]}"""))).isEmpty()
    }

    @Test
    fun `an override that only matched the legend without its cluster is reported`() {
        val legends = listOf("{{cluster_name}} baseline", "{{cluster_name}} candidate {{host_name}}")

        assertThat(
            SeriesClusterGuards.staleOverrides(
                legends,
                listOf("byName" to "baseline", "byRegexp" to "^candidate.*", "byRegexp" to ".*candidate.*"),
            ),
        ).containsExactly("byName 'baseline'", "byRegexp '^candidate.*'")
        assertThat(
            SeriesClusterGuards.staleOverrides(legends, listOf("byRegexp" to "^(.* )?baseline$", "byRegexp" to "^(.* )?candidate.*")),
        ).isEmpty()
    }

    @Test
    fun `every table that writes cluster_name shows it as its Cluster column`() {
        val problems =
            DashboardFiles.all().flatMap { file ->
                panels(parse(file.readText())).flatMap { panel ->
                    SeriesClusterGuards.tableProblems(panel).map { "${file.path} panel '${title(panel)}': $it" }
                }
            }

        assertThat(problems).isEmpty()
    }

    @Test
    fun `every field override still matches the legend it styled`() {
        val stale =
            DashboardFiles.all().flatMap { file ->
                panels(parse(file.readText())).filter { (it["type"] as? JsonPrimitive)?.contentOrNull != "table" }.flatMap { panel ->
                    val legends =
                        (panel["targets"] as? JsonArray)
                            .orEmpty()
                            .mapNotNull { ((it as? JsonObject)?.get("legendFormat") as? JsonPrimitive)?.contentOrNull }
                    val matchers =
                        (((panel["fieldConfig"] as? JsonObject)?.get("overrides")) as? JsonArray)
                            .orEmpty()
                            .mapNotNull { (it as? JsonObject)?.get("matcher") as? JsonObject }
                            .mapNotNull { m ->
                                val id = (m["id"] as? JsonPrimitive)?.contentOrNull
                                val options = (m["options"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                                if (id == null || options == null) null else id to options
                            }
                    SeriesClusterGuards.staleOverrides(legends, matchers).map { "${file.path} panel '${title(panel)}': $it" }
                }
            }

        assertThat(stale).isEmpty()
    }

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun title(panel: JsonObject): String = (panel["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** Every panel of [dashboard], the panels of rows included. */
    private fun panels(dashboard: JsonObject): List<JsonObject> {
        fun walk(list: JsonArray?): List<JsonObject> =
            list.orEmpty().filterIsInstance<JsonObject>().flatMap { listOf(it) + walk(it["panels"] as? JsonArray) }
        return walk(dashboard["panels"] as? JsonArray)
    }

    /** Each custom variable's possible values: the comma-separated list in its `query`. */
    private fun customValues(dashboard: JsonObject): Map<String, List<String>> =
        ((dashboard["templating"] as? JsonObject)?.get("list") as? JsonArray)
            .orEmpty()
            .filterIsInstance<JsonObject>()
            .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "custom" }
            .associate { variable ->
                val name = (variable["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val values =
                    (variable["query"] as? JsonPrimitive)
                        ?.contentOrNull
                        .orEmpty()
                        .split(',')
                        .map { it.trim() }
                name to values
            }

    /** The PromQL and LogQL queries of every dashboard with a `cluster` variable, less the exempt ones. */
    private fun seriesQueries(): List<Pair<String, Query>> =
        DashboardFiles.all().flatMap { file ->
            val dashboard = Json.parseToJsonElement(file.readText()).jsonObject
            val testsListing = (dashboard["uid"] as? JsonPrimitive)?.contentOrNull == TESTS_UID
            ClusterFilterGuards
                .queries(dashboard)
                .takeIf { hasClusterVariable(dashboard) }
                .orEmpty()
                .filter { it.language != Language.PROFILES && !SeriesClusterGuards.exempt(it, testsListing) }
                .map { file.path to it }
        }

    private fun hasClusterVariable(dashboard: JsonObject): Boolean =
        ((dashboard["templating"] as? JsonObject)?.get("list") as? JsonArray)
            .orEmpty()
            .any { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull == "cluster" }

    private companion object {
        const val TESTS_UID = "tests"
    }
}
