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
