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
 * Every dashboard that reads metrics or logs shows only the clusters its `cluster` variable selects.
 *
 * Clusters of one tenant share one store, so a dashboard without the filter mixes every cluster
 * that ran at the same time, and a performance review reads another cluster's numbers as its own.
 * The Tests dashboard's listing is the one exception: it lists every cluster so that one can be
 * picked, and its `cluster` variable only picks the test whose documents it shows.
 */
class ClusterFilterTest {
    private fun unscoped(
        text: String,
        language: Language = Language.PROMQL,
    ): List<String> = ClusterFilterGuards.unscoped(Query("sample", language, text))

    @Test
    fun `a selector with a cluster matcher on the dashboard's cluster is scoped`() {
        assertThat(
            unscoped("""rate(cassandra_reads_total{host_name=~"${'$'}host", cluster=~"${'$'}cluster"}[${'$'}__rate_interval])"""),
        ).isEmpty()
        assertThat(unscoped("""up{cluster=~"${'$'}{cluster:regex}"}""")).isEmpty()
        assertThat(unscoped("""up{cluster="${'$'}baseline_cluster"} - up{cluster="${'$'}{candidate_cluster}"}""")).isEmpty()
    }

    @Test
    fun `each side of a binary expression must be scoped`() {
        val found = unscoped("""sum(a_total{cluster=~"${'$'}cluster"}) / sum(b_total{host_name=~"${'$'}host"}) or vector(0)""")

        assertThat(found).containsExactly("""b_total{host_name=~"${'$'}host"}""")
    }

    @Test
    fun `a bare metric name reads every cluster`() {
        assertThat(unscoped("pd_regions_status")).containsExactly("pd_regions_status")
        assertThat(unscoped("aws_s3_get_requests_sum / 60")).containsExactly("aws_s3_get_requests_sum")
    }

    @Test
    fun `a cluster matcher on another variable or a fixed value does not scope the selector`() {
        assertThat(unscoped("""up{cluster=~".+"}""")).hasSize(1)
        assertThat(unscoped("""up{k8s_cluster=~"${'$'}cluster"}""")).hasSize(1)
        assertThat(unscoped("""up{cluster=~"${'$'}KeeperCluster"}""")).hasSize(1)
    }

    @Test
    fun `functions, label lists, strings, ranges and offsets are not selectors`() {
        val query =
            """
            histogram_quantile(0.99, sum by (le, ${'$'}groupby) (rate(x_bucket{cluster=~"${'$'}cluster"}[5m] offset -${'$'}{cand_offset}s)))
            + on (host_name) group_left label_replace(time() - y{cluster=~"${'$'}cluster"} @ ${'$'}{base_end}, "a", "{b}", "c", "(.*)")
            """.trimIndent()

        assertThat(unscoped(query)).isEmpty()
    }

    @Test
    fun `a metric name that holds a variable is still a selector`() {
        assertThat(unscoped("""latency_${'$'}{percentile}_microseconds{host_name=~"${'$'}host"}""")).hasSize(1)
    }

    @Test
    fun `label_values lists over its selector, and over everything without one`() {
        assertThat(unscoped("""label_values(system_cpu_time_seconds_total{cluster=~"${'$'}cluster"}, host_name)""")).isEmpty()
        assertThat(unscoped("label_values(cassandra_storage_load_bytes, host_name)")).hasSize(1)
        assertThat(unscoped("label_values(host_name)")).hasSize(1)
    }

    @Test
    fun `a LogQL query is scoped by its stream selectors and not its pipeline`() {
        val scoped =
            """sum by (host_name) (count_over_time({cluster=~"${'$'}cluster", source="cassandra"}""" +
                """ | json | unwrap gc_event_ms | line_format "{{.x}}" [5m]))"""

        assertThat(unscoped(scoped, Language.LOGQL)).isEmpty()
        assertThat(unscoped("""{source="annotation"} | dashboard_uid="" """, Language.LOGQL)).hasSize(1)
    }

    @Test
    fun `queries are read from nested panels, links, annotations and variables`() {
        val dashboard =
            parse(
                """
                {"panels": [{"type": "row", "panels": [{"title": "inner", "targets": [{"expr": "a"}]}]},
                            {"title": "logs", "datasource": {"type": "loki", "uid": "${'$'}{logs_datasource}"},
                             "targets": [{"expr": "{source=\"x\"}"}]},
                            {"title": "profiles", "datasource": {"type": "grafana-pyroscope-datasource", "uid": "pyroscope"},
                             "targets": [{"labelSelector": "{service_name=\"x\"}"}]}],
                 "annotations": {"list": [{"name": "marks", "datasource": {"type": "loki"}, "expr": "{source=\"annotation\"}"}]},
                 "templating": {"list": [{"name": "host", "type": "query", "datasource": {"type": "prometheus"},
                                          "definition": "label_values(b, host_name)", "query": {"query": "label_values(b, host_name)"}}]}}
                """,
            )

        val found = ClusterFilterGuards.queries(dashboard).map { "${it.where}: ${it.language} ${it.text}" }

        assertThat(found).containsExactlyInAnyOrder(
            "panel 'inner': PROMQL a",
            "panel 'logs': LOGQL {source=\"x\"}",
            "annotation 'marks': LOGQL {source=\"annotation\"}",
            "variable 'host': PROMQL label_values(b, host_name)",
            "variable 'host': PROMQL label_values(b, host_name)",
        )
    }

    @Test
    fun `every dashboard that queries metrics or logs declares the cluster variable`() {
        val missing =
            DashboardFiles.all().mapNotNull { file ->
                val dashboard = parse(file.readText())
                file.path.takeIf { ClusterFilterGuards.queries(dashboard).isNotEmpty() && "cluster" !in variableNames(dashboard) }
            }

        assertThat(missing).isEmpty()
    }

    @Test
    fun `every metrics and logs query filters by the selected clusters`() {
        val unscoped =
            DashboardFiles.all().flatMap { file ->
                val dashboard = parse(file.readText())
                val testsListing = (dashboard["uid"] as? JsonPrimitive)?.contentOrNull == TESTS_UID
                ClusterFilterGuards
                    .queries(dashboard)
                    .filterNot { it.where.removePrefix("variable '").removeSuffix("'") in ClusterFilterGuards.CLUSTER_LISTS }
                    .filterNot { testsListing && it.where.startsWith("panel ") }
                    .flatMap { query -> ClusterFilterGuards.unscoped(query).map { "${file.path} ${query.where}: $it in ${query.text}" } }
            }

        assertThat(unscoped).isEmpty()
    }

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text.trimIndent()).jsonObject

    private fun variableNames(dashboard: JsonObject): Set<String> =
        ((dashboard["templating"] as? JsonObject)?.get("list") as? JsonArray)
            .orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull }
            .toSet()

    private companion object {
        const val TESTS_UID = "tests"
    }
}
